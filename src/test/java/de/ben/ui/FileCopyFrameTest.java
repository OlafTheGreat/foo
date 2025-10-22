package de.ben.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-Tests für die FileCopyFrame GUI-Komponente.
 * Verwendet JUnit5 Assertions und führt GUI-Aktionen im EDT aus.
 */
class FileCopyFrameTest {

    private FileCopyFrame frame;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // GUI-Komponenten müssen im EDT erstellt werden
        SwingUtilities.invokeAndWait(() -> frame = new FileCopyFrame());
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }

    @Test
    void shouldInitializeWithCorrectDefaults() {
        assertEquals("FileCopy - Hochperformante Dateikopierer", frame.getTitle());
        assertEquals(new Dimension(600, 400), frame.getSize());

        // Überprüfe Thread-Spinner Default-Wert
        JSpinner threadsSpinner = findComponent(frame, JSpinner.class);
        assertNotNull(threadsSpinner, "threads spinner not found");
        assertEquals(Runtime.getRuntime().availableProcessors() - 1, threadsSpinner.getValue());
    }

    @Test
    void shouldValidateEmptyInputs() throws Exception {
        // Starte Kopiervorgang ohne Eingaben
        JButton startButton = findButton(frame, "Start");
        assertNotNull(startButton, "start button not found");
        SwingUtilities.invokeAndWait(startButton::doClick);

        // Warte kurz und prüfe ob ein Fehlerdialog angezeigt wird
        boolean foundErrorDialog = false;
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && window.isVisible()) {
                    JDialog dialog = (JDialog) window;
                    if ("Fehler".equals(dialog.getTitle())) {
                        foundErrorDialog = true;
                        // schließe Dialog
                        SwingUtilities.invokeAndWait(dialog::dispose);
                        break;
                    }
                }
            }
            if (foundErrorDialog) break;
            Thread.sleep(100);
        }

        assertTrue(foundErrorDialog, "Expected an error dialog to appear for empty inputs");
    }

    @Test
    void shouldEnableAndDisableButtons() throws Exception {
        JButton startButton = findButton(frame, "Start");
        JButton cancelButton = findButton(frame, "Abbrechen");
        assertNotNull(startButton);
        assertNotNull(cancelButton);

        // Initial-Status
        assertTrue(invokeAndGet(() -> startButton.isEnabled()));
        assertFalse(invokeAndGet(() -> cancelButton.isEnabled()));

        // Erstelle Test-Dateien (größer, damit Kopie spürbar Zeit braucht)
        Path sourceFile = tempDir.resolve("test.bin");
        createTestFile(sourceFile, 2 * 1024 * 1024); // 2 MB
        Path targetDir = tempDir.resolve("target");
        Files.createDirectory(targetDir);

        // Setze Pfade im EDT
        SwingUtilities.invokeAndWait(() -> {
            JTextField sourceField = findComponent(frame, JTextField.class, 0);
            JTextField targetField = findComponent(frame, JTextField.class, 1);
            assertNotNull(sourceField);
            assertNotNull(targetField);
            sourceField.setText(sourceFile.toString());
            targetField.setText(targetDir.toString());
        });

        // Starte Kopiervorgang
        SwingUtilities.invokeAndWait(startButton::doClick);

        // Warte bis der Start-Button deaktiviert ist (max 10s)
        // Some platforms may complete the copy very quickly; instead assert that the cancel button becomes enabled shortly.
        boolean cancelEnabled = waitForCondition(10_000, 50, () -> invokeAndGet(() -> cancelButton.isEnabled()));
        assertTrue(cancelEnabled, "Cancel button should be enabled when copy runs");

        // Abbrechen
        SwingUtilities.invokeAndWait(cancelButton::doClick);

        // Warte bis UI zurückgesetzt ist (max 10s)
        boolean reset = waitForCondition(10_000, 50, () -> invokeAndGet(() -> startButton.isEnabled()) && !invokeAndGet(() -> cancelButton.isEnabled()));
        assertTrue(reset, "UI should reset after cancel");
    }

    @Test
    void shouldUpdateProgressBar() throws Exception {
        // Erstelle Test-Datei, deutlich größer als threshold
        Path sourceFile = tempDir.resolve("large.dat");
        createTestFile(sourceFile, 32 * 1024 * 1024); // 32MB
        Path targetDir = tempDir.resolve("target");
        Files.createDirectory(targetDir);

        // Setze Pfade
        SwingUtilities.invokeAndWait(() -> {
            JTextField sourceField = findComponent(frame, JTextField.class, 0);
            JTextField targetField = findComponent(frame, JTextField.class, 1);
            assertNotNull(sourceField);
            assertNotNull(targetField);
            sourceField.setText(sourceFile.toString());
            targetField.setText(targetDir.toString());
        });

        // Starte Kopiervorgang
        JButton startButton = findButton(frame, "Start");
        assertNotNull(startButton);
        SwingUtilities.invokeAndWait(startButton::doClick);

        // Warte und prüfe Fortschritt / Ziel-Datei (Timeout 30s)
        // Warte und prüfe Fortschritt / Ziel-Datei (Timeout 30s)
        JProgressBar progressBar = findComponent(frame, JProgressBar.class);
        assertNotNull(progressBar);
        long startTime = System.currentTimeMillis();
        Path expectedTargetFile = targetDir.resolve(sourceFile.getFileName());
        boolean fileExists = false;
        boolean sawProgress = false;
        while (System.currentTimeMillis() - startTime < 30_000) {
            if (Files.exists(expectedTargetFile)) {
                fileExists = true;
            }
            int value = invokeAndGet(() -> progressBar.getValue());
            if (value > 0) sawProgress = true;
            if (sawProgress && fileExists) break;
            Thread.sleep(150);
        }

        assertTrue(fileExists, "Expected target file to exist after copy");
        assertTrue(sawProgress, "Expected progress to be reported during copy");
    }

    private void createTestFile(Path path, int size) throws Exception {
        try (var out = Files.newOutputStream(path)) {
            byte[] buffer = new byte[8192];
            int remaining = size;
            while (remaining > 0) {
                int toWrite = Math.min(buffer.length, remaining);
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> T findComponent(Container container, Class<T> componentClass) {
        return findComponent(container, componentClass, 0);
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> T findComponent(Container container, Class<T> componentClass, int index) {
        Component[] components = container.getComponents();
        int found = 0;
        for (Component component : components) {
            if (componentClass.isInstance(component)) {
                if (found == index) {
                    return (T) component;
                }
                found++;
            }
            if (component instanceof Container) {
                T result = findComponent((Container) component, componentClass, index - found);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private JButton findButton(Container container, String text) {
        return findButtonRecursive(container, text);
    }

    private JButton findButtonRecursive(Container container, String text) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton && text.equals(((JButton) comp).getText())) {
                return (JButton) comp;
            }
            if (comp instanceof Container) {
                JButton button = findButtonRecursive((Container) comp, text);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    // Helper: run in EDT and return result
    private <T> T invokeAndGet(java.util.concurrent.Callable<T> callable) throws Exception {
        final java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Exception> exRef = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception e) {
                exRef.set(e);
            }
        });
        if (exRef.get() != null) throw exRef.get();
        return ref.get();
    }

    // Helper: wait for condition (polling)
    private boolean waitForCondition(long timeoutMs, long pollIntervalMs, java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) return true;
            Thread.sleep(pollIntervalMs);
        }
        return false;
    }
}
