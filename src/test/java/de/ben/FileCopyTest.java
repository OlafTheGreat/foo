package de.ben;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileCopyTest {

    @Test
    void copiesDirectoryRecursively(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("subdir"));

        Path file1 = source.resolve("a.txt");
        Files.writeString(file1, "hello world");

        Path file2 = source.resolve("subdir").resolve("b.bin");
        Files.write(file2, new byte[]{0, 1, 2, 3});

        // empty directory
        Files.createDirectories(source.resolve("emptydir"));

        Path target = tempDir.resolve("target");

        // run copy with 2 threads
        FileCopy.copyDirectory(source, target, 2);

        // Assertions
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.exists(target.resolve("a.txt")));
        assertEquals("hello world", Files.readString(target.resolve("a.txt")));
        assertTrue(Files.exists(target.resolve("subdir").resolve("b.bin")));
        assertArrayEquals(new byte[]{0,1,2,3}, Files.readAllBytes(target.resolve("subdir").resolve("b.bin")));
        assertTrue(Files.exists(target.resolve("emptydir")) && Files.isDirectory(target.resolve("emptydir")));
    }
}

