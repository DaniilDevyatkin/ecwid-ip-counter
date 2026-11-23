package ipcounter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.random.RandomGenerator;

/** Generates reproducible IPv4 fixtures (duplicates included on purpose). */
final class Fixture {

    private Fixture() {
    }

    /**
     * Writes {@code millions * 1_000_000} lines drawn from a pool of
     * {@code lines / 2} distinct addresses, so roughly half the lines are repeats.
     */
    static Path generate(Path dir, int millions) throws IOException {
        long lines = (long) millions * 1_000_000L;
        Path file = dir.resolve("ip_" + millions + "M.txt");
        RandomGenerator random = RandomGenerator.getDefault();
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            for (long i = 0; i < lines; i++) {
                long address = random.nextLong(lines / 2);
                out.write(Integer.toString((int) (address >>> 24) & 0xFF));
                out.write('.');
                out.write(Integer.toString((int) (address >>> 16) & 0xFF));
                out.write('.');
                out.write(Integer.toString((int) (address >>> 8) & 0xFF));
                out.write('.');
                out.write(Integer.toString((int) address & 0xFF));
                out.write('\n');
            }
        }
        return file;
    }
}