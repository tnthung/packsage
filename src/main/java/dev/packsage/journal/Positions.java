package dev.packsage.journal;

import java.io.ByteArrayOutputStream;

/**
 * Block positions packed into a long (x 26 bits, z 26 bits, y 12 bits, all signed), and a compact blob of many:
 * an unsigned varint count, then per position the zigzag varint deltas dx, dy, dz from the previous one (the first
 * from 0,0,0).
 */
public final class Positions {
    static final int MAX_HORIZONTAL = (1 << 25) - 1;
    static final int MAX_VERTICAL = (1 << 11) - 1;

    private Positions() {
    }

    public static long pack(int x, int y, int z) {
        if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_VERTICAL) {
            throw new IllegalArgumentException("position " + x + "," + y + "," + z + " is out of range");
        }
        return ((long) x << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    /** Encodes the first {@code count} positions of {@code packed}. */
    public static byte[] encode(long[] packed, int count) {
        if (count < 0 || count > packed.length) {
            throw new IllegalArgumentException("count " + count + " is outside 0.." + packed.length);
        }
        var out = new ByteArrayOutputStream(2 + count * 3);
        writeVarint(out, count);
        int x = 0;
        int y = 0;
        int z = 0;
        for (int i = 0; i < count; i++) {
            var position = packed[i];
            writeVarint(out, zigzag(x(position) - x));
            writeVarint(out, zigzag(y(position) - y));
            writeVarint(out, zigzag(z(position) - z));
            x = x(position);
            y = y(position);
            z = z(position);
        }
        return out.toByteArray();
    }

    public static long[] decode(byte[] blob) {
        var reader = new Reader(blob);
        var count = reader.varint();
        if (count > blob.length) {
            throw new IllegalArgumentException("position blob count " + count + " exceeds its " + blob.length + " bytes");
        }
        var positions = new long[(int) count];
        long x = 0;
        long y = 0;
        long z = 0;
        for (int i = 0; i < positions.length; i++) {
            x += unzigzag(reader.varint());
            y += unzigzag(reader.varint());
            z += unzigzag(reader.varint());
            positions[i] = pack(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
        }
        if (reader.offset != blob.length) {
            throw new IllegalArgumentException("position blob has " + (blob.length - reader.offset) + " trailing bytes");
        }
        return positions;
    }

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static final class Reader {
        private final byte[] blob;
        private int offset;

        Reader(byte[] blob) {
            this.blob = blob;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (offset >= blob.length) {
                    throw new IllegalArgumentException("position blob ends inside a number");
                }
                var b = blob[offset++];
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("position blob number is too long");
        }
    }
}
