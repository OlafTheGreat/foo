package de.ben;

import java.nio.file.Path;

/**
 * Options for FileCopy behaviour.
 */
public final class FileCopyOptions {
    public final boolean followSymlinks; // when true, copy the content of symlinks; when false, replicate symlinks
    public final boolean preservePosixPermissions; // attempt to preserve POSIX file permissions when available
    public final ProgressListener progressListener; // optional listener for progress updates

    private FileCopyOptions(boolean followSymlinks, boolean preservePosixPermissions, ProgressListener progressListener) {
        this.followSymlinks = followSymlinks;
        this.preservePosixPermissions = preservePosixPermissions;
        this.progressListener = progressListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FileCopyOptions defaults() {
        return new Builder().build();
    }

    public interface ProgressListener {
        /**
         * Called with incremental progress for a file copy.
         * @param source source path
         * @param target target path
         * @param bytesCopied bytes copied so far for this file
         * @param totalBytes total size of the file, or -1 when unknown
         */
        void onProgress(Path source, Path target, long bytesCopied, long totalBytes);
    }

    public static final class Builder {
        private boolean followSymlinks = true;
        private boolean preservePosixPermissions = false;
        private ProgressListener progressListener = null;

        public Builder followSymlinks(boolean v) { this.followSymlinks = v; return this; }
        public Builder preservePosixPermissions(boolean v) { this.preservePosixPermissions = v; return this; }
        public Builder progressListener(ProgressListener l) { this.progressListener = l; return this; }
        public FileCopyOptions build() {
            return new FileCopyOptions(followSymlinks, preservePosixPermissions, progressListener);
        }
    }
}
