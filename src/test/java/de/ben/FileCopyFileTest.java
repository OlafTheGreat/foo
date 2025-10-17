package de.ben;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

 class FileCopyFileTest {

    @Test
    void copiesSingleSmallFile(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("a.txt");
        Files.writeString(source, "hello small file");

        Path target = tempDir.resolve("b.txt");

        // use single thread to exercise the atomic single-file path
        FileCopy.copy(source, target, 1);

        assertTrue(Files.exists(target));
        assertEquals("hello small file", Files.readString(target));
    }

    @Test
    void copiesLargeFileInParallel(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("large.bin");
        long size = 2L * 1024L * 1024L; // 2 MB

        // write predictable content: repeated pattern
        byte[] pattern = new byte[1024];
        for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i & 0xFF);

        try (var out = Files.newOutputStream(source)) {
            long written = 0;
            while (written < size) {
                int toWrite = (int) Math.min(pattern.length, size - written);
                out.write(pattern, 0, toWrite);
                written += toWrite;
            }
        }

        // set a custom last-modified time to check preservation
        FileTime t = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(source, t);

        Path target = tempDir.resolve("large-copy.bin");

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        FileCopy.copy(source, target, threads);

        assertTrue(Files.exists(target));
        assertEquals(size, Files.size(target));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target));

        // check last modified was preserved (or close enough)
        assertEquals(Files.getLastModifiedTime(source), Files.getLastModifiedTime(target));
    }
}

