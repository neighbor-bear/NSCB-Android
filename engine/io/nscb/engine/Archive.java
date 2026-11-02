package io.nscb.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Container-level reader for NSP / XCI / NSZ / XCZ / standalone NCA. */
public final class Archive implements AutoCloseable {
    public enum Type { NSP, XCI, NSZ, XCZ, NCA, UNKNOWN }

    public final Type type;
    public final String fileName;
    public final ByteSource src;
    public final long size;
    public final Keys keys;

    public Container pfs;         // NSP PFS0
    public Container root;        // XCI root HFS0
    public Container secure;      // XCI secure HFS0
    public Container update;
    public Container normal;
    public XciHeader xci;
    public Ticket ticket;
    public Nca nca;               // standalone
    public final List<NcaEntry> ncas = new ArrayList<>();
    public Cnmt cnmt;
    public Nacp nacp;
    public byte[] icon;
    public String titleId = "";
    public long version = -1;
    public String titleType = "";
    public long requiredSystemVersion = -1;
    public String nacpName = "";
    public String nacpPublisher = "";
    public String displayVersion = "";
    public String note = "";

    public static final class NcaEntry {
        public String name;
        public long size;
        public Nca nca;
        public String partition;
    }

    public static final class XciHeader {
        public String magic = "";
        public long secureOffset;
        public long backupOffset;
        public int titleKekIndex;
        public int gamecardSize;
        public int headerVersion;
        public int flags;
        public long packageId;
        public long validDataEnd;
        public long hfs0Offset;
        public long hfs0HeaderSize;
        public int secureMode;
        public int titleKeyFlag;
        public int keyFlag;
        public long firmwareVersion;
    }

    private Archive(Type type, String fileName, ByteSource src, long size, Keys keys) {
        this.type = type;
        this.fileName = fileName;
        this.src = src;
        this.size = size;
        this.keys = keys;
    }

    public static Archive open(ByteSource src, String fileName, Keys keys) throws IOException {
        long size = src.size();
        String lower = Util.normalizeExt(fileName);
        byte[] head = src.readFully(0, (int) Math.min(0x300, size));
        String magic0 = len(head, 0) >= 4 ? new String(head, 0, 4, java.nio.charset.StandardCharsets.US_ASCII) : "";
        String magic100 = len(head, 0x100) >= 4 ? new String(head, 0x100, 4, java.nio.charset.StandardCharsets.US_ASCII) : "";

        Type type;
        if (magic0.equals("PFS0")) {
            type = lower.endsWith(".nsz") ? Type.NSZ : Type.NSP;
        } else if (magic100.equals("HEAD")) {
            type = lower.endsWith(".xcz") ? Type.XCZ : Type.XCI;
        } else if (magicAt(head, 0x200).equals("NCA3") || magicAt(head, 0x200).equals("NCA2")
                || magicAt(head, 0x200).equals("NCA0") || lower.endsWith(".nca") || lower.endsWith(".ncz")) {
            type = Type.NCA;
        } else {
            type = Type.UNKNOWN;
        }

        Archive a = new Archive(type, fileName, src, size, keys);
        switch (type) {
            case NSP:
            case NSZ:
                a.pfs = Container.parse(src, 0, size, Container.Kind.PFS0);
                a.readNsp();
                break;
            case XCI:
            case XCZ:
                a.readXci();
                break;
            case NCA:
                a.readStandaloneNca();
                break;
            default:
                throw new IOException("Unsupported container (not NSP/XCI/NSZ/XCZ/NCA)");
        }
        a.resolveMetadata();
        return a;
    }

    private static int len(byte[] b, int off) {
        return Math.max(0, b.length - off);
    }

    private static String magicAt(byte[] b, int off) {
        if (b.length < off + 4) return "";
        return new String(b, off, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private void readNsp() throws IOException {
        ticket = findTicket(pfs);
        for (Container.Entry e : pfs.entries) {
            if (isNcaName(e.name)) addNca(e, e.size, "nsp");
        }
        assignTickets();
        if (type == Type.NSZ) {
            int ncz = 0;
            for (Container.Entry e : pfs.entries) if (e.name.endsWith(".ncz")) ncz++;
            note = "NSZ compressed container (" + ncz + " compressed NCA entries).";
        }
    }

    private void readXci() throws IOException {
        byte[] h = src.readFully(0, 0x200);
        if (h.length < 0x200) throw new IOException("truncated XCI header");
        XciHeader x = new XciHeader();
        x.magic = new String(h, 0x100, 4, java.nio.charset.StandardCharsets.US_ASCII);
        x.secureOffset = Util.u32(h, 0x104);
        x.backupOffset = Util.u32(h, 0x108);
        x.titleKekIndex = Util.u8(h, 0x10C);
        x.gamecardSize = Util.u8(h, 0x10D);
        x.headerVersion = Util.u8(h, 0x10E);
        x.flags = Util.u8(h, 0x10F);
        x.packageId = Util.u64(h, 0x110);
        x.validDataEnd = Util.u64(h, 0x118);
        x.hfs0Offset = Util.u64(h, 0x130);
        x.hfs0HeaderSize = Util.u64(h, 0x138);
        x.secureMode = Util.i32(h, 0x180);
        x.titleKeyFlag = Util.i32(h, 0x184);
        x.keyFlag = Util.i32(h, 0x188);
        x.firmwareVersion = Util.u64(h, 0x190);
        xci = x;

        long rootBase = x.hfs0Offset != 0 ? x.hfs0Offset : 0xF000;
        if (rootBase <= 0 || rootBase >= size) throw new IOException("invalid XCI HFS0 offset");
        root = Container.parse(src, rootBase, size - rootBase, Container.Kind.HFS0);
        Container.Entry sec = root.find("secure");
        Container.Entry upd = root.find("update");
        Container.Entry nor = root.find("normal");
        if (sec != null) {
            secure = Container.parse(src, sec.dataStart, sec.size, Container.Kind.HFS0);
            ticket = findTicket(secure);
            for (Container.Entry e : secure.entries) {
                if (isNcaName(e.name)) addNca(e, e.size, "secure");
            }
        }
        if (upd != null) update = Container.parse(src, upd.dataStart, upd.size, Container.Kind.HFS0);
        if (nor != null) normal = Container.parse(src, nor.dataStart, nor.size, Container.Kind.HFS0);
        assignTickets();
        if (type == Type.XCZ) note = "XCZ compressed container.";
    }

    private void readStandaloneNca() throws IOException {
        nca = new Nca(src, 0, size, keys);
        NcaEntry ne = new NcaEntry();
        ne.name = fileName;
        ne.size = size;
        ne.nca = nca;
        ne.partition = "nca";
        ncas.add(ne);
    }

    private void addNca(Container.Entry e, long ncaSize, String partition) {
        NcaEntry ne = new NcaEntry();
        ne.name = e.name;
        ne.size = ncaSize;
        ne.partition = partition;
        long base = e.dataStart;
        try {
            ne.nca = new Nca(src, base, ncaSize, keys);
        } catch (IOException ex) {
            ne.nca = null;
        }
        ncas.add(ne);
    }

    private void assignTickets() {
        if (ticket == null) return;
        for (NcaEntry ne : ncas) {
            if (ne.nca != null) ne.nca.setTicket(ticket);
        }
    }

    private Ticket findTicket(Container c) {
        if (c == null) return null;
        for (Container.Entry e : c.entries) {
            if (e.name.toLowerCase(Locale.ROOT).endsWith(".tik")) {
                try {
                    return Ticket.parse(src, e.dataStart, e.size, keys);
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean isNcaName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".nca") || l.endsWith(".ncz");
    }

    private void resolveMetadata() throws IOException {
        if (nca != null && nca.headerOk) {
            cnmt = safeCnmt(nca);
        }
        for (NcaEntry ne : ncas) {
            if (ne.nca == null || !ne.nca.headerOk) continue;
            if (cnmt == null && (ne.nca.contentType == Nca.META || ne.name.endsWith(".cnmt.nca"))) {
                cnmt = safeCnmt(ne.nca);
            }
        }
        for (NcaEntry ne : ncas) {
            if (ne.nca == null || !ne.nca.headerOk) continue;
            if (ne.nca.contentType == Nca.CONTROL) {
                try {
                    nacp = ne.nca.readNacp();
                    icon = ne.nca.readIcon();
                } catch (IOException ignored) {
                }
                break;
            }
        }

        if (cnmt != null) {
            titleId = cnmt.titleId;
            version = cnmt.version;
            if (cnmt.titleType >= 0) titleType = Cnmt.typeName(cnmt.titleType);
            requiredSystemVersion = cnmt.requiredSystemVersion;
        }
        if (titleId.isEmpty() && ticket != null) titleId = ticket.titleId;
        if (titleId.isEmpty()) titleId = fromFileName(fileName, "[([0-9A-Fa-f]{16})]");
        if (version < 0) {
            String v = fromFileName(fileName, "[v[0-9]+]");
            if (!v.isEmpty()) version = Long.parseLong(v.substring(2));
        }
        if (nacp != null) {
            nacpName = pickName(nacp);
            nacpPublisher = pickPublisher(nacp);
            displayVersion = nacp.displayVersion;
        }
        if (titleId.isEmpty() && !ncas.isEmpty()) {
            for (NcaEntry ne : ncas) {
                if (ne.nca != null && ne.nca.headerOk && !ne.nca.titleId.isEmpty()) {
                    titleId = ne.nca.titleId;
                    break;
                }
            }
        }
    }

    private static Cnmt safeCnmt(Nca n) {
        try {
            return n.readCnmt();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String pickName(Nacp n) {
        for (int i = 0; i < n.names.length; i++) {
            if (n.names[i] != null && !n.names[i].isEmpty()) return n.names[i];
        }
        return "";
    }

    private static String pickPublisher(Nacp n) {
        for (int i = 0; i < n.publishers.length; i++) {
            if (n.publishers[i] != null && !n.publishers[i].isEmpty()) return n.publishers[i];
        }
        return "";
    }

    private static String fromFileName(String name, String regex) {
        Matcher m = Pattern.compile(regex).matcher(name);
        return m.find() ? m.group(0).substring(1) : "";
    }

    public Report report() {
        Report r = new Report();
        r.fileName = fileName;
        r.fileSize = size;
        r.titleId = titleId;
        r.version = version;
        r.titleType = titleType;
        r.requiredSystemVersion = requiredSystemVersion;
        r.nacpName = nacpName;
        r.nacpPublisher = nacpPublisher;
        r.displayVersion = displayVersion;
        r.hasTicket = ticket != null;
        boolean tk = false;
        for (NcaEntry ne : ncas) {
            if (ne.nca != null && ne.nca.titleKey != null) tk = true;
        }
        r.titleKeyAvailable = tk;
        r.icon = icon;
        switch (type) {
            case NSP:
                r.container = "NSP (PFS0)";
                break;
            case NSZ:
                r.container = "NSZ (compressed NSP)";
                break;
            case XCI:
                r.container = "XCI (game card image)";
                break;
            case XCZ:
                r.container = "XCZ (compressed XCI)";
                break;
            case NCA:
                r.container = "NCA";
                break;
            default:
                r.container = "Unknown";
        }
        StringBuilder note = new StringBuilder(this.note);
        if (xci != null) {
            note.append(String.format(Locale.ROOT,
                    "XCI header: secureOffset=0x%X titleKekIndex=%d gamecardSize=%d hfs0=0x%X/0x%X packageId=0x%016X fw=%d",
                    xci.secureOffset, xci.titleKekIndex, xci.gamecardSize, xci.hfs0Offset, xci.hfs0HeaderSize,
                    xci.packageId, xci.firmwareVersion));
        }
        if (nca != null && !nca.headerOk) note.append(nca.decryptError);
        r.note = note.toString().trim();

        List<Container.Entry> list = entryList();
        for (Container.Entry e : list) {
            String kind = "";
            Nca n = findNcaByName(e.name);
            if (n != null && n.headerOk) {
                kind = n.contentTypeName() + ", crypto " + n.cryptoName();
            } else if (e.name.toLowerCase(Locale.ROOT).endsWith(".tik")) {
                kind = "Ticket";
            } else if (e.name.toLowerCase(Locale.ROOT).endsWith(".cert")) {
                kind = "Certificate";
            } else if (e.name.toLowerCase(Locale.ROOT).endsWith(".ncz")) {
                kind = "Compressed NCA";
            } else {
                kind = "File";
            }
            r.contents.add(e.name + "  [" + kind + "]  " + Util.size(e.size));
        }
        return r;
    }

    private List<Container.Entry> entryList() {
        List<Container.Entry> out = new ArrayList<>();
        if (pfs != null) out.addAll(pfs.entries);
        if (secure != null) out.addAll(secure.entries);
        if (nca != null) {
            Container.Entry e = new Container.Entry();
            e.name = fileName;
            e.size = size;
            out.add(e);
        }
        return out;
    }

    private Nca findNcaByName(String name) {
        for (NcaEntry ne : ncas) if (ne.name.equals(name)) return ne.nca;
        return null;
    }

    /** NCA object for an entry name inside this archive (null if not an NCA or unreadable). */
    public Nca ncaFor(String name) {
        return findNcaByName(name);
    }

    /** First readable Program NCA (RomFS lives in these). */
    public Nca programNca() {
        for (NcaEntry ne : ncas) {
            if (ne.nca != null && ne.nca.headerOk && ne.nca.contentType == Nca.PROGRAM) return ne.nca;
        }
        return null;
    }

    /** Extract container payloads. For XCI, secure partition is extracted (plus optional all partitions). */
    public void extract(Sink sink, boolean allPartitions, Progress p) throws IOException {
        if (type == Type.NCA) {
            extractNcaContents(nca, sink, p);
            return;
        }
        if (type == Type.XCI || type == Type.XCZ) {
            p.onProgress("Extracting secure partition", 0, Math.max(1, size));
            extractContainer(secure, "secure/", sink, new long[]{0}, p);
            if (allPartitions) {
                extractContainer(update, "update/", sink, new long[]{0}, p);
                extractContainer(normal, "normal/", sink, new long[]{0}, p);
            }
            return;
        }
        if (pfs != null) {
            extractContainer(pfs, "", sink, new long[]{0}, p);
        }
    }

    private long secureSize() {
        if (secure == null) return 0;
        long t = 0;
        for (Container.Entry e : secure.entries) t += e.size;
        return t;
    }

    private void extractContainer(Container c, String prefix, Sink sink, long[] done, Progress p) throws IOException {
        if (c == null) return;
        long total = 0;
        for (Container.Entry e : c.entries) total += e.size;
        long base = done[0];
        for (Container.Entry e : c.entries) {
            p.onProgress("Extracting " + prefix + e.name, base + done[0], Math.max(1, size));
            try (OutputStream out = sink.open(prefix + Util.sanitize(e.name))) {
                Util.copyRange(src, e.dataStart, e.size, out);
            }
            done[0] += e.size;
        }
        done[0] = base + total;
    }

    private void extractNcaContents(Nca n, Sink sink, Progress p) throws IOException {
        if (n == null || !n.headerOk) throw new IOException(n == null ? "no NCA" : n.decryptError);
        String baseName = fileName;
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        int total = 0;
        for (Nca.Section s : n.validSections()) if (s.fsType == 2) total++;
        if (total == 0) throw new IOException("NCA has no PFS0 sections (RomFS is not extracted in this build)");
        int idx = 0;
        for (Nca.Section s : n.validSections()) {
            if (s.fsType != 2) continue;
            Container c = n.pfs0(s.index);
            if (c == null) continue;
            for (Container.Entry e : c.entries) {
                try (OutputStream out = sink.open(Util.sanitize(baseName) + "/" + Util.sanitize(e.name))) {
                    n.extractFile(e, out);
                }
                idx++;
                p.onProgress("Extracted " + e.name, idx, total);
            }
        }
    }

    /** Simple integrity verification; returns human readable findings. */
    public List<String> verify(Progress p) throws IOException {
        List<String> issues = new ArrayList<>();
        List<Container.Entry> list = entryList();
        for (Container.Entry e : list) {
            if (e.dataStart < 0 || e.size < 0 || e.dataStart + e.size > size) {
                issues.add("ENTRY OUT OF BOUNDS: " + e.name);
            }
        }
        if (cnmt != null) {
            for (Cnmt.Content c : cnmt.contents) {
                Container.Entry match = null;
                for (Container.Entry e : list) {
                    String n = e.name.toLowerCase(Locale.ROOT);
                    if (n.contains(c.ncaId.toLowerCase(Locale.ROOT))) {
                        match = e;
                        break;
                    }
                }
                if (match == null) {
                    issues.add("MISSING NCA from CNMT: " + c.ncaId);
                    continue;
                }
                if (match.size != c.size) {
                    issues.add("SIZE MISMATCH: " + match.name + " header=" + match.size + " cnmt=" + c.size);
                }
                byte[] hash = sha256Range(match.dataStart, match.size);
                if (!Util.hex(hash).equals(Util.hex(c.hash))) {
                    issues.add("HASH MISMATCH: " + match.name);
                }
            }
        }
        for (NcaEntry ne : ncas) {
            if (ne.nca == null) {
                issues.add("UNREADABLE NCA: " + ne.name);
            } else if (!ne.nca.headerOk) {
                issues.add("HEADER NOT DECRYPTED: " + ne.name + " (" + ne.nca.decryptError + ")");
            } else if (ne.nca.titleKey == null) {
                issues.add("NO TITLE KEY: " + ne.name + " (" + ne.nca.decryptError + ")");
            }
        }
        if (issues.isEmpty()) issues.add("OK: structure, sizes and CNMT hashes verified.");
        return issues;
    }

    private byte[] sha256Range(long pos, long len) throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[0x100000];
        long done = 0;
        while (done < len) {
            int n = (int) Math.min(buf.length, len - done);
            int got = src.read(pos + done, buf, 0, n);
            if (got <= 0) break;
            md.update(buf, 0, got);
            done += got;
        }
        return md.digest();
    }

    @Override
    public void close() throws IOException {
        src.close();
    }
}
