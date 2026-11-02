package io.nscb.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * RomFS extraction: locates the IVFC level 6 partition inside a Program NCA and walks the
 * directory / file metadata tables.
 */
public final class Romfs {
    private Romfs() {
    }

    private static final long EMPTY = 0xFFFFFFFFL;   // ROMFS_ENTRY_EMPTY

    private static final class Counter {
        int files;
        long bytes;
    }

    /**
     * Extracts every file below prefix (e.g. "0100000000010000/") into sink.
     * Returns the number of files written.
     */
    public static int extract(Nca nca, Sink sink, String prefix, Progress p) throws IOException {
        if (nca == null || !nca.headerOk) throw new IOException(nca == null ? "no NCA" : nca.decryptError);
        int idx = -1;
        for (Nca.Section s : nca.validSections()) {
            if (s.fsType == 3) {
                idx = s.index;
                break;
            }
        }
        if (idx < 0) {
            for (Nca.Section s : nca.validSections()) {
                ByteSource probe = nca.sectionSource(s.index);
                if (probe == null) continue;
                byte[] m = probe.readFully(0, 0x10);
                if (m.length >= 4 && ascii(m, 0, 4).equals("IVFC")) {
                    idx = s.index;
                    break;
                }
            }
        }
        if (idx < 0) throw new IOException("no RomFS (IVFC) section found in NCA");

        ByteSource fs = nca.sectionSource(idx);
        if (fs == null) throw new IOException("RomFS section cannot be decrypted (missing title key)");

        byte[] ivfc = fs.readFully(0, 0x98);
        if (ivfc.length < 0x98 || !ascii(ivfc, 0, 4).equals("IVFC")) throw new IOException("bad IVFC header");
        // level_headers[5] = the RomFS level: logical_offset @0x88, hash_data_size @0x90.
        long romfsOff = Util.u64(ivfc, 0x88);
        long romfsSize = Util.u64(ivfc, 0x90);

        byte[] rh = fs.readFully(romfsOff, 0x50);
        if (rh.length < 0x50) throw new IOException("bad RomFS header");
        long headerSize = Util.u64(rh, 0);
        if (headerSize < 0x48 || headerSize > 0x1000) throw new IOException("bad RomFS header");
        long dirMetaOff = Util.u64(rh, 0x18);
        long dirMetaSize = Util.u64(rh, 0x20);
        long fileMetaOff = Util.u64(rh, 0x38);
        long fileMetaSize = Util.u64(rh, 0x40);
        long dataOff = Util.u64(rh, 0x48);
        if (dirMetaSize <= 0) {
            p.onLog("RomFS is empty");
            return 0;
        }

        long dirBase = romfsOff + dirMetaOff;
        long fileBase = romfsOff + fileMetaOff;
        long dataBase = romfsOff + dataOff;
        if (romfsSize > 0 && dataBase > romfsOff + romfsSize) throw new IOException("bad RomFS layout");

        Counter c = new Counter();
        walk(fs, dirBase, fileBase, dataBase, 0, prefix, sink, c, p, 0);
        p.onLog("RomFS: " + c.files + " files, " + Util.size(c.bytes));
        return c.files;
    }

    private static void walk(ByteSource fs, long dirBase, long fileBase, long dataBase,
                             long dirOff, String prefix, Sink sink, Counter c, Progress p,
                             int depth) throws IOException {
        if (depth > 64) throw new IOException("RomFS directory nesting too deep");
        byte[] d = fs.readFully(dirBase + dirOff, 0x18);
        if (d.length < 0x18) return;
        long childDir = Util.u32(d, 8);
        long childFile = Util.u32(d, 0xC);

        long f = childFile;
        int guard = 0;
        while (f != EMPTY) {
            if (++guard > 1000000) throw new IOException("RomFS file chain loop");
            byte[] fe = fs.readFully(fileBase + f, 0x20);
            if (fe.length < 0x20) break;
            long nameLen = Util.u32(fe, 0x1C);
            if (nameLen > 0x1000) nameLen = 0;
            byte[] nm = fs.readFully(fileBase + f + 0x20, (int) nameLen);
            String name = entryName(nm);
            long off = Util.u64(fe, 8);
            long size = Util.u64(fe, 0x10);
            checkBounds(fs, dataBase + off, size, prefix + name);
            p.onProgress("Extracting " + prefix + name, c.files, 0);
            try (OutputStream out = sink.open(prefix + name)) {
                Util.copyRange(fs, dataBase + off, size, out);
            }
            c.files++;
            c.bytes += size;
            f = Util.u32(fe, 4);
        }

        long dd = childDir;
        guard = 0;
        while (dd != EMPTY) {
            if (++guard > 1000000) throw new IOException("RomFS directory chain loop");
            byte[] de = fs.readFully(dirBase + dd, 0x18);
            if (de.length < 0x18) break;
            long nameLen = Util.u32(de, 0x14);
            if (nameLen > 0x1000) nameLen = 0;
            byte[] nm = fs.readFully(dirBase + dd + 0x18, (int) nameLen);
            String name = entryName(nm);
            walk(fs, dirBase, fileBase, dataBase, dd, prefix + name + "/", sink, c, p, depth + 1);
            dd = Util.u32(de, 4);
        }
    }

    private static String entryName(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        s = Util.sanitize(s);
        return s.isEmpty() ? "unnamed" : s;
    }

    private static void checkBounds(ByteSource fs, long pos, long len, String name) throws IOException {
        if (pos < 0 || len < 0 || pos + len > fs.size()) throw new IOException("RomFS file out of bounds: " + name);
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.US_ASCII);
    }
}
