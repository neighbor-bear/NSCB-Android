package io.nscb.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** PFS0 / HFS0 partition table reader + writer helpers. */
public final class Container {
    public enum Kind { PFS0, HFS0 }

    public static final class Entry {
        public String name;
        public long offset;       // relative to data start (after header)
        public long size;
        public byte[] hash;       // HFS0 only
        public long dataStart;    // absolute position of payload

        @Override
        public String toString() {
            return name + " (" + Util.size(size) + ")";
        }
    }

    public final Kind kind;
    public final long base;
    public final long headerSize;
    public final long totalSize;
    public final List<Entry> entries = new ArrayList<>();

    private Container(Kind kind, long base, long headerSize, long totalSize) {
        this.kind = kind;
        this.base = base;
        this.headerSize = headerSize;
        this.totalSize = totalSize;
    }

    public static Container parse(ByteSource src, long base, long limit, Kind kind) throws IOException {
        byte[] h = src.readFully(base, 0x10);
        if (h.length < 0x10) throw new IOException("truncated container header");
        String magic = new String(h, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (magic.equals("HFS0") != (kind == Kind.HFS0) || (!magic.equals("HFS0") && !magic.equals("PFS0"))) {
            throw new IOException("not a " + kind + " partition (magic " + magic + ")");
        }
        int count = Util.i32(h, 4);
        long strTab = Util.u32(h, 8);
        if (count < 0 || count > 100000) throw new IOException("invalid entry count " + count);
        int entrySize = kind == Kind.PFS0 ? 0x18 : 0x40;
        long tableEnd = 0x10L + (long) count * entrySize;
        long headerSize = tableEnd + strTab;
        if (headerSize > limit) throw new IOException("container header exceeds partition size");
        Container c = new Container(kind, base, headerSize, limit);

        byte[] tbl = src.readFully(base + 0x10, count * entrySize);
        byte[] names = src.readFully(base + tableEnd, (int) Math.min(strTab, 0x100000));
        if (tbl.length < count * entrySize) throw new IOException("truncated entry table");

        for (int i = 0; i < count; i++) {
            int off = i * entrySize;
            Entry e = new Entry();
            e.offset = Util.u64(tbl, off);
            e.size = Util.u64(tbl, off + 8);
            int nameOff = (int) Util.u32(tbl, off + 0x10);
            if (nameOff < 0 || nameOff > names.length) nameOff = 0;
            StringBuilder sb = new StringBuilder();
            for (int p = nameOff; p < names.length; p++) {
                int ch = names[p] & 0xFF;
                if (ch == 0) break;
                sb.append((char) ch);
            }
            e.name = sb.toString();
            if (kind == Kind.HFS0) {
                e.hash = new byte[0x20];
                System.arraycopy(tbl, off + 0x20, e.hash, 0, 0x20);
            }
            e.dataStart = base + headerSize + e.offset;
            if (e.offset < 0 || e.size < 0 || e.dataStart + e.size > src.size() + 1) {
                throw new IOException("entry out of bounds: " + e.name);
            }
            c.entries.add(e);
        }
        return c;
    }

    public Entry find(String name) {
        for (Entry e : entries) if (e.name.equals(name)) return e;
        return null;
    }

    /** Build a PFS0 header exactly like NSCB's Pfs0Stream (16-byte alignment padding). */
    public static byte[] buildPfs0Header(List<String> names, List<Long> sizes) {
        int n = names.size();
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) st.append('\0');
            st.append(names.get(i));
        }
        long logical = 0x10L + (long) n * 0x18 + st.length();
        long rem = 0x10 - (logical % 0x10);
        long headerSize = logical + rem;

        byte[] h = new byte[(int) headerSize];
        writeAscii(h, 0, "PFS0");
        putU32(h, 4, n);
        putU32(h, 8, st.length() + rem);
        long off = 0;
        int nameOff = 0;
        for (int i = 0; i < n; i++) {
            int p = 0x10 + i * 0x18;
            putU64(h, p, off);
            putU64(h, p + 8, sizes.get(i));
            putU32(h, p + 0x10, nameOff);
            off += sizes.get(i);
            nameOff += names.get(i).length() + 1;
        }
        writeAscii(h, 0x10 + n * 0x18, st.toString());
        return h;
    }

    /** Build an HFS0 header; hashes are sha256 over first 0x200 bytes of each item. */
    public static byte[] buildHfs0Header(List<String> names, List<Long> sizes, List<byte[]> hashes,
                                         List<Long> hashRegions) {
        int n = names.size();
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) st.append('\0');
            st.append(names.get(i));
        }
        long logical = 0x10L + (long) n * 0x40 + st.length();
        long headerSize = ((logical + 0x1FF) / 0x200) * 0x200;
        long rem = headerSize - logical;

        byte[] h = new byte[(int) headerSize];
        writeAscii(h, 0, "HFS0");
        putU32(h, 4, n);
        putU32(h, 8, st.length() + rem);
        long off = 0;
        int nameOff = 0;
        for (int i = 0; i < n; i++) {
            int p = 0x10 + i * 0x40;
            putU64(h, p, off);
            putU64(h, p + 8, sizes.get(i));
            putU32(h, p + 0x10, nameOff);
            putU32(h, p + 0x14, hashRegions == null ? 0x200 : hashRegions.get(i).intValue());
            byte[] hash = hashes.get(i);
            System.arraycopy(hash, 0, h, p + 0x20, 0x20);
            off += sizes.get(i);
            nameOff += names.get(i).length() + 1;
        }
        writeAscii(h, 0x10 + n * 0x40, st.toString());
        return h;
    }

    private static void writeAscii(byte[] dst, int off, String s) {
        for (int i = 0; i < s.length(); i++) dst[off + i] = (byte) s.charAt(i);
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    private static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }
}
