package de.ben;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Konfigurationsoptionen für den FileCopy-Prozess.
 * Diese Klasse verwendet das Builder-Pattern für eine flexible und typsichere Konfiguration
 * aller Kopieroptionen. Alle Instanzen sind immutable und thread-safe.
 *
 * <p>Beispiel-Verwendung:</p>
 * <pre>{@code
 * FileCopyOptions options = FileCopyOptions.builder()
 *     .followSymlinks(true)
 *     .preservePosixPermissions(true)
 *     .maxRetries(3)
 *     .retryDelayMs(1000)
 *     .build();
 * }</pre>
 *
 * @author Ben
 * @version 2.0
 * @since 1.0
 */
public final class FileCopyOptions {
    /** Maximale Anzahl Wiederholungsversuche bei IO-Fehlern */
    public final int maxRetries;

    /** Wartezeit zwischen Wiederholungsversuchen in Millisekunden */
    public final long retryDelayMs;

    /** Anzahl der Dateien pro Batch für kleine Dateien */
    public final int smallFileBatchSize;

    /** Maximalgröße für "kleine" Dateien in Bytes */
    public final long smallFileThreshold;

    /** Wenn true, wird der Inhalt von Symlinks kopiert; wenn false, werden Symlinks repliziert */
    public final boolean followSymlinks;

    /** Wenn true, werden POSIX-Dateiberechtigungen beibehalten (falls verfügbar) */
    public final boolean preservePosixPermissions;

    /** Optionaler Listener für Fortschrittsaktualisierungen */
    public final ProgressListener progressListener;

    /**
     * Erstellt eine neue Instanz mit den angegebenen Optionen.
     * Verwende stattdessen {@link #builder()} für eine einfachere Konfiguration.
     *
     * @param builder Der Builder mit den konfigurierten Optionen
     */
    private FileCopyOptions(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.retryDelayMs = builder.retryDelayMs;
        this.smallFileBatchSize = builder.smallFileBatchSize;
        this.smallFileThreshold = builder.smallFileThreshold;
        this.followSymlinks = builder.followSymlinks;
        this.preservePosixPermissions = builder.preservePosixPermissions;
        this.progressListener = builder.progressListener;
    }

    /**
     * Erstellt einen neuen Builder für FileCopyOptions.
     * @return ein neuer Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Erstellt eine Instanz mit Standardeinstellungen.
     * @return FileCopyOptions mit Standardwerten
     */
    public static FileCopyOptions defaults() {
        return new Builder().build();
    }

    /**
     * Interface für Fortschrittsbenachrichtigungen während des Kopiervorgangs.
     */
    @FunctionalInterface
    public interface ProgressListener {
        /**
         * Wird aufgerufen, wenn neue Fortschrittsinformationen verfügbar sind.
         *
         * @param source Quellpfad
         * @param target Zielpfad
         * @param bytesCopied bisher kopierte Bytes
         * @param totalBytes Gesamtgröße der Datei, oder -1 wenn unbekannt
         */
        void onProgress(Path source, Path target, long bytesCopied, long totalBytes);
    }

    /**
     * Builder für FileCopyOptions.
     * Ermöglicht die schrittweise Konfiguration aller Optionen mit Überprüfung der Gültigkeit.
     */
    public static final class Builder {
        private int maxRetries = 3;
        private long retryDelayMs = 1000;
        private int smallFileBatchSize = 100;
        private long smallFileThreshold = 1024 * 1024; // 1MB
        private boolean followSymlinks = true;
        private boolean preservePosixPermissions = false;
        private ProgressListener progressListener = null;

        private Builder() {
            // private constructor to enforce builder pattern
        }

        /**
         * Setzt die maximale Anzahl an Wiederholungsversuchen bei IO-Fehlern.
         * @param value Anzahl der Versuche (≥ 0)
         * @return this Builder für Method Chaining
         * @throws IllegalArgumentException wenn value < 0
         */
        public Builder maxRetries(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("maxRetries muss ≥ 0 sein");
            }
            this.maxRetries = value;
            return this;
        }

        /**
         * Setzt die Wartezeit zwischen Wiederholungsversuchen.
         * @param value Wartezeit in Millisekunden (> 0)
         * @return this Builder für Method Chaining
         * @throws IllegalArgumentException wenn value ≤ 0
         */
        public Builder retryDelayMs(long value) {
            if (value <= 0) {
                throw new IllegalArgumentException("retryDelayMs muss > 0 sein");
            }
            this.retryDelayMs = value;
            return this;
        }

        /**
         * Setzt die Wartezeit zwischen Wiederholungsversuchen als Duration.
         * @param duration Wartezeit als Duration (nicht null)
         * @return this Builder für Method Chaining
         * @throws NullPointerException wenn duration null ist
         * @throws IllegalArgumentException wenn duration negativ oder zu groß ist
         */
        public Builder retryDelay(Duration duration) {
            Objects.requireNonNull(duration, "duration darf nicht null sein");
            long ms = duration.toMillis();
            if (ms <= 0) {
                throw new IllegalArgumentException("duration muss positiv sein");
            }
            this.retryDelayMs = ms;
            return this;
        }

        /**
         * Setzt die Anzahl der Dateien pro Batch für kleine Dateien.
         * @param value Anzahl der Dateien (> 0)
         * @return this Builder für Method Chaining
         * @throws IllegalArgumentException wenn value ≤ 0
         */
        public Builder smallFileBatchSize(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("smallFileBatchSize muss > 0 sein");
            }
            this.smallFileBatchSize = value;
            return this;
        }

        /**
         * Setzt die maximale Größe für "kleine" Dateien.
         * @param bytes Maximalgröße in Bytes (> 0)
         * @return this Builder für Method Chaining
         * @throws IllegalArgumentException wenn bytes ≤ 0
         */
        public Builder smallFileThreshold(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("smallFileThreshold muss > 0 sein");
            }
            this.smallFileThreshold = bytes;
            return this;
        }

        /**
         * Konfiguriert das Verhalten beim Kopieren von Symlinks.
         * @param value true um Symlinks zu folgen, false um sie zu replizieren
         * @return this Builder für Method Chaining
         */
        public Builder followSymlinks(boolean value) {
            this.followSymlinks = value;
            return this;
        }

        /**
         * Konfiguriert die Beibehaltung von POSIX-Berechtigungen.
         * @param value true um Berechtigungen beizubehalten
         * @return this Builder für Method Chaining
         */
        public Builder preservePosixPermissions(boolean value) {
            this.preservePosixPermissions = value;
            return this;
        }

        /**
         * Setzt den Fortschritts-Listener.
         * @param listener der zu verwendende ProgressListener, oder null
         * @return this Builder für Method Chaining
         */
        public Builder progressListener(ProgressListener listener) {
            this.progressListener = listener;
            return this;
        }

        /**
         * Erstellt eine neue FileCopyOptions-Instanz mit den konfigurierten Werten.
         * @return neue FileCopyOptions-Instanz
         */
        public FileCopyOptions build() {
            return new FileCopyOptions(this);
        }
    }
}
