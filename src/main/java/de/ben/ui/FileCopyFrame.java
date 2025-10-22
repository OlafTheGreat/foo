package de.ben.ui;

import de.ben.FileCopy;
import de.ben.FileCopyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hauptfenster der FileCopy-Anwendung.
 * Diese Klasse implementiert eine grafische Benutzeroberfläche für die Dateikopier-Funktionalität.
 * Sie ermöglicht die Auswahl von Quell- und Zieldateien/-verzeichnissen sowie verschiedene
 * Kopieroptionen und zeigt den Fortschritt der Kopieroperation an.
 *
 * <p>Thread-Safety: Diese Klasse ist thread-safe durch die Verwendung von SwingUtilities.invokeLater()
 * für UI-Updates und thread-sichere Datenstrukturen für gemeinsam genutzte Ressourcen.</p>
 *
 * @author Ben
 * @version 2.0
 * @since 1.0
 */
public final class FileCopyFrame extends JFrame {
    private static final Logger LOG = LoggerFactory.getLogger(FileCopyFrame.class);
    private static final int DEFAULT_WIDTH = 600;
    private static final int DEFAULT_HEIGHT = 400;
    private static final int MIN_WIDTH = 500;
    private static final int MIN_HEIGHT = 300;
    private static final int TEXT_FIELD_COLUMNS = 30;
    private static final int MAX_THREADS = 32;
    private static final String DEFAULT_TITLE = "FileCopy - Hochperformante Dateikopierer";

    private final transient JTextField sourceField;
    private final transient JTextField targetField;
    private final transient JSpinner threadsSpinner;
    private final transient JProgressBar progressBar;
    private final transient JButton startButton;
    private final transient JButton cancelButton;
    private final transient JCheckBox followSymlinksBox;
    private final transient JCheckBox preservePermissionsBox;
    private final transient JTextArea statusArea;
    private final transient ExecutorService executor;
    private final transient AtomicBoolean isCancelled;

    /**
     * Erstellt ein neues FileCopyFrame.
     * Initialisiert alle UI-Komponenten und registriert die notwendigen Event-Handler.
     */
    public FileCopyFrame() {
        super(DEFAULT_TITLE);

        this.executor = createExecutor();
        this.isCancelled = new AtomicBoolean(false);

        // Initialisiere Hauptkomponenten
        this.sourceField = createTextField();
        this.targetField = createTextField();
        this.threadsSpinner = createThreadsSpinner();
        this.progressBar = createProgressBar();
        this.startButton = createButton("Start", this::startCopy);
        this.cancelButton = createButton("Abbrechen", this::cancelCopy);
        this.cancelButton.setEnabled(false); // Button initial deaktivieren
        this.followSymlinksBox = createCheckBox("Symlinks folgen", true);
        this.preservePermissionsBox = createCheckBox("Berechtigungen beibehalten", false);
        this.statusArea = createStatusArea();

        initializeUI();
        setupWindowListener();
    }

    /**
     * Initialisiert das Hauptfenster und alle UI-Komponenten.
     */
    private void initializeUI() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        mainPanel.add(createInputPanel(), BorderLayout.NORTH);
        mainPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        mainPanel.add(createProgressPanel(), BorderLayout.SOUTH);

        add(mainPanel);
        setLocationRelativeTo(null);
    }

    /**
     * Erstellt das Panel für die Eingabefelder und Optionen.
     * @return JPanel mit den Eingabekomponenten
     */
    private JPanel createInputPanel() {
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Quelle
        addLabelAndField(inputPanel, gbc, "Quelle:", sourceField,
            createButton("...", this::selectSource));

        // Ziel
        gbc.gridy = 1;
        addLabelAndField(inputPanel, gbc, "Ziel:", targetField,
            createButton("...", this::selectTarget));

        // Optionen
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        inputPanel.add(createOptionsPanel(), gbc);

        return inputPanel;
    }

    /**
     * Erstellt das Panel für die Fortschrittsanzeige und Steuerungsbuttons.
     * @return JPanel mit Fortschrittsbalken und Buttons
     */
    private JPanel createProgressPanel() {
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.add(progressBar, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(startButton);
        buttonPanel.add(cancelButton);
        progressPanel.add(buttonPanel, BorderLayout.SOUTH);

        return progressPanel;
    }

    /**
     * Erstellt ein neues JTextField mit standardisierten Eigenschaften.
     * @return konfiguriertes JTextField
     */
    private JTextField createTextField() {
        JTextField field = new JTextField(TEXT_FIELD_COLUMNS);
        field.setEditable(true);
        return field;
    }

    /**
     * Erstellt einen neuen Button mit Action-Listener.
     * @param text Button-Text
     * @param action auszuführende Aktion
     * @return konfigurierter JButton
     */
    private JButton createButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * Startet den Kopiervorgang mit den aktuellen Einstellungen.
     * Validiert die Eingaben und zeigt Fehlermeldungen an, falls notwendig.
     */
    private void startCopy() {
        String source = sourceField.getText().trim();
        String target = targetField.getText().trim();

        if (!validateInputs(source, target)) {
            return;
        }

        Path sourcePath = Path.of(source);
        Path targetPath = Path.of(target);

        if (!validatePaths(sourcePath, targetPath)) {
            return;
        }

        int threads = (Integer) threadsSpinner.getValue();
        isCancelled.set(false);
        updateUIForCopyStart();

        submitCopyTask(sourcePath, targetPath, threads);
    }

    /**
     * Validiert die Eingabefelder.
     * @param source Quellpfad
     * @param target Zielpfad
     * @return true wenn die Eingaben gültig sind
     */
    private boolean validateInputs(String source, String target) {
        if (source.isEmpty() || target.isEmpty()) {
            showError("Bitte Quelle und Ziel auswählen");
            return false;
        }
        return true;
    }

    /**
     * Validiert die Pfade auf Existenz und Zugriffsrechte.
     * @param source Quellpfad
     * @param target Zielpfad
     * @return true wenn die Pfade gültig sind
     */
    private boolean validatePaths(Path source, Path target) {
        if (!Files.exists(source)) {
            showError("Quelle existiert nicht: " + source);
            return false;
        }

        try {
            if (Files.exists(target) && !Files.isWritable(target)) {
                showError("Keine Schreibrechte im Zielverzeichnis: " + target);
                return false;
            }
        } catch (SecurityException e) {
            showError("Keine Berechtigung für Zielverzeichnis: " + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Aktualisiert die UI-Komponenten für den Start des Kopiervorgangs.
     */
    private void updateUIForCopyStart() {
        SwingUtilities.invokeLater(() -> {
            startButton.setEnabled(false);
            cancelButton.setEnabled(true);
            progressBar.setValue(0);
            appendStatus("Starte Kopiervorgang...");
        });
    }

    /**
     * Fügt eine Statusmeldung zum Status-Bereich hinzu.
     * @param message die anzuzeigende Nachricht
     */
    private void appendStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusArea.append(message + "\n");
            statusArea.setCaretPosition(statusArea.getDocument().getLength());
        });
    }

    /**
     * Zeigt eine Fehlermeldung in einem Dialogfenster an.
     * @param message die anzuzeigende Fehlermeldung
     */
    private void showError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
            message,
            "Fehler",
            JOptionPane.ERROR_MESSAGE));
    }

    /**
     * Erstellt einen neuen ExecutorService für die Ausführung von Hintergrundaufgaben.
     * @return konfigurierten ExecutorService
     */
    private ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    /**
     * Erstellt ein neues JSpinner für die Auswahl der Anzahl an Threads.
     * @return konfiguriertes JSpinner
     */
    private JSpinner createThreadsSpinner() {
        return new JSpinner(new SpinnerNumberModel(
            Runtime.getRuntime().availableProcessors() - 1, 1, MAX_THREADS, 1));
    }

    /**
     * Erstellt ein neues JProgressBar für die Anzeige des Kopierfortschritts.
     * @return konfiguriertes JProgressBar
     */
    private JProgressBar createProgressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        return bar;
    }

    /**
     * Erstellt ein neues JCheckBox mit dem angegebenen Text und dem Standardstatus.
     * @param text der anzuzeigende Text
     * @param selected ob die Checkbox standardmäßig ausgewählt sein soll
     * @return konfiguriertes JCheckBox
     */
    private JCheckBox createCheckBox(String text, boolean selected) {
        return new JCheckBox(text, selected);
    }

    /**
     * Öffnet einen Dateiauswahldialog für die Quelldatei/Verzeichnis.
     */
    private void selectSource() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Quelle auswählen");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(false);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            sourceField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /**
     * Öffnet einen Dateiauswahldialog für das Zielverzeichnis.
     */
    private void selectTarget() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Ziel auswählen");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            targetField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /**
     * Erstellt die Statusanzeige für Logmeldungen.
     * @return konfiguriertes JTextArea
     */
    private JTextArea createStatusArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setRows(10);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setMargin(new Insets(5, 5, 5, 5));
        return area;
    }

    /**
     * Fügt ein Label und ein Textfeld zu einem Panel hinzu.
     * @param panel das hinzuzufügende Panel
     * @param gbc das GridBagConstraints-Objekt für die Layout-Konfiguration
     * @param labelText der anzuzeigende Text des Labels
     * @param textField das hinzuzufügende Textfeld
     * @param component zusätzliche Komponente (z.B. Button), die hinzugefügt werden soll
     */
    private void addLabelAndField(JPanel panel, GridBagConstraints gbc, String labelText,
                                   JTextField textField, Component component) {
        gbc.gridx = 0;
        gbc.gridy++;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        panel.add(textField, gbc);

        gbc.gridx = 2;
        panel.add(component, gbc);
    }

    /**
     * Erstellt das Optionspanel mit den verfügbaren Einstellungen für den Kopiervorgang.
     * @return JPanel mit den Optionskomponenten
     */
    private JPanel createOptionsPanel() {
        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Threads
        optionsPanel.add(new JLabel("Threads:"));
        optionsPanel.add(threadsSpinner);

        // Checkboxes
        optionsPanel.add(followSymlinksBox);
        optionsPanel.add(preservePermissionsBox);

        return optionsPanel;
    }

    /**
     * Registriert den WindowListener für das Fenster.
     * Beendet den ExecutorService beim Schließen des Fensters.
     */
    private void setupWindowListener() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                executor.shutdownNow();
            }
        });
    }

    /**
     * Hauptmethode zum Starten der Anwendung.
     * Setzt das Look and Feel und zeigt das Hauptfenster an.
     */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            LOG.warn("Konnte System Look & Feel nicht setzen", e);
        }

        SwingUtilities.invokeLater(() -> {
            FileCopyFrame frame = new FileCopyFrame();
            frame.setVisible(true);
        });
    }

    /**
     * Reicht die Kopieraufgabe zur Ausführung ein.
     * @param sourcePath Quellpfad
     * @param targetPath Zielpfad
     * @param threads Anzahl der zu verwendenden Threads
     */
    private void submitCopyTask(Path sourcePath, Path targetPath, int threads) {
        executor.submit(() -> {
            try {
                FileCopyOptions options = FileCopyOptions.builder()
                    .followSymlinks(followSymlinksBox.isSelected())
                    .preservePosixPermissions(preservePermissionsBox.isSelected())
                    .progressListener((src, tgt, copied, total) -> {
                        if (isCancelled.get()) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (total > 0) {
                            int percentage = (int) ((copied * 100) / total);
                            SwingUtilities.invokeLater(() -> progressBar.setValue(percentage));
                        }
                    })
                    .build();

                FileCopy.copy(sourcePath, targetPath, threads, options);

                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Kopiervorgang erfolgreich abgeschlossen",
                    "Erfolg",
                    JOptionPane.INFORMATION_MESSAGE));
                resetUI();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.error("Kopiervorgang wurde unterbrochen", ie);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Kopiervorgang wurde unterbrochen",
                    "Abgebrochen",
                    JOptionPane.WARNING_MESSAGE));
                resetUI();
            } catch (Exception e) {
                LOG.error("Kopierfehler", e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Fehler beim Kopieren: " + e.getMessage(),
                    "Fehler",
                    JOptionPane.ERROR_MESSAGE));
                resetUI();
            }
        });
    }

    /**
     * Setzt die UI-Komponenten zurück.
     * Aktiviert die Start-Schaltfläche, deaktiviert die Abbrechen-Schaltfläche und setzt den Fortschrittsbalken zurück.
     */
    private void resetUI() {
        SwingUtilities.invokeLater(() -> {
            startButton.setEnabled(true);
            cancelButton.setEnabled(false);
            progressBar.setValue(0);
            statusArea.setText("");
        });
    }

    /**
     * Bricht den aktuellen Kopiervorgang ab, falls er läuft.
     */
    private void cancelCopy() {
        isCancelled.set(true);
        cancelButton.setEnabled(false);
    }
}
