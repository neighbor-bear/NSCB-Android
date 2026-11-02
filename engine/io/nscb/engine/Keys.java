package io.nscb.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * prod.keys loader and Switch key derivation (titlekek / key area keys).
 * No keys are bundled with the app; the user must supply their own prod.keys.
 */
public final class Keys {
    private static final Pattern LINE = Pattern.compile("\\s*([a-zA-Z0-9_]+)\\s*=\\s*([A-Fa-f0-9]+)\\s*");

    private final Map<String, byte[]> keys = new HashMap<>();
    private byte[][] titleKeks = new byte[20][];
    private byte[][][] keyAreaKeys = new byte[20][3][];

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public int size() {
        return keys.size();
    }

    public boolean has(String name) {
        return keys.containsKey(name);
    }

    public byte[] get(String name) {
        return keys.get(name);
    }

    public static Keys fromFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return fromStream(in);
        }
    }

    public static Keys fromStream(InputStream in) throws IOException {
        Keys k = new Keys();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1).toLowerCase(Locale.ROOT);
            String val = m.group(2);
            if (name.startsWith("master_key_")) {
                String num = name.substring("master_key_".length());
                try {
                    int n = Integer.parseInt(num, 16);
                    name = String.format(Locale.ROOT, "master_key_%02d", n);
                } catch (NumberFormatException ignored) {
                }
            }
            k.keys.put(name, hex(val));
        }
        if (k.keys.containsKey("master_key_16") && !k.keys.containsKey("master_key_10")) {
            k.keys.put("master_key_10", k.keys.get("master_key_16"));
            k.keys.remove("master_key_16");
        }
        k.derive();
        return k;
    }

    private void derive() {
        byte[] aesKekSource = keys.get("aes_kek_generation_source");
        byte[] aesKeySource = keys.get("aes_key_generation_source");
        byte[] titlekekSource = keys.get("titlekek_source");
        byte[] appSource = keys.get("key_area_key_application_source");
        byte[] oceanSource = keys.get("key_area_key_ocean_source");
        byte[] systemSource = keys.get("key_area_key_system_source");
        if (aesKekSource == null || aesKeySource == null) return;

        titleKeks = new byte[20][];
        keyAreaKeys = new byte[20][3][];
        for (int i = 0; i < 20; i++) {
            byte[] mk = keys.get(String.format(Locale.ROOT, "master_key_%02d", i));
            if (mk == null) continue;
            if (titlekekSource != null) {
                titleKeks[i] = Aes.ecbDecrypt(mk, titlekekSource);
            }
            if (appSource != null) keyAreaKeys[i][0] = generateKek(appSource, mk, aesKekSource, aesKeySource);
            if (oceanSource != null) keyAreaKeys[i][1] = generateKek(oceanSource, mk, aesKekSource, aesKeySource);
            if (systemSource != null) keyAreaKeys[i][2] = generateKek(systemSource, mk, aesKekSource, aesKeySource);
        }
    }

    /** Matches NSCB generateKek(): three AES-ECB decrypts. */
    public static byte[] generateKek(byte[] src, byte[] masterKey, byte[] kekSeed, byte[] keySeed) {
        byte[] kek = Aes.ecbDecrypt(masterKey, kekSeed);
        byte[] srcKek = Aes.ecbDecrypt(kek, src);
        if (keySeed == null) return srcKek;
        return Aes.ecbDecrypt(srcKek, keySeed);
    }

    public byte[] areaKey(int masterKeyIndex, int keyType) {
        if (masterKeyIndex < 0 || masterKeyIndex >= 20) return null;
        if (keyType < 0 || keyType >= 3) keyType = 0;
        return keyAreaKeys[masterKeyIndex][keyType];
    }

    public byte[] titleKek(int masterKeyIndex) {
        if (masterKeyIndex < 0 || masterKeyIndex >= 20) return null;
        return titleKeks[masterKeyIndex];
    }

    public byte[] decryptTitleKey(byte[] encTitleKey, int masterKeyIndex) {
        byte[] kek = titleKek(masterKeyIndex);
        if (kek == null) throw new IllegalStateException("titlekek for master key " + masterKeyIndex + " missing");
        return Aes.ecbDecrypt(kek, encTitleKey);
    }

    public boolean canDecryptHeaders() {
        return keys.containsKey("header_key");
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] b = new byte[n / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
