package io.nscb.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * XCI (game card image) rebuilding: XCZ -> XCI, XCI trimming, update-partition integration and
 * XCI creation from loose files or from an NSP. The 0xF000-byte gamecard header region is
 * preserved; HFS0 tables are regenerated with fresh sizes / offsets / hashes.
 */
public final class Xci {
    public static final long HEADER_REGION = 0xF000;

    private Xci() {
    }

    private static final class XItem {
        String name;
        long size;
        byte[] hash;
        File temp;       // decompressed payload
        ByteSource src;  // raw payload source
        long srcPos;
    }

    /**
     * Rebuilds an XCI/XCZ. When decompress is true every .ncz in the secure partition is converted
     * to .nca. When keepAll is false only "update" and "secure" partitions survive (trim).
     */
    public static long rebuild(Archive a, boolean keepAll, boolean decompress, Sink sink,
                               String outName, File tempDir, Progress p) throws IOException {
        return write(a, keepAll, decompress, null, false, sink, outName, tempDir, p);
    }

    /**
     * Rebuilds an XCI replacing its "update" partition with the contents of an update NSP.
     * The base game (secure partition) is kept untouched.
     */
    public static long integrateUpdate(Archive a, Archive update, Sink sink, String outName,
                                       File tempDir, Progress p) throws IOException {
        return write(a, true, true, update, false, sink, outName, tempDir, p);
    }

    /**
     * True merge: the secure partition itself is rebuilt from the update NSP (the newest
     * Program / Control / Meta NCAs and its ticket), and the update partition is emptied.
     * Tools that read the base NACP / CNMT therefore report the updated version, and the
     * console boots straight into the patched content.
     */
    public static long mergeUpdate(Archive a, Archive update, Sink sink, String outName,
                                   File tempDir, Progress p) throws IOException {
        return write(a, true, true, update, true, sink, outName, tempDir, p);
    }

    private static long write(Archive a, boolean keepAll, boolean decompress, Archive update,
                              boolean mergeSecure, Sink sink, String outName, File tempDir,
                              Progress p) throws IOException {
        if (a.type != Archive.Type.XCI && a.type != Archive.Type.XCZ) {
            throw new IOException("Not an XCI/XCZ archive");
        }
        if (a.secure == null) throw new IOException("XCI has no secure partition");
        if (!tempDir.exists() && !tempDir.mkdirs()) throw new IOException("cannot create temp dir " + tempDir);
        long rootBase = a.xci != null && a.xci.hfs0Offset > 0 ? a.xci.hfs0Offset : HEADER_REGION;

        List<XItem> secItems;
        if (mergeSecure) {
            if (update == null || update.pfs == null) throw new IOException("更新包不是 NSP: "
                    + (update == null ? "(none)" : update.fileName));
            secItems = collectItems(update, update.pfs.entries, decompress, tempDir, p, "sec");
        } else {
            secItems = collectItems(a, a.secure.entries, decompress, tempDir, p, "sec");
        }

        List<String> secNames = new ArrayList<>();
        List<Long> secSizes = new ArrayList<>();
        List<byte[]> secHashes = new ArrayList<>();
        List<Long> secRegions = new ArrayList<>();
        for (XItem it : secItems) {
            secNames.add(it.name);
            secSizes.add(it.size);
            secHashes.add(it.hash == null ? new byte[0x20] : it.hash);
            secRegions.add(0x200L);
        }
        byte[] secHeader = Container.buildHfs0Header(secNames, secSizes, secHashes, secRegions);
        long secSize = secHeader.length;
        for (XItem it : secItems) secSize += it.size;

        // Optional replacement for the update partition, taken from an NSP.
        List<XItem> updItems = null;
        byte[] updHeader = null;
        long updSize = 0;
        if (update != null) {
            if (mergeSecure) {
                // True merge puts the update content into "secure"; the update partition stays
                // present but empty so the root partition layout is unchanged.
                updItems = new ArrayList<>();
                updHeader = Container.buildHfs0Header(new ArrayList<String>(),
                        new ArrayList<Long>(), new ArrayList<byte[]>(), new ArrayList<Long>());
                updSize = updHeader.length;
            } else {
                if (update.pfs == null) throw new IOException("更新包不是 NSP: " + update.fileName);
                updItems = new ArrayList<>();
                for (Container.Entry e : update.pfs.entries) {
                    String l = e.name.toLowerCase(Locale.ROOT);
                    if (Nsz.isCompressedName(e.name)) {
                        throw new IOException("更新包内含压缩 NCA，请先 NSZ → NSP：" + e.name);
                    }
                    if (!(l.endsWith(".nca") || l.endsWith(".tik") || l.endsWith(".cert") || l.endsWith(".xml"))) {
                        continue;
                    }
                    XItem it = new XItem();
                    it.name = e.name;
                    it.size = e.size;
                    it.hash = e.hash != null ? e.hash : hashFirst(update.src, e.dataStart, e.size);
                    it.src = update.src;
                    it.srcPos = e.dataStart;
                    updItems.add(it);
                }
                if (updItems.isEmpty()) throw new IOException("更新包中没有 NCA: " + update.fileName);
                List<String> names = new ArrayList<>();
                List<Long> sizes = new ArrayList<>();
                List<byte[]> hashes = new ArrayList<>();
                List<Long> regions = new ArrayList<>();
                for (XItem it : updItems) {
                    names.add(it.name);
                    sizes.add(it.size);
                    hashes.add(it.hash == null ? new byte[0x20] : it.hash);
                    regions.add(0x200L);
                }
                updHeader = Container.buildHfs0Header(names, sizes, hashes, regions);
                updSize = updHeader.length;
                for (XItem it : updItems) updSize += it.size;
            }
        }

        List<String> rNames = new ArrayList<>();
        List<Long> rSizes = new ArrayList<>();
        List<byte[]> rHashes = new ArrayList<>();
        List<Long> rRegions = new ArrayList<>();
        List<Long> rCopyPos = new ArrayList<>();
        for (Container.Entry e : a.root.entries) {
            boolean isSecure = e.name.equals("secure");
            boolean isUpdate = e.name.equals("update");
            if (!keepAll && !isSecure && !isUpdate) continue;
            if (update != null && isUpdate) {
                rNames.add(e.name);
                rSizes.add(updSize);
                rHashes.add(hashFirst(updHeader, updHeader.length));
                rRegions.add(0x200L);
                rCopyPos.add(0L);
                continue;
            }
            rNames.add(e.name);
            rSizes.add(isSecure ? secSize : e.size);
            rHashes.add(isSecure ? hashFirst(secHeader, 0x200) : e.hash);
            rRegions.add(0x200L);
            rCopyPos.add(e.dataStart);
        }
        if (!rNames.contains("secure")) throw new IOException("XCI root partition has no secure entry");
        if (update != null && !rNames.contains("update")) {
            int at = rNames.indexOf("secure");
            if (at < 0) at = rNames.size();
            rNames.add(at, "update");
            rSizes.add(at, updSize);
            rHashes.add(at, hashFirst(updHeader, updHeader.length));
            rRegions.add(at, 0x200L);
            rCopyPos.add(at, 0L);
        }
        byte[] rootHeader = Container.buildHfs0Header(rNames, rSizes, rHashes, rRegions);

        long total = rootBase + rootHeader.length;
        for (long s : rSizes) total += s;

        // Absolute file offset (and 0x200 sector number) of the secure partition.
        long securePos = rootBase + rootHeader.length;
        for (int i = 0; i < rNames.size(); i++) {
            if (rNames.get(i).equals("secure")) break;
            securePos += rSizes.get(i);
        }

        long done;
        try (OutputStream out = sink.open(outName)) {
            byte[] pre = a.src.readFully(0, (int) rootBase);
            if (pre.length < rootBase) throw new IOException("XCI header region truncated");
            putU32(pre, 0x104, securePos / 0x200);      // secure partition offset (sectors)
            putU64(pre, 0x118, Math.max(0, total / 0x200 - 1)); // cartridge size (0x200 units)
            putU64(pre, 0x138, rootHeader.length);     // root HFS0 header size
            System.arraycopy(Util.sha256(rootHeader), 0, pre, 0x140, 0x20); // root header hash
            out.write(pre);
            out.write(rootHeader);
            done = rootHeader.length;
            for (int i = 0; i < rNames.size(); i++) {
                String nm = rNames.get(i);
                if (nm.equals("secure")) {
                    out.write(secHeader);
                    done += secHeader.length;
                    for (XItem it : secItems) {
                        if (it.temp != null) {
                            try (InputStream in = new FileInputStream(it.temp)) {
                                Util.copy(in, out);
                            }
                        } else {
                            Util.copyRange(it.src, it.srcPos, it.size, out);
                        }
                        done += it.size;
                        p.onProgress("Writing " + it.name, done, total);
                    }
                } else if (update != null && nm.equals("update")) {
                    out.write(updHeader);
                    done += updHeader.length;
                    for (XItem it : updItems) {
                        p.onProgress("Writing update/" + it.name, done, total);
                        Util.copyRange(it.src, it.srcPos, it.size, out);
                        done += it.size;
                    }
                } else {
                    long len = rSizes.get(i);
                    Util.copyRange(a.src, rCopyPos.get(i), len, out);
                    done += len;
                    p.onProgress("Writing " + nm, done, total);
                }
            }
        } finally {
            for (XItem it : secItems) {
                if (it.temp != null) it.temp.delete();
            }
        }
        p.onProgress("XCI written", total, total);
        return total;
    }

    /** Collects partition items from a secure HFS0 or an NSP PFS0, decompressing NCZ if needed. */
    private static List<XItem> collectItems(Archive arc, List<Container.Entry> entries, boolean decompress,
                                            File tempDir, Progress p, String tag) throws IOException {
        List<XItem> items = new ArrayList<>();
        for (Container.Entry e : entries) {
            XItem it = new XItem();
            it.name = e.name;
            it.size = e.size;
            it.hash = e.hash;
            if (decompress && Nsz.isCompressedName(e.name)) {
                long expected = Nsz.readInfo(arc.src, e.dataStart, e.size, arc.keys).ncaSize;
                if (expected <= 0 && arc.cnmt != null) expected = Nsz.cnmtSize(arc.cnmt, e.name);
                if (expected <= 0) {
                    throw new IOException("Cannot determine uncompressed size of " + e.name
                            + "; import prod.keys (header_key) or keep the CNMT entry.");
                }
                File tmp = new File(tempDir, tag + "-" + items.size() + ".nca");
                p.onProgress("Decompressing " + e.name, 0, expected);
                try (OutputStream o = new FileOutputStream(tmp)) {
                    Nsz.decompress(arc.src, e.dataStart, e.size, o, arc.keys, p, e.name);
                }
                it.name = Nsz.plainName(e.name);
                it.temp = tmp;
                it.size = tmp.length();
                it.hash = hashFirst(tmp);
                if (it.size != expected) {
                    p.onLog("WARNING: " + e.name + " decompressed to " + it.size + " / " + expected + " bytes");
                }
            } else {
                it.src = arc.src;
                it.srcPos = e.dataStart;
                if (it.hash == null) it.hash = hashFirst(arc.src, e.dataStart, e.size);
            }
            items.add(it);
        }
        return items;
    }

    /** Creates a fresh XCI from loose NCA / ticket / cert / xml files. */
    public static long create(List<Packer.Item> files, Keys keys, String outName, Sink sink,
                              Progress p) throws IOException {
        List<Packer.Item> list = new ArrayList<>();
        for (Packer.Item it : files) {
            String l = it.name.toLowerCase(Locale.ROOT);
            if (l.endsWith(".nca") || l.endsWith(".ncz") || l.endsWith(".tik")
                    || l.endsWith(".cert") || l.endsWith(".xml")) {
                list.add(it);
            }
        }
        if (list.isEmpty()) throw new IOException("no NCA / ticket / cert files selected");

        Cnmt cnmt = null;
        for (Packer.Item it : list) {
            if (it.name.toLowerCase(Locale.ROOT).endsWith(".cnmt.nca")) {
                try {
                    cnmt = new Nca(it.src, 0, it.size, keys).readCnmt();
                } catch (Exception ignored) {
                }
                break;
            }
        }
        list = Packer.order(list, cnmt);

        List<String> secNames = new ArrayList<>();
        List<Long> secSizes = new ArrayList<>();
        List<byte[]> secHashes = new ArrayList<>();
        for (Packer.Item it : list) {
            secNames.add(it.name);
            secSizes.add(it.size);
            secHashes.add(hashFirst(it.src, 0, it.size));
        }
        List<Long> regions = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) regions.add(0x200L);
        byte[] secHeader = Container.buildHfs0Header(secNames, secSizes, secHashes, regions);
        long secSize = secHeader.length;
        for (long s : secSizes) secSize += s;

        List<String> emptyNames = new ArrayList<>();
        byte[] empty = Container.buildHfs0Header(emptyNames, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<String> rNames = new ArrayList<>();
        List<Long> rSizes = new ArrayList<>();
        List<byte[]> rHashes = new ArrayList<>();
        List<Long> rRegions = new ArrayList<>();
        for (String nm : new String[]{"update", "normal"}) {
            rNames.add(nm);
            rSizes.add((long) empty.length);
            rHashes.add(hashFirst(empty, empty.length));
            rRegions.add(0x200L);
        }
        rNames.add("secure");
        rSizes.add(secSize);
        rHashes.add(hashFirst(secHeader, 0x200));
        rRegions.add(0x200L);
        rNames.add("logo");
        rSizes.add((long) empty.length);
        rHashes.add(hashFirst(empty, empty.length));
        rRegions.add(0x200L);
        byte[] rootHeader = Container.buildHfs0Header(rNames, rSizes, rHashes, rRegions);

        long total = HEADER_REGION + rootHeader.length + 3L * empty.length + secSize;
        byte[] gc = new byte[(int) HEADER_REGION];
        putAscii(gc, 0x100, "HEAD");
        long securePos = HEADER_REGION + rootHeader.length + 2L * empty.length;
        putU32(gc, 0x104, securePos / 0x200);                            // secure offset (sectors)
        putU32(gc, 0x108, 0xFFFFFFFFL);                                  // no backup partition
        putU64(gc, 0x118, Math.max(0, total / 0x200 - 1));               // cartridge size (0x200 units)
        putU64(gc, 0x130, HEADER_REGION);
        putU64(gc, 0x138, rootHeader.length);
        System.arraycopy(Util.sha256(rootHeader), 0, gc, 0x140, 0x20);   // root header hash

        long done;
        try (OutputStream out = sink.open(outName)) {
            out.write(gc);
            out.write(rootHeader);
            done = rootHeader.length;
            out.write(empty);                     // update
            out.write(empty);                     // normal
            done += 2L * empty.length;
            out.write(secHeader);
            done += secHeader.length;
            for (int i = 0; i < list.size(); i++) {
                Packer.Item it = list.get(i);
                p.onProgress("Writing " + it.name, done, total);
                Util.copyRange(it.src, 0, it.size, out);
                done += it.size;
            }
            out.write(empty);                     // logo
            done += empty.length;
            p.onProgress("XCI written", total, total);
        }
        return HEADER_REGION + done;
    }

    /** Builds an XCI directly from an NSP's PFS0 entries (no extraction needed). */
    public static long fromArchive(Archive a, String outName, Sink sink, Progress p) throws IOException {
        if (a.pfs == null) throw new IOException("请选择 .nsp 文件");
        List<Packer.Item> items = new ArrayList<>();
        for (Container.Entry e : a.pfs.entries) {
            String l = e.name.toLowerCase(Locale.ROOT);
            if (Nsz.isCompressedName(e.name)) {
                throw new IOException("NSP 内含压缩 NCA，请先将 NSZ 转成 NSP: " + e.name);
            }
            if (!(l.endsWith(".nca") || l.endsWith(".tik") || l.endsWith(".cert") || l.endsWith(".xml"))) {
                continue;
            }
            items.add(new Packer.Item(e.name, e.size, new SliceSource(a.src, e.dataStart, e.size)));
        }
        if (items.isEmpty()) throw new IOException("NSP 中没有可用于 XCI 的 NCA");
        return create(items, a.keys, outName, sink, p);
    }

    private static byte[] hashFirst(ByteSource src, long pos, long size) throws IOException {
        int n = (int) Math.min(0x200, size);
        return Util.sha256(src.readFully(pos, n));
    }

    private static byte[] hashFirst(File f) throws IOException {
        int n = (int) Math.min(0x200, f.length());
        byte[] b = new byte[n];
        try (InputStream in = new FileInputStream(f)) {
            int got = 0;
            while (got < n) {
                int r = in.read(b, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            if (got < n) {
                byte[] s = new byte[got];
                System.arraycopy(b, 0, s, 0, got);
                return Util.sha256(s);
            }
        }
        return Util.sha256(b);
    }

    private static byte[] hashFirst(byte[] data, int len) {
        int n = Math.min(0x200, len);
        byte[] b = new byte[n];
        System.arraycopy(data, 0, b, 0, n);
        return Util.sha256(b);
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }

    private static void putU32(byte[] b, int off, long v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }
}
