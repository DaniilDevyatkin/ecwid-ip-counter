package ipcounter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** The naive solution from the assignment, kept as a correctness baseline. */
final class HashSetBaseline {

    private HashSetBaseline() {
    }

    static long count(Path file) throws IOException {
        Set<String> seen = new HashSet<>();
        try (var lines = Files.lines(file)) {
            lines.forEach(seen::add);
        }
        return seen.size();
    }
}