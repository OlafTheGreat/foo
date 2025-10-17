package de.ben;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small command-line wrapper for the FileCopy utility.
 * Usage: java -jar foo.jar <sourceDir> <targetDir> [threads]
 * If threads is omitted the application uses Runtime.availableProcessors().
 */
public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            LOG.error("Usage: java -jar foo.jar <sourceDir> <targetDir> [threads]");
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
            FileCopy.copyDirectory(source, target, threads);
            LOG.info("Copy finished successfully.");
        } catch (InterruptedException ie) {
            // Restore interrupt status and exit
            Thread.currentThread().interrupt();
            LOG.error("Copy interrupted", ie);
            System.exit(1);
        } catch (IOException e) {
            LOG.error("Copy failed", e);
            System.exit(1);
        }
    }
}
