package com.cleanroommc.unified;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A minimal streaming tar reader (Java 8 has none): ustar, GNU long names and pax path headers, which covers the
 * Adoptium JRE archives and this jar's own payload.
 */
final class TarReader {

    static final class Member {
        final String name;
        final char type;
        final int mode;
        final long size;
        /** Seconds since the epoch. */
        final long mtime;
        final String linkName;

        Member(String name, char type, int mode, long size, long mtime, String linkName) {
            this.name = name;
            this.type = type;
            this.mode = mode;
            this.size = size;
            this.mtime = mtime;
            this.linkName = linkName;
        }

        boolean isDirectory() {
            return type == '5';
        }

        boolean isFile() {
            return type == '0' || type == '\0' || type == '7';
        }

        boolean isSymlink() {
            return type == '2';
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[512];
    private final byte[] buf = new byte[1 << 16];
    /** Unread bytes of the current member's data, then its padding. */
    private long remaining;
    private long padding;

    TarReader(InputStream in) {
        this.in = in;
    }

    /** The next member, or {@code null} at the end of the archive. Skips what was not read of the previous one. */
    Member next() throws IOException {
        skip(remaining + padding);
        remaining = 0;
        padding = 0;
        String longName = null;
        String paxPath = null;
        while (true) {
            readFully(header, header.length);
            if (isZeroBlock(header)) {
                return null;
            }
            String name = string(header, 0, 100);
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            long mtime = octal(header, 136, 12);
            char type = (char) header[156];
            String linkName = string(header, 157, 100);
            if ("ustar".equals(string(header, 257, 5))) {
                String prefix = string(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
            }

            if (type == 'L') { // GNU long name: the data is the name of the next member
                longName = string(readData(size), 0, (int) size);
                continue;
            }
            if (type == 'x') { // pax extended header
                paxPath = paxValue(readData(size), "path");
                continue;
            }
            if (type == 'g') { // pax global header, nothing we need
                skip(padded(size));
                continue;
            }
            if (paxPath != null) {
                name = paxPath;
            } else if (longName != null) {
                name = longName;
            }
            boolean hasData = type != '5' && type != '2' && type != '1';
            remaining = hasData ? size : 0;
            padding = hasData ? padded(size) - size : padded(size);
            return new Member(name, type, mode, size, mtime, linkName);
        }
    }

    /** Copies the current member's data to {@code out}. */
    void copyTo(OutputStream out) throws IOException {
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                throw new EOFException("Truncated tar archive");
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private byte[] readData(long size) throws IOException {
        if (size > 1 << 20) {
            throw new IOException("Unexpectedly large tar header entry: " + size);
        }
        byte[] data = new byte[(int) size];
        readFully(data, data.length);
        skip(padded(size) - size);
        return data;
    }

    private void readFully(byte[] target, int length) throws IOException {
        int off = 0;
        while (off < length) {
            int n = in.read(target, off, length - off);
            if (n < 0) {
                throw new EOFException("Truncated tar archive");
            }
            off += n;
        }
    }

    private void skip(long count) throws IOException {
        long left = count;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new EOFException("Truncated tar archive");
            }
            left -= n;
        }
    }

    private static long padded(long size) {
        return (size + 511) & ~511L;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String string(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < buf.length && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] buf, int offset, int length) {
        if ((buf[offset] & 0x80) != 0) { // GNU base-256 encoding for large values
            long value = buf[offset] & 0x7F;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (buf[offset + i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') {
                if (value != 0) {
                    break;
                }
                continue;
            }
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static String paxValue(byte[] data, String key) {
        // Records are "<length> <key>=<value>\n"
        String text = new String(data, StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < text.length()) {
            int space = text.indexOf(' ', pos);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(text.substring(pos, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || pos + length > text.length()) {
                break;
            }
            String record = text.substring(space + 1, pos + length - 1);
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals(key)) {
                return record.substring(eq + 1);
            }
            pos += length;
        }
        return null;
    }
}
