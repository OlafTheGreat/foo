package de.ben;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Robust, production-ready directory copier with focus on stability and security.
 */
public final class FileCopy {

    private static final Logger LOG = LoggerFactory.getLogger(FileCopy.class);

    private FileCopy() {
        // utility class
    }

    /**
     * Copy a directory recursively from source to target using a thread pool.
     */
    public static void copyDirectory(Path source, Path target, int threads) throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        if (threads <= 0) throw new IllegalArgumentException("threads must be >= 1");

        // Normalize once and bind to final locals so inner classes/lambdas can reference them
        final Path src = source.toAbsolutePath().normalize();
        final Path tgt = target.toAbsolutePath().normalize();

        if (!Files.exists(src)) throw new IOException("Source does not exist: " + src);
        if (!Files.isDirectory(src)) throw new IOException("Source is not a directory: " + src);

        // Ensure target exists
        if (!Files.exists(tgt)) {
            Files.createDirectories(tgt);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        try {
            // Schedule copy tasks
            scheduleCopyTasks(src, tgt, executor, futures);

            // Prevent more submissions
            executor.shutdown();

            // Await task completion and handle exceptions
            awaitFutures(futures, executor);

        } catch (IOException ioe) {
            LOG.error("Copy failed, aborting from {} to {}", src, tgt, ioe);
            executor.shutdownNow();
            throw new IOException("Copy failed from " + src + " to " + tgt, ioe);
        } catch (RuntimeException re) {
            LOG.error("Copy failed with runtime exception from {} to {}", src, tgt, re);
            executor.shutdownNow();
            throw new CopyFailedException("Copy failed from " + src + " to " + tgt, re);
        } finally {
            if (!executor.isShutdown()) executor.shutdownNow();
        }
    }

    private static void scheduleCopyTasks(final Path src, final Path tgt, ExecutorService executor, List<Future<Void>> futures) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = src.relativize(dir);
                Path targetDir = tgt.resolve(relative).normalize();
                if (!targetDir.startsWith(tgt)) {
                    throw new IOException("Attempt to write outside target directory: " + targetDir);
                }
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = src.relativize(file);
                Path targetFile = tgt.resolve(relative).normalize();

                if (!targetFile.startsWith(tgt)) {
                    throw new IOException("Attempt to write outside target directory: " + targetFile);
                }

                // Submit a callable that copies the file
                futures.add(executor.submit(() -> {
                    copyFileAtomic(file, targetFile);
                    return null;
                }));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void awaitFutures(List<Future<Void>> futures, ExecutorService executor) throws IOException, InterruptedException {
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                // Cancel remaining tasks and surface the cause
                executor.shutdownNow();
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                } else if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                } else {
                    throw new IOException("Unexpected exception during file copy", cause);
                }
            }
        }

        // Ensure termination
        boolean terminated = executor.awaitTermination(5, TimeUnit.MINUTES);
        if (!terminated) {
            executor.shutdownNow();
            throw new IOException("Timed out waiting for file copy tasks to finish");
        }
    }

    private static void copyFileAtomic(Path sourceFile, Path targetFile) throws IOException {
        Objects.requireNonNull(sourceFile);
        Objects.requireNonNull(targetFile);

        // Ensure parent exists
        Path parent = targetFile.getParent();
        if (parent == null) {
            throw new IOException("Target file has no parent: " + targetFile);
        }
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Use system temp directory to avoid FS-specific limitations when creating a temp file inside the target directory.
        Path temp = Files.createTempFile("filecopy-", ".tmp");
        boolean tempExists = true;
        try {
            // Write source to temp
            writeSourceToTemp(sourceFile, temp);

            // Move or copy temp to target; method handles fallbacks and cleanup
            moveOrCopyTempToTarget(temp, sourceFile, targetFile);

            // If we reach here, temp has been moved or deleted
            tempExists = false;

        } finally {
            if (tempExists) {
                safeDeleteIfExists(temp);
            }
        }
    }

    private static void moveOrCopyTempToTarget(Path temp, Path sourceFile, Path targetFile) throws IOException {
        // Try an atomic move from temp -> target. If not supported, fall back to regular move or copy.
        try {
            Files.move(temp, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (UnsupportedOperationException uoe) {
            LOG.debug("ATOMIC_MOVE not supported; falling back to non-atomic move: {}", uoe.toString());
        } catch (IOException ioe) {
            LOG.debug("Atomic move failed, will try non-atomic move or copy: {}", ioe.getMessage());
        }

        // Try non-atomic move
        try {
            Files.move(temp, targetFile, StandardCopyOption.REPLACE_EXISTING);
            tryPreserveLastModifiedTime(sourceFile, targetFile);
            return;
        } catch (IOException moveEx) {
            LOG.debug("Move from temp to target failed: {}", moveEx.getMessage());
            // Fall through to final copy strategy
        }

        // Final fallback: copy source directly to target and then delete temp
        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        safeDeleteIfExists(temp);
    }

    private static void tryPreserveLastModifiedTime(Path source, Path target) {
        try {
            Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
        } catch (IOException ignored) {
            LOG.debug("Could not preserve last modified time for {}", target);
        }
    }

    private static void safeDeleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException delEx) {
            LOG.debug("Failed to delete temp file {}: {}", path, delEx.getMessage());
        }
    }

    private static void writeSourceToTemp(Path sourceFile, Path temp) throws IOException {
        try (InputStream in = Files.newInputStream(sourceFile);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
    }
}
