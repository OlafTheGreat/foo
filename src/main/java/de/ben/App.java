package de.ben;

import de.ben.ui.FileCopyFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Small command-line wrapper for the FileCopy utility.
 * Usage: java -jar foo.jar <sourcePath> <targetPath> [threads]
 * If threads is omitted the application uses Runtime.availableProcessors().
 */
public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            startGUI();
            return;
        }

        handleCLI(args);
    }

    private static void startGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            LOG.warn("Konnte System Look & Feel nicht setzen", e);
        }

        SwingUtilities.invokeLater(() -> {
            FileCopyFrame frame = new FileCopyFrame();
            frame.setVisible(true);
        });
    }

    private static void handleCLI(String[] args) {
        if (args.length < 2) {
            LOG.error("Usage: java -jar foo.jar <sourcePath> <targetPath> [threads]");
            System.exit(2);
        }

        Path source = Paths.get(args[0]);
        Path target = Paths.get(args[1]);
        int threads = Runtime.getRuntime().availableProcessors();

        if (args.length >= 3) {
            try {
                threads = Integer.parseInt(args[2]);
                if (threads <= 0) throw new NumberFormatException("threads must be > 0");
            } catch (NumberFormatException e) {
                LOG.error("Invalid threads value: {}", args[2]);
                System.exit(3);
            }
        }

        try {
            if (!Files.exists(source)) {
                LOG.error("Source does not exist: {}", source);
                System.exit(4);
            }

            FileCopyOptions options = FileCopyOptions.builder()
                    .progressListener((src, tgt, copied, total) -> {
                        if (total > 0) {
                            int percentage = (int) ((copied * 100) / total);
                            System.out.printf("\rKopiere: %d%%", percentage);
                        }
                    })
                    .build();

            FileCopy.copy(source, target, threads, options);
            System.out.println("\nKopiervorgang erfolgreich abgeschlossen.");

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.error("Copy interrupted", ie);
            System.exit(1);
        } catch (Exception e) {
            LOG.error("Copy failed", e);
            System.exit(1);
        }
    }
}
