package ipcounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IpCounterTest {

    @TempDir
    Path dir;

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void countsDistinctFromAssignmentSample() throws IOException {
        Path file = write(dir, "sample.txt",
                "145.67.23.4\n8.34.5.23\n89.54.3.124\n89.54.3.124\n3.45.71.5\n");
        try (IpCounter counter = IpCounter.open(file).scan()) {
            assertEquals(4, counter.distinct());
            assertEquals(5, counter.lines());
        }
    }

    @Test
    void handlesCrlfAndMissingTrailingNewline() throws IOException {
        Path file = write(dir, "crlf.txt", "1.2.3.4\r\n1.2.3.4\r\n5.6.7.8");
        try (IpCounter counter = IpCounter.open(file).scan()) {
            assertEquals(2, counter.distinct());
        }
    }

    @Test
    void emptyFileCountsZero() throws IOException {
        Path file = write(dir, "empty.txt", "");
        try (IpCounter counter = IpCounter.open(file).scan()) {
            assertEquals(0, counter.distinct());
            assertEquals(0, counter.lines());
        }
    }

    @Test
    void skipsGarbageInsteadOfCrashing() throws IOException {
        Path file = write(dir, "junk.txt",
                "10.0.0.1\nnot-an-ip\n256.1.1.1\n1.2.3\n::1\n10.0.0.1\n");
        try (IpCounter counter = IpCounter.open(file).scan()) {
            assertEquals(1, counter.distinct());
            assertEquals(4, counter.badLines());
        }
    }

    @Test
    void countsAddressSpaceEdges() throws IOException {
        Path file = write(dir, "edges.txt",
                "0.0.0.0\n255.255.255.255\n0.0.0.1\n255.255.255.254\n0.0.0.0\n");
        try (IpCounter counter = IpCounter.open(file).scan()) {
            assertEquals(4, counter.distinct());
        }
    }

    @Test
    void matchesHashSetOnRandomDataAcrossFileSizes() throws IOException {
        RandomGenerator random = RandomGenerator.getDefault();
        for (int size : new int[] {1, 999, 50_000}) {
            StringBuilder text = new StringBuilder();
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < size; i++) {
                String line = random.nextInt(256) + "." + random.nextInt(256) + "."
                        + random.nextInt(256) + "." + random.nextInt(256);
                text.append(line).append('\n');
                expected.add(line);
            }
            Path file = write(dir, "random_" + size + ".txt", text.toString());
            try (IpCounter counter = IpCounter.open(file).scan()) {
                assertEquals(expected.size(), counter.distinct(), "size " + size);
                long estimate = counter.cardinalityEstimate();
                double error = Math.abs(estimate - expected.size())
                        / (double) Math.max(expected.size(), 1);
                assertTrue(error < 0.05, "HLL error too big for " + size + ": " + estimate);
            }
        }
    }

    @Test
    void bitmapIs512MiB() throws IOException {
        Path file = write(dir, "one.txt", "1.1.1.1\n");
        try (IpCounter counter = IpCounter.open(file).scan()) {
            assertEquals(512L * 1024 * 1024, counter.mappedBytes());
            assertEquals(512L * 1024 * 1024, counter.bitmapBytes());
        }
    }
}