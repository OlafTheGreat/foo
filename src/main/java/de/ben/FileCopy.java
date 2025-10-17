package de.ben;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Robust, production-ready directory & file copier with focus on stability and security.
 */
public final class FileCopy {

    private static final Logger LOG = LoggerFactory.getLogger(FileCopy.class);

    // Minimal size to attempt parallel copy (1 MB)
    private static final long MIN_PARALLEL_SIZE = 1L * 1024L * 1024L;
    // Buffer size used by chunk workers
    private static final int IO_BUFFER = 64 * 1024;

    private FileCopy() {
        // utility class
    }

    // Backwards-compatible convenience method
    public static void copy(Path source, Path target, int threads) throws IOException, InterruptedException {
        copy(source, target, threads, FileCopyOptions.defaults());
    }

    // New API with options
    public static void copy(Path source, Path target, int threads, FileCopyOptions options) throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (threads <= 0) throw new IllegalArgumentException("threads must be >= 1");

        Path src = source.toAbsolutePath().normalize();
        Path tgt = target.toAbsolutePath().normalize();

        if (!Files.exists(src)) throw new IOException("Source does not exist: " + src);

        if (Files.isDirectory(src)) {
            copyDirectory(src, tgt, threads, options);
            return;
        }

        if (Files.isRegularFile(src) || Files.isSymbolicLink(src)) {
            long size = Files.size(src);
            if (threads > 1 && size >= MIN_PARALLEL_SIZE) {
                copyFileParallel(src, tgt, threads, options);
            } else {
                // reuse atomic single-file copy
                copyFileAtomic(src, tgt, options);
            }
            return;
        }

        throw new IOException("Unsupported source type: " + src);
    }

    /**
     * Backwards-compatible overload for copyDirectory without options.
     */
    public static void copyDirectory(Path source, Path target, int threads) throws IOException, InterruptedException {
        copyDirectory(source, target, threads, FileCopyOptions.defaults());
    }

    /**
     * Copy directory with options
     */
    public static void copyDirectory(Path source, Path target, int threads, FileCopyOptions options) throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");

        if (threads <= 0) throw new IllegalArgumentException("threads must be >= 1");

        final Path src = source.toAbsolutePath().normalize();
        final Path tgt = target.toAbsolutePath().normalize();

        if (!Files.exists(src)) throw new IOException("Source does not exist: " + src);
        if (!Files.isDirectory(src)) throw new IOException("Source is not a directory: " + src);

        if (!Files.exists(tgt)) {
            Files.createDirectories(tgt);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        try {
            scheduleCopyTasks(src, tgt, executor, futures, options);
            executor.shutdown();
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

    private static void scheduleCopyTasks(final Path src, final Path tgt, ExecutorService executor, List<Future<Void>> futures, FileCopyOptions options) throws IOException {
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

                futures.add(executor.submit(() -> {
                    try {
                        if (Files.isSymbolicLink(file) && !options.followSymlinks) {
                            // replicate the symlink itself
                            Path linkTarget = Files.readSymbolicLink(file);
                            // ensure parent exists
                            Path p = targetFile.getParent();
                            if (p != null && !Files.exists(p)) Files.createDirectories(p);
                            Files.deleteIfExists(targetFile);
                            Files.createSymbolicLink(targetFile, linkTarget);
                        } else {
                            // follow symlink or regular file: copy content
                            long size = Files.size(file);
                            if (options.progressListener != null && size > 0 && size >= MIN_PARALLEL_SIZE && executor != null) {
                                // try parallel copy for large files
                                copyFileParallel(file, targetFile, Math.max(1, Runtime.getRuntime().availableProcessors()), options);
                            } else {
                                copyFileAtomic(file, targetFile, options);
                            }

                            // preserve POSIX permissions if requested and supported
                            if (options.preservePosixPermissions) {
                                try {
                                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                                    Files.setPosixFilePermissions(targetFile, perms);
                                } catch (UnsupportedOperationException | IOException ignored) {
                                    LOG.debug("Posix permissions not preserved for {}", targetFile);
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw e;
                    }
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

        boolean terminated = executor.awaitTermination(5, TimeUnit.MINUTES);
        if (!terminated) {
            executor.shutdownNow();
            throw new IOException("Timed out waiting for file copy tasks to finish");
        }
    }

    private static void copyFileAtomic(Path sourceFile, Path targetFile, FileCopyOptions options) throws IOException {
        Objects.requireNonNull(sourceFile);
        Objects.requireNonNull(targetFile);

        Path parent = targetFile.getParent();
        if (parent == null) {
            throw new IOException("Target file has no parent: " + targetFile);
        }
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile("filecopy-", ".tmp");
        boolean tempExists = true;
        try {
            writeSourceToTemp(sourceFile, temp, options);
            moveOrCopyTempToTarget(temp, sourceFile, targetFile);
            tempExists = false;

        } finally {
            if (tempExists) safeDeleteIfExists(temp);
        }
    }

    private static void copyFileParallel(Path source, Path target, int threads, FileCopyOptions options) throws IOException, InterruptedException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(options);
        if (threads <= 0) throw new IllegalArgumentException("threads must be >= 1");

        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Target file has no parent: " + target);
        if (!Files.exists(parent)) Files.createDirectories(parent);

        Path temp = Files.createTempFile("filecopy-", ".tmp");
        boolean tempExists = true;

        try (FileChannel srcCh = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel tgtCh = FileChannel.open(temp, StandardOpenOption.WRITE)) {

            long size = srcCh.size();
            long chunkSize = Math.max(MIN_PARALLEL_SIZE, (size + threads - 1) / threads);

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Void>> futures = new ArrayList<>();
                AtomicLong bytesCopied = new AtomicLong(0);

                for (long pos = 0; pos < size; pos += chunkSize) {
                    final long chunkStart = pos;
                    final long chunkLen = Math.min(chunkSize, size - pos);
                    futures.add(executor.submit(() -> {
                        ByteBuffer buf = ByteBuffer.allocate(IO_BUFFER);
                        long localPos = chunkStart;
                        long remaining = chunkLen;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.capacity(), remaining);
                            buf.limit(toRead);
                            int read = srcCh.read(buf, localPos);
                            if (read <= 0) break;
                            buf.flip();
                            int written = 0;
                            while (buf.hasRemaining()) {
                                written += tgtCh.write(buf, localPos + written);
                            }
                            buf.clear();
                            localPos += read;
                            remaining -= read;
                            long total = bytesCopied.addAndGet(read);
                            if (options.progressListener != null) {
                                options.progressListener.onProgress(source, target, total, size);
                            }
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IOException("Copy interrupted");
                            }
                        }
                        return null;
                    }));
                }

                executor.shutdown();

                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        executor.shutdownNow();
                        Throwable cause = e.getCause();
                        if (cause instanceof IOException) throw (IOException) cause;
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new IOException("Unexpected exception during parallel file copy", cause);
                    }
                }

                boolean terminated = executor.awaitTermination(5, TimeUnit.MINUTES);
                if (!terminated) {
                    executor.shutdownNow();
                    throw new IOException("Timed out waiting for parallel copy tasks to finish");
                }

                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    try {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException moveEx) {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        safeDeleteIfExists(temp);
                        return;
                    }
                }

                tryPreserveLastModifiedTime(source, target);
                tempExists = false;

            } finally {
                if (!executor.isShutdown()) {
                    executor.shutdownNow();
                }
            }

        } finally {
            if (tempExists) safeDeleteIfExists(temp);
        }
    }

    private static void moveOrCopyTempToTarget(Path temp, Path sourceFile, Path targetFile) throws IOException {
        try {
            Files.move(temp, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (UnsupportedOperationException uoe) {
            LOG.debug("ATOMIC_MOVE not supported; falling back to non-atomic move: {}", uoe.toString());
        } catch (IOException ioe) {
            LOG.debug("Atomic move failed, will try non-atomic move or copy: {}", ioe.getMessage());
        }

        try {
            Files.move(temp, targetFile, StandardCopyOption.REPLACE_EXISTING);
            tryPreserveLastModifiedTime(sourceFile, targetFile);
            return;
        } catch (IOException moveEx) {
            LOG.debug("Move from temp to target failed: {}", moveEx.getMessage());
        }

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

    private static void writeSourceToTemp(Path sourceFile, Path temp, FileCopyOptions options) throws IOException {
        try (InputStream in = Files.newInputStream(sourceFile);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buf = new byte[16 * 1024];
            int r;
            long total = 0L;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                total += r;
                if (options != null && options.progressListener != null) {
                    options.progressListener.onProgress(sourceFile, temp, total, Files.size(sourceFile));
                }
            }
        }
    }
}
