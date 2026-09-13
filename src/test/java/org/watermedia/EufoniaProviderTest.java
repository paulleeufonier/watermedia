package org.watermedia.api.player;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EufoniaProviderTest {
    @Test
    void supportsTheEufoniaVlcLayout() throws IOException {
        final Path directory = Files.createTempDirectory("eufonia-vlc");
        try {
            Files.createFile(directory.resolve("libvlc.so.5"));
            Files.createFile(directory.resolve("libvlccore.so.9"));
            Files.createDirectories(directory.resolve("vlc/plugins"));

            assertTrue(new EufoniaProvider(directory.toFile()).supported());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void rejectsAPathWithoutVlcLibraries() throws IOException {
        final Path directory = Files.createTempDirectory("eufonia-vlc");
        try {
            Files.createDirectories(directory.resolve("vlc/plugins"));

            assertFalse(new EufoniaProvider(directory.toFile()).supported());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void rejectsAPathWithoutThePluginDirectory() throws IOException {
        final Path directory = Files.createTempDirectory("eufonia-vlc");
        try {
            Files.createFile(directory.resolve("libvlc.so.5"));
            Files.createFile(directory.resolve("libvlccore.so.9"));

            assertFalse(new EufoniaProvider(directory.toFile()).supported());
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
