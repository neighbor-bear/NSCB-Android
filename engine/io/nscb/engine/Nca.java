package io.nscb.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Nintendo Content Archive (NCA) reader: header, key area, sections, PFS0 payloads. */
public final class Nca {
    public static final int PROGRAM = 0;
    public static final int META = 1;
    public static final int CONTROL = 2;
    public static final int MANUAL = 3;
    public static final int DATA = 4;
    public static final int PUBLIC_DATA = 5;

    private final ByteSource src;
    private final long base;
    private final long fileSize;
    private final Keys keys;

    public boolean headerOk;
    public String magic = "";
    public int isGameCard;
    public int contentType;
    public int cryptoType;
    public int keyIndex;
    public long ncaSize;
    public String titleId = "";
    public int contentIndex;
    public int sdkVersion;
    public int cryptoType2;
    public String rightsId = "";
    public int masterKeyIndex;
    public byte[][] areaKeys = new byte[4][];
    public byte[] titleKey;
    public String decryptError = "";
    public Ticket ticket;

    private byte[] rawHeader;
    private final long[] sectionMediaOffset = new long[4];
    private final long[] sectionMediaEnd = new long[4];
    private final Section[] sections = new Section[4];

    public static final class Section {
        public int index;
        public long offset;     // relative to NCA start
        public long size;
        public int fsType;
        public int cryptoType;
        public long sectionStart;
        public long payloadSize;
        public long ctrValue;
    }

    public Nca(ByteSource src, long base, long fileSize, Keys keys) throws IOException {
        this.src = src;
        this.base = base;
        this.fileSize = fileSize;
        this.keys = keys;
        openHeader();
    }

    public long fileSize() {
        return fileSize;
    }

    public void setTicket(Ticket t) {
        this.ticket = t;
        if (t != null && t.decTitleKeyHex != null && headerOk) {
            byte[] dk = Util.unhex(t.decTitleKeyHex);
            if (!t.rightsId.equals("00000000000000000000000000000000")) {
                titleKey = dk;
                rebuildSections();
            }
        }
    }

    private void openHeader() throws IOException {
        byte[] raw = src.readFully(base, (int) Math.min(0xC00, fileSize));
        if (raw.length < 0xC00) {
            decryptError = "NCA header truncated";
            return;
        }
        byte[] hdr = null;
        if (keys != null && keys.has("header_key")) {
            try {
                hdr = new Aes.Xts(keys.get("header_key")).decrypt(0, raw);
                if (magicAt(hdr).equals("NCA3") || magicAt(hdr).equals("NCA2") || magicAt(hdr).equals("NCA0")) {
                    rawHeader = hdr;
                }
            } catch (RuntimeException e) {
                decryptError = e.getMessage();
            }
        }
        if (rawHeader == null && (magicAt(raw).equals("NCA0") || magicAt(raw).equals("NCA3") || magicAt(raw).equals("NCA2"))) {
            rawHeader = raw;
        }
        if (rawHeader == null) {
            headerOk = false;
            decryptError = keys == null || !keys.has("header_key")
                    ? "NCA header is encrypted. Import prod.keys to read it."
                    : "Failed to decrypt NCA header (wrong header_key?).";
            return;
        }
        headerOk = true;
        rawHeader = rawHeader; // decrypted 0xC00
        magic = magicAt(rawHeader);
        isGameCard = Util.u8(rawHeader, 0x204);
        contentType = Util.u8(rawHeader, 0x205);
        cryptoType = Util.u8(rawHeader, 0x206);
        keyIndex = Util.u8(rawHeader, 0x207);
        ncaSize = Util.u64(rawHeader, 0x208);
        titleId = Util.titleId(rawHeader, 0x210);
        contentIndex = Util.i32(rawHeader, 0x218);
        sdkVersion = Util.i32(rawHeader, 0x21C);
        cryptoType2 = Util.u8(rawHeader, 0x220);
        rightsId = Util.hex(slice(rawHeader, 0x230, 16));
        masterKeyIndex = Math.max(0, Math.max(cryptoType, cryptoType2) - 1);

        for (int i = 0; i < 4; i++) {
            int p = 0x240 + i * 0x10;
            sectionMediaOffset[i] = Util.u32(rawHeader, p);
            sectionMediaEnd[i] = Util.u32(rawHeader, p + 4);
        }

        if (keys != null) {
            byte[] areaKey = keys.areaKey(masterKeyIndex, keyIndex);
            if (areaKey != null) {
                byte[] block = Aes.ecbDecrypt(areaKey, slice(rawHeader, 0x300, 0x40));
                for (int i = 0; i < 4; i++) {
                    areaKeys[i] = slice(block, i * 16, 16);
                }
            }
        }

        rebuildSections();
        resolveTitleKey();
    }

    private void resolveTitleKey() {
        if (rightsId.equals("00000000000000000000000000000000")) {
            titleKey = areaKeys[2];
            return;
        }
        if (titleKey != null) return; // from ticket
        if (ticket != null && ticket.decTitleKeyHex != null) {
            titleKey = Util.unhex(ticket.decTitleKeyHex);
        } else if (ticket != null && ticket.encTitleKey != null && keys != null) {
            try {
                titleKey = keys.decryptTitleKey(ticket.encTitleKey, masterKeyIndex);
            } catch (RuntimeException e) {
                decryptError = "Missing titlekek for master key " + masterKeyIndex;
            }
        } else if (keys != null && keys.titleKek(masterKeyIndex) != null) {
            decryptError = "Title rights NCA: ticket (.tik) is required to obtain the title key.";
        } else {
            decryptError = "Title rights NCA: missing title key (import title.keys/ticket).";
        }
    }

    private void rebuildSections() {
        for (int i = 0; i < 4; i++) pfsCache[i] = null;
        for (int i = 0; i < 4; i++) {
            Section s = new Section();
            s.index = i;
            s.offset = sectionMediaOffset[i] * 0x200L;
            s.size = (sectionMediaEnd[i] - sectionMediaOffset[i]) * 0x200L;
            int p = 0x400 + i * 0x200;
            s.fsType = Util.u8(rawHeader, p + 3);
            s.cryptoType = Util.u8(rawHeader, p + 4);
            s.sectionStart = Util.u64(rawHeader, p + 0x40);
            s.payloadSize = Util.u64(rawHeader, p + 0x48);
            s.ctrValue = Util.u64(rawHeader, p + 0x140);
            if (s.offset < 0 || s.size <= 0 || s.offset >= fileSize) {
                s.size = 0;
            }
            if (s.size > fileSize - s.offset) s.size = fileSize - s.offset;
            sections[i] = s;
        }
    }

    public Section section(int i) {
        return sections[i];
    }

    public List<Section> validSections() {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) {
            if (s.size > 0 && s.fsType != 0) out.add(s);
        }
        return out;
    }

    public String contentTypeName() {
        switch (contentType) {
            case PROGRAM:
                return "Program";
            case META:
                return "Meta (CNMT)";
            case CONTROL:
                return "Control (NACP)";
            case MANUAL:
                return "Manual";
            case DATA:
                return "Data (RomFS)";
            case PUBLIC_DATA:
                return "PublicData";
            default:
                return "Unknown(" + contentType + ")";
        }
    }

    public String cryptoName() {
        return cryptoType == 1 ? "None" : cryptoType == 2 ? "XTS" : cryptoType == 3 ? "CTR" : cryptoType == 4 ? "BKTR" : ("Type " + cryptoType);
    }

    public byte[] readHeaderRegion() {
        return rawHeader == null ? new byte[0] : rawHeader;
    }

    /** Byte source over one decrypted section. */
    public ByteSource sectionSource(int index) {
        Section s = sections[index];
        if (s.size <= 0) return null;
        if (s.cryptoType == 1) return new RawSectionSource(s);
        if (titleKey == null) return null;
        return new CryptSectionSource(s);
    }

    private final class RawSectionSource implements ByteSource {
        private final Section s;

        RawSectionSource(Section s) {
            this.s = s;
        }

        @Override
        public long size() {
            return s.size;
        }

        @Override
        public int read(long pos, byte[] dst, int off, int len) throws IOException {
            if (pos >= s.size) return -1;
            int n = (int) Math.min(len, s.size - pos);
            return src.read(base + s.offset + pos, dst, off, n);
        }
    }

    private final class CryptSectionSource implements ByteSource {
        private final Section s;
        private final Aes.Ctr ctr;

        CryptSectionSource(Section s) {
            this.s = s;
            byte[] nonce = Util.ctrNonce(s.ctrValue);
            this.ctr = new Aes.Ctr(titleKey, nonce);
        }

        @Override
        public long size() {
            return s.size;
        }

        @Override
        public int read(long pos, byte[] dst, int off, int len) throws IOException {
            if (pos >= s.size) return -1;
            int n = (int) Math.min(len, s.size - pos);
            int got = src.read(base + s.offset + pos, dst, off, n);
            if (got > 0) ctr.process(s.offset + pos, dst, off, got);
            return got;
        }
    }

    public Container pfs0(int sectionIndex) throws IOException {
        Section s = sections[sectionIndex];
        if (s.fsType != 2) return null;
        if (pfsCache[sectionIndex] != null) return pfsCache[sectionIndex];
        ByteSource ss = sectionSource(sectionIndex);
        if (ss == null) return null;
        Container c = Container.parse(ss, s.sectionStart, s.size - s.sectionStart, Container.Kind.PFS0);
        pfsCache[sectionIndex] = c;
        return c;
    }

    /** A file entry together with the PFS0 partition that owns it. */
    public static final class OwnedEntry {
        public final Container container;
        public final Container.Entry entry;
        public final int sectionIndex;

        OwnedEntry(Container container, Container.Entry entry, int sectionIndex) {
            this.container = container;
            this.entry = entry;
            this.sectionIndex = sectionIndex;
        }
    }

    private final Container[] pfsCache = new Container[4];

    public List<Container.Entry> allPfsEntries() throws IOException {
        List<Container.Entry> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Container c = pfs0(i);
            if (c != null) out.addAll(c.entries);
        }
        return out;
    }

    public OwnedEntry findOwned(String name) throws IOException {
        for (int i = 0; i < 4; i++) {
            Container c = pfs0(i);
            if (c == null) continue;
            Container.Entry e = c.find(name);
            if (e != null) return new OwnedEntry(c, e, i);
        }
        return null;
    }

    public Container.Entry findFile(String name) throws IOException {
        OwnedEntry o = findOwned(name);
        return o == null ? null : o.entry;
    }

    public byte[] readFile(OwnedEntry o, long maxBytes) throws IOException {
        ByteSource ss = sectionSource(o.sectionIndex);
        if (ss == null) throw new IOException("section " + o.sectionIndex + " cannot be decrypted (missing title key)");
        long len = Math.min(o.entry.size, maxBytes);
        byte[] out = new byte[(int) len];
        int got = 0;
        while (got < len) {
            int n = ss.read(o.entry.dataStart + got, out, got, (int) (len - got));
            if (n <= 0) break;
            got += n;
        }
        if (got < len) {
            byte[] shorter = new byte[got];
            System.arraycopy(out, 0, shorter, 0, got);
            return shorter;
        }
        return out;
    }

    public byte[] readFile(Container.Entry e, long maxBytes) throws IOException {
        OwnedEntry o = findOwned(e.name);
        if (o == null) throw new IOException("file entry not found in any PFS0 section: " + e.name);
        return readFile(o, maxBytes);
    }

    public void extractFile(OwnedEntry o, OutputStream out) throws IOException {
        ByteSource ss = sectionSource(o.sectionIndex);
        if (ss == null) throw new IOException("section " + o.sectionIndex + " cannot be decrypted");
        Util.copyRange(ss, o.entry.dataStart, o.entry.size, out);
    }

    public void extractFile(Container.Entry e, OutputStream out) throws IOException {
        OwnedEntry o = findOwned(e.name);
        if (o == null) throw new IOException("file entry not found in any PFS0 section: " + e.name);
        extractFile(o, out);
    }

    public Nacp readNacp() throws IOException {
        Container.Entry e = findFile("control.nacp");
        if (e == null) return null;
        byte[] b = readFile(e, Math.min(e.size, 0x4000));
        return Nacp.parse(b);
    }

    public byte[] readIcon() throws IOException {
        if (!headerOk) return null;
        for (Container.Entry e : allPfsEntries()) {
            if (e.name.startsWith("icon_") && e.name.endsWith(".dat")) {
                return readFile(e, Math.min(e.size, 0x100000));
            }
        }
        return null;
    }

    public Cnmt readCnmt() throws IOException {
        if (!headerOk) return null;
        Container.Entry e = findFile("Application.cnmt");
        if (e == null) e = findFile("Patch.cnmt");
        if (e == null) {
            for (Container.Entry x : allPfsEntries()) {
                if (x.name.endsWith(".cnmt")) {
                    e = x;
                    break;
                }
            }
        }
        if (e == null) return null;
        byte[] b = readFile(e, Math.min(e.size, 0x100000));
        return Cnmt.parse(new MemorySource(b), 0, b.length);
    }

    public void extractContents(OutputStream[] unused) {
    }

    private static String magicAt(byte[] b) {
        if (b.length < 0x204) return "";
        return new String(b, 0x200, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!headerOk) {
            sb.append("NCA (encrypted / unreadable)\n");
            sb.append("Reason: ").append(decryptError).append('\n');
            sb.append("Size: ").append(Util.size(fileSize)).append('\n');
            return sb.toString();
        }
        sb.append("NCA ").append(magic).append('\n');
        sb.append("Title ID: ").append(titleId).append('\n');
        sb.append("Content type: ").append(contentTypeName()).append('\n');
        sb.append("Crypto type: ").append(cryptoName()).append("  (master key rev ").append(Math.max(cryptoType, cryptoType2)).append(")\n");
        sb.append("Key area index: ").append(keyIndex).append('\n');
        sb.append("Rights ID: ").append(rightsId).append('\n');
        sb.append("Size: ").append(Util.size(ncaSize)).append('\n');
        sb.append("SDK version: ").append(String.format("0x%08X", sdkVersion)).append('\n');
        for (Section s : sections) {
            if (s.size == 0) continue;
            sb.append(String.format("Section %d: fs=%s crypto=%s offset=0x%X size=%s", s.index,
                    s.fsType == 2 ? "PFS0" : s.fsType == 3 ? "RomFS" : ("type " + s.fsType),
                    s.cryptoType == 1 ? "none" : s.cryptoType == 3 ? "CTR" : ("type " + s.cryptoType),
                    s.offset, Util.size(s.size)));
            sb.append('\n');
        }
        if (titleKey == null) sb.append(decryptError).append('\n');
        return sb.toString();
    }
}
