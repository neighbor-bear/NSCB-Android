package io.nscb.engine;

import java.io.IOException;

/** NACP metadata parser (title names, publishers, display version, icon names). */
public final class Nacp {
    public static final String[] LANGUAGES = {
            "American English", "British English", "Japanese", "French", "German",
            "Latin American Spanish", "Spanish", "Italian", "Dutch", "Canadian French",
            "Portuguese", "Russian", "Korean", "Traditional Chinese", "Simplified Chinese"
    };

    public final String[] names = new String[15];
    public final String[] publishers = new String[15];
    public String displayVersion = "";
    public String isbn = "";
    public long addOnContentBaseId;
    public String logoType = "";
    public String attribute = "";

    public static Nacp parse(byte[] b) throws IOException {
        if (b.length < 0x3240) throw new IOException("NACP too small");
        Nacp n = new Nacp();
        for (int i = 0; i < 15; i++) {
            n.names[i] = str(b, i * 0x300, 0x200);
            n.publishers[i] = str(b, i * 0x300 + 0x200, 0x100);
        }
        n.isbn = str(b, 0x3000, 0x24);
        int attr = Util.u8(b, 0x3028);
        n.attribute = attr == 0 ? "None" : attr == 1 ? "Demo" : attr == 2 ? "RetailInteractiveDisplay" : "Unknown";
        n.displayVersion = str(b, 0x3060, 0xF);
        n.addOnContentBaseId = Util.u64(b, 0x3070);
        int logo = Util.u8(b, 0x30F0);
        n.logoType = logo == 0 ? "LicensedByNintendo" : logo == 2 ? "Nintendo" : "Unknown";
        return n;
    }

    private static String str(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }
}
