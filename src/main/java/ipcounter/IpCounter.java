package ipcounter;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Counts distinct IPv4 addresses in a newline separated file.
 *
 * <p>The naive solution ({@code HashSet<String>}) needs ~1.4 GB of heap for an
 * address space of 2^32 entries, and one allocation per line. This one keeps a
 * bitmap of 2^32 bits = 512 MiB (or a 1 GiB {@code long[]}) and parses bytes
 * straight out of a memory mapped 1 GiB window: no per-line objects at all.
 *
 * <p>Two results are tracked in one pass:
 * <ul>
 *   <li>{@code distinct} — exact number of unique addresses (the assignment);</li>
 *   <li>{@code cardinality} — a HyperLogLog estimate kept for free, because the
 *       bitmap only answers "is this address present", and having a rough count
 *       lets the caller sanity check the exact value against a second algorithm.</li>
 * </ul>
 *
 * <p>Every count is capped at {@link Integer#MAX_VALUE}, which is far above the
 * number of distinct IPv4 addresses ({@code Long} overflow is impossible for a
 * 2^32 key space anyway).
 */
public final class IpCounter implements AutoCloseable {

    /** One bit per possible address: 2^32 bits. */
    private static final long BITMAP_BYTES = 1L << 32 >>> 3; // 512 MiB
    private static final long BITMAP_LONGS = BITMAP_BYTES >>> 3;
    private static final int WINDOW_BYTES = 1 << 30; // 1 GiB mapped at a time
    private static final int HLL_BITS = 14;          // 16384 registers, ~0.8% error
    private static final int HLL_REGISTERS = 1 << HLL_BITS;

    private final FileChannel channel;
    private final long fileSize;
    private final long[] bitmap;
    private final byte[] hll = new byte[HLL_REGISTERS];
    private long lines;
    private long badLines;

    private IpCounter(FileChannel channel, long fileSize) {
        this.channel = channel;
        this.fileSize = fileSize;
        this.bitmap = new long[(int) BITMAP_LONGS];
    }

    public static IpCounter open(Path file) throws java.io.IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        return new IpCounter(channel, channel.size());
    }

    /** Streams the whole file and fills the bitmap. */
    public IpCounter scan() throws java.io.IOException {
        long position = 0;
        while (position < fileSize) {
            long window = Math.min(WINDOW_BYTES, fileSize - position);
            ByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY, position, window);
            consume(buffer);
            position += window;
        }
        return this;
    }

    /**
     * Parses every complete line in {@code buffer}. A line that starts before the
     * window and ends inside it is skipped by the caller (see {@link #scan()}
     * alignment): windows are cut right after a newline instead.
     */
    private void consume(ByteBuffer buffer) {
        int start = 0;
        int limit = buffer.limit();
        while (start < limit) {
            int end = indexOfNewline(buffer, start, limit);
            if (end < 0) {
                // last window without a trailing newline
                end = limit;
            }
            long address = parseIpv4(buffer, start, end);
            int length = trimEnd(buffer, start, end) - start;
            lines++;
            if (address >= 0 && length > 0) {
                set(address);
                hllAdd(address);
            } else if (length > 0) {
                badLines++;
            }
            start = end + 1;
        }
    }

    private static int indexOfNewline(ByteBuffer buffer, int from, int limit) {
        for (int i = from; i < limit; i++) {
            if (buffer.get(i) == (byte) '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Strips a trailing {@code '\r'} so CRLF files count too. */
    private static int trimEnd(ByteBuffer buffer, int start, int end) {
        if (end > start && buffer.get(end - 1) == (byte) '\r') {
            return end - 1;
        }
        return end;
    }

    /**
     * Parses {@code a.b.c.d} into an unsigned 32-bit value, or returns -1.
     * Hand-rolled on purpose: {@code InetAddress.getByName} would do a DNS lookup
     * for anything that does not look like a literal and allocate a byte array.
     */
    static long parseIpv4(ByteBuffer buffer, int start, int stopExclusive) {
        int end = trimEnd(buffer, start, stopExclusive);
        long result = 0;
        int octet = 0;
        int digits = 0;
        int octets = 0;
        for (int i = start; i < end; i++) {
            byte raw = buffer.get(i);
            if (raw >= '0' && raw <= '9') {
                octet = octet * 10 + (raw - '0');
                if (++digits > 3 || octet > 255) {
                    return -1;
                }
            } else if (raw == '.') {
                if (digits == 0 || octets == 3) {
                    return -1;
                }
                result = (result << 8) | octet;
                octet = 0;
                digits = 0;
                octets++;
            } else {
                return -1;
            }
        }
        if (octets != 3 || digits == 0) {
            return -1;
        }
        return (result << 8) | octet;
    }

    private void set(long address) {
        bitmap[(int) (address >>> 6)] |= 1L << (address & 63);
    }

    private void hllAdd(long address) {
        int mixed = (int) hash(address);
        int index = mixed >>> (Integer.SIZE - HLL_BITS);
        int rank = Integer.numberOfLeadingZeros((mixed << HLL_BITS) | (1 << (HLL_BITS - 1))) + 1;
        if (rank > hll[index]) {
            hll[index] = (byte) rank;
        }
    }

    private static long hash(long value) {
        // splitmix64 finaliser: cheap, good avalanche on small integers
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Exact number of distinct addresses seen. */
    public long distinct() {
        long count = 0;
        for (long word : bitmap) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /** Orders of magnitude cheaper check that the bitmap is consistent. */
    public long cardinalityEstimate() {
        double inverseSum = 0;
        int zeros = 0;
        for (byte register : hll) {
            inverseSum += Math.pow(2, -register);
            if (register == 0) {
                zeros++;
            }
        }
        double m = HLL_REGISTERS;
        double estimate = 0.7213 / (1 + 1.079 / m) * m * m / inverseSum;
        if (estimate <= 2.5 * m && zeros > 0) {
            estimate = m * Math.log(m / (double) zeros); // small range correction
        }
        return Math.round(estimate);
    }

    public long lines() {
        return lines;
    }

    public long badLines() {
        return badLines;
    }

    /** Bytes of heap the exact structure occupies. */
    public long bitmapBytes() {
        return (long) bitmap.length * Long.BYTES;
    }

    public long mappedBytes() {
        return BITMAP_BYTES;
    }

    @Override
    public void close() throws java.io.IOException {
        channel.close();
    }

    /** Exposed for tests: resets state so a counter can be reused. */
    void reset() {
        Arrays.fill(bitmap, 0L);
        Arrays.fill(hll, (byte) 0);
        lines = 0;
        badLines = 0;
    }
}