package io.nscb.engine;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** NSZ / NCZ decompression (zstd), converted into a plain NSP. */
public final class Nsz {
    private Nsz() {
    }

    public static final class NczSection {
        public long offset;
        public long size;
        public long cryptoType;
        public byte[] key = new byte[16];
        public byte[] counter = new byte[16];
    }

    public static final class NczInfo {
        public byte[] header = new byte[0];
        public long ncaSize = -1;
        public long dataStart;
        public long dataLength;
        public final List<NczSection> sections = new ArrayList<>();
        public boolean blockCompressed;
        public long blockSize;
        public long blockDecompressedSize;
        public long blocksStart;
        public long[] blockSizes = new long[0];
    }

    public static boolean isCompressedName(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        return l.endsWith(".ncz");
    }

    public static String plainName(String name) {
        return isCompressedName(name) ? name.substring(0, name.length() - 1) + "a" : name;
    }

    /** Reads the NCZ header + section table without decompressing the body. */
    public static NczInfo readInfo(ByteSource src, long base, long entrySize, Keys keys) throws IOException {
        NczInfo info = new NczInfo();
        int headLen = (int) Math.min(0x4000, entrySize);
        info.header = src.readFully(base, headLen);
        if (headLen < 0x4000) throw new IOException("NCZ entry too small: " + entrySize);
        byte[] tbl = src.readFully(base + 0x4000, 0x10);
        if (tbl.length < 0x10) throw new IOException("NCZ section header truncated");
        String magic = new String(tbl, 0, 8, java.nio.charset.StandardCharsets.US_ASCII);
        if (!magic.equals("NCZSECTN")) throw new IOException("bad NCZ magic: " + magic);
        long count = Util.u64(tbl, 8);
        long maxBySize = Math.max(0, (entrySize - 0x4010) / 0x40);
        if (count <= 0 || count > maxBySize || count > 0x10000) {
            throw new IOException("implausible NCZ section count " + count);
        }
        byte[] s = src.readFully(base + 0x4010, (int) count * 0x40);
        if (s.length < count * 0x40) throw new IOException("NCZ section table truncated");
        for (int i = 0; i < count; i++) {
            int p = i * 0x40;
            NczSection sec = new NczSection();
            sec.offset = Util.u64(s, p);
            sec.size = Util.u64(s, p + 8);
            sec.cryptoType = Util.u64(s, p + 0x10);
            System.arraycopy(s, p + 0x20, sec.key, 0, 16);
            System.arraycopy(s, p + 0x30, sec.counter, 0, 16);
            info.sections.add(sec);
        }
        info.dataStart = base + 0x4010 + count * 0x40;
        info.dataLength = base + entrySize - info.dataStart;

        // The NCZ section table alone is enough to know the uncompressed size:
        // nsz/nut store the sections back to back starting at 0x4000.
        long sum = 0x4000;
        long end = 0x4000;
        for (NczSection sec : info.sections) {
            sum += sec.size;
            end = Math.max(end, sec.offset + sec.size);
        }
        info.ncaSize = Math.max(sum, end);

        // Block compressed NCZ ("NCZBLOCK") carries the body size in its own header.
        if (info.dataLength >= 0x18) {
            byte[] bh = src.readFully(info.dataStart, 0x18);
            if (bh.length == 0x18 && new String(bh, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)
                    .equals("NCZBLOCK")) {
                int exp = Util.u8(bh, 0x0B);
                long nBlocks = Util.u32(bh, 0x0C);
                long decompressed = Util.u64(bh, 0x10);
                if (exp >= 14 && exp <= 32 && nBlocks > 0 && nBlocks < 1 << 24 && decompressed > 0) {
                    info.blockCompressed = true;
                    info.blockSize = 1L << exp;
                    info.blockDecompressedSize = decompressed;
                    info.ncaSize = 0x4000 + decompressed;
                    info.blocksStart = info.dataStart + 0x18 + nBlocks * 4;
                    long[] sizes = new long[(int) nBlocks];
                    byte[] raw = src.readFully(info.dataStart + 0x18, (int) nBlocks * 4);
                    for (int i = 0; i < nBlocks; i++) sizes[i] = Util.u32(raw, i * 4);
                    info.blockSizes = sizes;
                }
            }
        }

        if (keys != null && keys.has("header_key")) {
            try {
                byte[] dec = new Aes.Xts(keys.get("header_key")).decrypt(0, info.header);
                String m = new String(dec, 0x200, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (m.equals("NCA3") || m.equals("NCA2") || m.equals("NCA0")) {
                    long hdrSize = Util.u64(dec, 0x208);
                    if (hdrSize >= 0x4000) info.ncaSize = hdrSize;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return info;
    }

    /** Decompresses a single NCZ entry into out, re-encrypting CTR sections. */
    public static long decompress(ByteSource src, long base, long entrySize, OutputStream out, Keys keys,
                                  Progress p, String label) throws IOException {
        NczInfo info = readInfo(src, base, entrySize, keys);
        out.write(info.header);
        long pos = 0x4000;
        long limit = info.ncaSize > 0 ? info.ncaSize : Long.MAX_VALUE;
        long written = info.header.length;
        try (InputStream zs = bodyStream(src, info)) {
            byte[] buf = new byte[0x10000];
            int n;
            while ((n = zs.read(buf)) > 0) {
                if (pos >= limit) break;
                int len = (int) Math.min(n, limit - pos);
                byte[] chunk = new byte[len];
                System.arraycopy(buf, 0, chunk, 0, len);
                encryptChunk(info, pos, chunk);
                out.write(chunk, 0, len);
                pos += len;
                written += len;
                postProgress(p, label, written, info.ncaSize > 0 ? info.ncaSize : written);
            }
        }
        if (info.ncaSize > 0 && written < info.ncaSize) {
            p.onLog("WARNING: " + label + " decompressed to " + written + " / " + info.ncaSize + " bytes");
        }
        return written;
    }

    /** Body data as a sequential stream: either one zstd frame or NCZBLOCK blocks. */
    private static InputStream bodyStream(ByteSource src, NczInfo info) throws IOException {
        if (!info.blockCompressed) {
            return new ZstdInputStreamNoFinalizer(
                    new ByteSourceInputStream(src, info.dataStart, info.dataLength));
        }
        return new BlockInputStream(src, info);
    }

    /** Sequential reader over the NCZBLOCK layout used by newer NSZ files. */
    private static final class BlockInputStream extends InputStream {
        private final ByteSource src;
        private final NczInfo info;
        private long blockId;
        private int inBlock;
        private byte[] cur;

        BlockInputStream(ByteSource src, NczInfo info) {
            this.src = src;
            this.info = info;
        }

        private boolean load(long id) throws IOException {
            if (id >= info.blockSizes.length) return false;
            long offset = info.blocksStart;
            for (int i = 0; i < id; i++) offset += info.blockSizes[i];
            long decSize = info.blockSize;
            long remain = info.blockDecompressedSize - id * info.blockSize;
            if (remain < decSize) decSize = remain;
            if (decSize <= 0) return false;
            byte[] packed = src.readFully(offset, (int) info.blockSizes[(int) id]);
            if (info.blockSizes[(int) id] >= decSize) {
                cur = packed;
            } else {
                try (InputStream in = new ZstdInputStreamNoFinalizer(new java.io.ByteArrayInputStream(packed))) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) decSize);
                    byte[] tmp = new byte[0x10000];
                    int got;
                    while ((got = in.read(tmp)) > 0) bo.write(tmp, 0, got);
                    cur = bo.toByteArray();
                }
            }
            inBlock = 0;
            return cur != null && cur.length > 0;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (cur != null && inBlock < cur.length) {
                int take = Math.min(len, cur.length - inBlock);
                System.arraycopy(cur, inBlock, b, off, take);
                inBlock += take;
                return take;
            }
            if (!load(blockId)) return -1;
            blockId++;
            return read(b, off, len);
        }
    }

    private static void encryptChunk(NczInfo info, long pos, byte[] chunk) {
        int done = 0;
        while (done < chunk.length) {
            long abs = pos + done;
            NczSection sec = sectionAt(info, abs);
            int take;
            if (sec == null) {
                take = chunk.length - done;
            } else {
                take = (int) Math.min(chunk.length - done, sec.offset + sec.size - abs);
            }
            if (take <= 0) break;
            if (sec != null && (sec.cryptoType == 3 || sec.cryptoType == 4)) {
                Aes.Ctr ctr = new Aes.Ctr(sec.key, sec.counter);
                ctr.process(abs, chunk, done, take);
            }
            done += take;
        }
    }

    private static NczSection sectionAt(NczInfo info, long abs) {
        for (NczSection s : info.sections) {
            if (abs >= s.offset && abs < s.offset + s.size) return s;
        }
        return null;
    }

    private static void postProgress(Progress p, String label, long done, long total) {
        p.onProgress(label, done, total);
    }

    /** Converts an NSZ archive into a plain NSP written via sink. */
    public static long toNsp(Archive a, Sink sink, String outName, Progress p) throws IOException {
        if (a.pfs == null) throw new IOException("Not an NSP/NSZ archive");
        List<Container.Entry> list = a.pfs.entries;
        List<String> names = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        long total = 0;
        for (Container.Entry e : list) {
            String out = plainName(e.name);
            long size = e.size;
            if (isCompressedName(e.name)) {
                NczInfo info = readInfo(a.src, e.dataStart, e.size, a.keys);
                size = info.ncaSize;
                if (size <= 0 && a.cnmt != null) size = cnmtSize(a.cnmt, e.name);
                if (size <= 0) {
                    throw new IOException("Cannot determine uncompressed size of " + e.name
                            + "; import prod.keys (header_key) or keep the .cnmt.nca entry intact.");
                }
            }
            names.add(out);
            sizes.add(size);
            total += size;
        }
        byte[] header = Container.buildPfs0Header(names, sizes);
        long written;
        try (OutputStream out = sink.open(outName)) {
            out.write(header);
            written = header.length;
            for (int i = 0; i < list.size(); i++) {
                Container.Entry e = list.get(i);
                p.onProgress("Decompressing " + e.name, written, header.length + total);
                if (isCompressedName(e.name)) {
                    written += decompress(a.src, e.dataStart, e.size, out, a.keys, p, e.name);
                } else {
                    Util.copyRange(a.src, e.dataStart, e.size, out);
                    written += e.size;
                }
            }
        }
        p.onProgress("NSP written", written, written);
        return written;
    }

    /** "name.nca" -> "name.ncz". */
    public static String compressedName(String name) {
        if (isCompressedName(name)) return name;
        return name.substring(0, name.length() - 1) + "z";
    }

    /**
     * Compresses an NSP into an NSZ: Program / PublicData NCAs become NCZ entries, everything
     * else (meta, control, tickets, certs) is copied verbatim. Compressed entries are staged in
     * tempDir because the PFS0 header needs their final sizes up front.
     */
    public static long toNsz(Archive a, Sink sink, String outName, int level, File tempDir,
                             Progress p) throws IOException {
        if (a.pfs == null) throw new IOException("Not an NSP archive");
        if (!tempDir.exists() && !tempDir.mkdirs()) throw new IOException("cannot create temp dir " + tempDir);

        List<Container.Entry> list = a.pfs.entries;
        List<String> names = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        List<File> temps = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            Container.Entry e = list.get(i);
            Nca n = a.ncaFor(e.name);
            File tmp = null;
            String outNameEntry = e.name;
            long size = e.size;
            if (canCompress(n, e)) {
                tmp = new File(tempDir, String.format(Locale.ROOT, "ncz-%d.tmp", i));
                compressNca(a.src, e, n, tmp, level, p);
                outNameEntry = compressedName(e.name);
                size = tmp.length();
            }
            names.add(outNameEntry);
            sizes.add(size);
            temps.add(tmp);
            total += size;
        }

        byte[] header = Container.buildPfs0Header(names, sizes);
        long written;
        try (OutputStream out = sink.open(outName)) {
            out.write(header);
            written = header.length;
            for (int i = 0; i < list.size(); i++) {
                Container.Entry e = list.get(i);
                p.onProgress("Writing " + names.get(i), written, header.length + total);
                File tmp = temps.get(i);
                if (tmp != null) {
                    try (InputStream in = new FileInputStream(tmp)) {
                        Util.copy(in, out);
                    }
                    written += tmp.length();
                    tmp.delete();
                } else {
                    Util.copyRange(a.src, e.dataStart, e.size, out);
                    written += e.size;
                }
            }
        } finally {
            for (File f : temps) {
                if (f != null) f.delete();
            }
        }
        p.onProgress("NSZ written", written, written);
        return written;
    }

    private static boolean canCompress(Nca n, Container.Entry e) {
        if (n == null || !n.headerOk || e.size < 0x4000) return false;
        if (!e.name.toLowerCase(Locale.ROOT).endsWith(".nca")) return false;
        if (n.contentType != Nca.PROGRAM && n.contentType != Nca.PUBLIC_DATA) return false;
        List<Nca.Section> secs = n.validSections();
        if (secs.isEmpty()) return false;
        for (Nca.Section s : secs) {
            if (s.cryptoType == 2) return false;                       // XTS sections unsupported
            if (s.cryptoType != 1 && n.titleKey == null) return false;  // cannot decrypt
        }
        return true;
    }

    /** Builds one NCZ entry in tmp: raw 0x4000 header + section table + zstd(decrypted sections). */
    private static void compressNca(ByteSource src, Container.Entry e, Nca n, File tmp,
                                    int level, Progress p) throws IOException {
        List<Nca.Section> secs = new ArrayList<>(n.validSections());
        secs.sort((x, y) -> Long.compare(x.offset, y.offset));
        List<SecRef> refs = new ArrayList<>();
        if (!secs.isEmpty() && secs.get(0).offset > 0x4000) {
            SecRef pad = new SecRef();
            pad.offset = 0x4000;
            pad.size = secs.get(0).offset - 0x4000;
            pad.cryptoType = 1;
            pad.key = n.titleKey;
            pad.sectionIndex = -1;
            refs.add(pad);
        }
        for (Nca.Section s : secs) {
            SecRef r = new SecRef();
            r.offset = s.offset;
            r.size = s.size;
            r.cryptoType = s.cryptoType;
            r.key = n.titleKey;
            r.counter = Util.ctrNonce(s.ctrValue);
            r.sectionIndex = s.index;
            refs.add(r);
        }
        long end = n.ncaSize > 0 ? n.ncaSize : e.size;
        if (end > e.size) end = e.size;

        byte[] head = src.readFully(e.dataStart, 0x4000);
        if (head.length < 0x4000) throw new IOException("NCA too small: " + e.name);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(head);
            byte[] table = new byte[0x10 + refs.size() * 0x40];
            putAscii(table, 0, "NCZSECTN");
            putU64(table, 8, refs.size());
            for (int i = 0; i < refs.size(); i++) {
                int b = 0x10 + i * 0x40;
                SecRef r = refs.get(i);
                putU64(table, b, r.offset);
                putU64(table, b + 8, r.size);
                putU64(table, b + 0x10, r.cryptoType);
                if (r.key != null) System.arraycopy(r.key, 0, table, b + 0x20, 16);
                System.arraycopy(r.counter, 0, table, b + 0x30, 16);
            }
            fos.write(table);

            try (ZstdOutputStream z = new ZstdOutputStream(fos, level)) {
                byte[] buf = new byte[0x10000];
                long pos = 0x4000;
                for (SecRef r : refs) {
                    while (pos < r.offset) {
                        int nbytes = (int) Math.min(buf.length, r.offset - pos);
                        Util.copyRange(src, e.dataStart + pos, nbytes, z);
                        pos += nbytes;
                    }
                    p.onProgress("Compressing " + e.name, pos, end);
                    if (r.sectionIndex >= 0) {
                        ByteSource ss = n.sectionSource(r.sectionIndex);
                        if (ss == null) throw new IOException("cannot decrypt section " + r.sectionIndex + " of " + e.name);
                        long done = 0;
                        while (done < r.size) {
                            int want = (int) Math.min(buf.length, r.size - done);
                            int got = ss.read(done, buf, 0, want);
                            if (got <= 0) throw new IOException("unexpected EOF in " + e.name);
                            z.write(buf, 0, got);
                            done += got;
                            p.onProgress("Compressing " + e.name, r.offset + done, end);
                        }
                    } else {
                        Util.copyRange(src, e.dataStart + r.offset, r.size, z);
                    }
                    pos = r.offset + r.size;
                }
                if (pos < end) Util.copyRange(src, e.dataStart + pos, end - pos, z);
            }
        }
    }

    private static final class SecRef {
        long offset;
        long size;
        long cryptoType;
        byte[] key;
        byte[] counter = new byte[16];
        int sectionIndex;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }

    static long cnmtSize(Cnmt cnmt, String name) {
        String base = name.toLowerCase(Locale.ROOT);
        int dot = base.indexOf('.');
        if (dot < 0) return -1;
        String id = base.substring(0, dot);
        for (Cnmt.Content c : cnmt.contents) {
            if (c.ncaId.toLowerCase(Locale.ROOT).startsWith(id)) return c.size;
        }
        return -1;
    }

    /** Sequential InputStream over a byte range of a ByteSource. */
    public static final class ByteSourceInputStream extends InputStream {
        private final ByteSource src;
        private long pos;
        private final long end;

        public ByteSourceInputStream(ByteSource src, long start, long len) {
            this.src = src;
            this.pos = start;
            this.end = start + len;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) return -1;
            int want = (int) Math.min(len, end - pos);
            int got = src.read(pos, b, off, want);
            if (got <= 0) return -1;
            pos += got;
            return got;
        }

        @Override
        public long skip(long n) throws IOException {
            long take = Math.min(n, end - pos);
            pos += take;
            return take;
        }
    }
}
