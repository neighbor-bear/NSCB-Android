package io.nscb.engine;

import java.io.IOException;

/** Minimal Nintendo ticket parser (used for title keys). */
public final class Ticket {
    public static final int RSA_4096_SHA1 = 0x010000;
    public static final int RSA_2048_SHA1 = 0x010001;
    public static final int ECDSA_SHA1 = 0x010002;
    public static final int RSA_4096_SHA256 = 0x010003;
    public static final int RSA_2048_SHA256 = 0x010004;
    public static final int ECDSA_SHA256 = 0x010005;

    public int signatureType;
    public String issuer;
    public String titleId;
    public String rightsId;
    public String ticketId;
    public byte[] encTitleKey;
    public int masterKeyRevision;
    public String titleKeyHex;
    public String decTitleKeyHex;

    private static int sigSize(int type) {
        switch (type) {
            case RSA_4096_SHA1:
            case RSA_4096_SHA256:
                return 0x200;
            case RSA_2048_SHA1:
            case RSA_2048_SHA256:
                return 0x100;
            case ECDSA_SHA1:
            case ECDSA_SHA256:
                return 0x3C;
            default:
                return -1;
        }
    }

    public static Ticket parse(ByteSource src, long pos, long available, Keys keys) throws IOException {
        byte[] head = src.readFully(pos, 0x180);
        if (head.length < 0x180) throw new IOException("truncated ticket");
        int type = Util.i32(head, 0);
        int sig = sigSize(type);
        if (sig < 0) throw new IOException("unknown ticket signature type " + Integer.toHexString(type));
        int pad = 0x40 - ((sig + 4) % 0x40);
        long base = pos + 4 + sig + pad;
        byte[] body = src.readFully(base, 0x180);
        if (body.length < 0x180) throw new IOException("truncated ticket body");

        Ticket t = new Ticket();
        t.signatureType = type;
        t.issuer = new String(body, 0, 0x40, java.nio.charset.StandardCharsets.US_ASCII).trim();
        t.encTitleKey = new byte[16];
        System.arraycopy(body, 0x40, t.encTitleKey, 0, 16);
        t.titleKeyHex = Util.hex(t.encTitleKey);
        t.masterKeyRevision = Util.u8(body, 0x146);
        t.ticketId = Util.hex(slice(body, 0x150, 8));
        t.rightsId = Util.hex(slice(body, 0x160, 16));
        t.titleId = t.rightsId.substring(0, 16);
        if (keys != null && t.masterKeyRevision >= 0) {
            byte[] kek = keys.titleKek(t.masterKeyRevision);
            if (kek != null) {
                try {
                    t.decTitleKeyHex = Util.hex(Aes.ecbDecrypt(kek, t.encTitleKey));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return t;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }
}
