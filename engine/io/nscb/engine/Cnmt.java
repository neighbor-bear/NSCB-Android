package io.nscb.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** CNMT metadata (inside the meta NCA). */
public final class Cnmt {
    public String titleId;
    public long version;
    public int titleType;
    public long requiredSystemVersion;
    public final List<Content> contents = new ArrayList<>();
    public final List<Meta> metas = new ArrayList<>();

    public static final class Content {
        public byte[] hash;
        public String ncaId;
        public long size;
        public int type;
    }

    public static final class Meta {
        public String titleId;
        public long version;
        public int type;
        public int install;
    }

    public static Cnmt parse(ByteSource src, long pos, long len) throws IOException {
        byte[] h = src.readFully(pos, (int) Math.min(len, 0x80));
        if (h.length < 0x20) throw new IOException("truncated cnmt");
        Cnmt c = new Cnmt();
        c.titleId = Util.titleId(h, 0);
        c.version = Util.u32(h, 8);
        c.titleType = Util.u8(h, 12);
        int headerOffset = Util.u16(h, 14);
        int contentCount = Util.u16(h, 16);
        int metaCount = Util.u16(h, 18);
        c.requiredSystemVersion = Util.u32(h, 0x28);

        long table = pos + 0x20 + headerOffset;
        for (int i = 0; i < contentCount; i++) {
            byte[] e = src.readFully(table + (long) i * 0x38, 0x38);
            if (e.length < 0x38) throw new IOException("truncated cnmt content entry");
            Cnmt.Content ce = new Cnmt.Content();
            ce.hash = new byte[0x20];
            System.arraycopy(e, 0, ce.hash, 0, 0x20);
            ce.ncaId = Util.hex(slice(e, 0x20, 0x10));
            ce.size = Util.u48(e, 0x30);
            ce.type = Util.u8(e, 0x36);
            c.contents.add(ce);
        }
        for (int i = 0; i < metaCount; i++) {
            byte[] e = src.readFully(table + (long) contentCount * 0x38 + (long) i * 0x10, 0x10);
            if (e.length < 0x10) throw new IOException("truncated cnmt meta entry");
            Cnmt.Meta me = new Cnmt.Meta();
            me.titleId = Util.titleId(e, 0);
            me.version = Util.u32(e, 8);
            me.type = Util.u8(e, 12);
            me.install = Util.u8(e, 13);
            c.metas.add(me);
        }
        return c;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    public static String typeName(int type) {
        switch (type) {
            case 0:
                return "SystemProgram";
            case 1:
                return "SystemData";
            case 2:
                return "SystemUpdate";
            case 3:
                return "BootImagePackage";
            case 4:
                return "BootImagePackageSafe";
            case 5:
                return "Application";
            case 6:
                return "Patch";
            case 7:
                return "AddOnContent";
            case 8:
                return "Delta";
            default:
                return "Unknown(" + type + ")";
        }
    }
}
