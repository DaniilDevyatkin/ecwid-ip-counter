package ipcounter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Demonstration entry point.
 *
 * <pre>
 *   java -Xmx2g -cp build ipcounter.Main &lt;file&gt;               # count a real file
 *   java -Xmx2g -cp build ipcounter.Main --demo &lt;dir&gt;        # generate fixtures and compare
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: Main <file> | Main --demo <dir>");
            System.exit(2);
        }
        if ("--demo".equals(args[0])) {
            demo(Path.of(args.length > 1 ? args[1] : "."));
            return;
        }
        run(Path.of(args[0]));
    }

    static void run(Path file) throws IOException {
        long started = System.nanoTime();
        try (IpCounter counter = IpCounter.open(file).scan()) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            long bytes = Files.size(file);
            System.out.printf("file             %s%n", file);
            System.out.printf("bytes            %,d%n", bytes);
            System.out.printf("lines            %,d (bad %,d)%n", counter.lines(), counter.badLines());
            System.out.printf("distinct (exact) %,d%n", counter.distinct());
            System.out.printf("HLL estimate     %,d%n", counter.cardinalityEstimate());
            System.out.printf("bitmap           %,d bytes%n", counter.bitmapBytes());
            System.out.printf("time             %,d ms (%,.1f MB/s)%n",
                    elapsedMs, bytes / 1_048_576.0 / Math.max(elapsedMs / 1000.0, 1e-9));
        }
    }

    /**
     * Builds fixtures of growing size, counts them with both the bitmap and a
     * {@link java.util.HashSet} baseline, and reports time and memory of each.
     */
    static void demo(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (int millions : new int[] {1, 4, 16}) {
            Path file = Fixture.generate(dir, millions);
            System.out.printf("%n=== %,d M lines ===%n", millions);
            run(file);
            long started = System.nanoTime();
            long baseline = HashSetBaseline.count(file);
            long baselineMs = (System.nanoTime() - started) / 1_000_000;
            System.out.printf("HashSet baseline %,d (%d ms)%n", baseline, baselineMs);
            Files.deleteIfExists(file); // keep the disk tidy in CI
        }
    }
}