package io.nscb.engine;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** AES helpers: ECB, CTR (Switch NCA counter layout) and XTS (sector tweak). */
public final class Aes {
    private Aes() {
    }

    private static Cipher cipher(byte[] key, int mode) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("bad AES key length: " + key.length);
            }
            c.init(mode, new SecretKeySpec(key, "AES"));
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    public static byte[] ecbEncrypt(byte[] key, byte[] block16) {
        try {
            return cipher(key, Cipher.ENCRYPT_MODE).doFinal(block16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] ecbDecrypt(byte[] key, byte[] data) {
        try {
            return cipher(key, Cipher.DECRYPT_MODE).doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Switch AES-CTR: 16-byte counter block is nonce[0..8] followed by big-endian
     * block counter. Bytes are processed starting at absolute offset, where
     * counter = absoluteOffset / 16.
     */
    public static final class Ctr {
        private final byte[] key;
        private final byte[] prefix;
        private final byte[] counter = new byte[16];
        private long block;

        public Ctr(byte[] key, byte[] nonce16) {
            if (key == null || key.length != 16) throw new IllegalArgumentException("CTR key must be 16 bytes");
            if (nonce16 == null || nonce16.length != 16) throw new IllegalArgumentException("CTR nonce must be 16 bytes");
            this.key = key;
            this.prefix = new byte[8];
            System.arraycopy(nonce16, 0, prefix, 0, 8);
        }

        public void seek(long absoluteOffset) {
            block = absoluteOffset >>> 4;
        }

        /** Process len bytes located at absoluteOffset (already known by caller). */
        public void process(long absoluteOffset, byte[] data, int off, int len) {
            long blk = absoluteOffset >>> 4;
            int skip = (int) (absoluteOffset & 0xF);
            byte[] stream = new byte[16];
            Cipher c = cipher(key, Cipher.ENCRYPT_MODE);
            int i = 0;
            while (i < len) {
                setCounter(blk);
                try {
                    stream = c.doFinal(counter);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                int start = skip;
                int use = Math.min(16 - start, len - i);
                for (int k = 0; k < use; k++) {
                    data[off + i + k] ^= stream[start + k];
                }
                i += use;
                blk++;
                skip = 0;
            }
            this.block = blk;
        }

        private void setCounter(long blk) {
            System.arraycopy(prefix, 0, counter, 0, 8);
            for (int j = 0; j < 8; j++) {
                counter[15 - j] = (byte) ((blk >>> (8 * j)) & 0xFF);
            }
        }
    }

    /** AES-XTS over 0x200-byte sectors, used for NCA headers. */
    public static final class Xts {
        private final byte[] k1;
        private final byte[] k2;

        public Xts(byte[] key32) {
            if (key32 == null || key32.length != 32) throw new IllegalArgumentException("XTS key must be 32 bytes");
            k1 = new byte[16];
            k2 = new byte[16];
            System.arraycopy(key32, 0, k1, 0, 16);
            System.arraycopy(key32, 16, k2, 0, 16);
        }

        public byte[] decrypt(long firstSector, byte[] data) {
            return process(firstSector, data, false);
        }

        public byte[] encrypt(long firstSector, byte[] data) {
            return process(firstSector, data, true);
        }

        private byte[] process(long firstSector, byte[] data, boolean enc) {
            if (data.length % 16 != 0) throw new IllegalArgumentException("XTS data not block aligned");
            byte[] out = new byte[data.length];
            int pos = 0;
            long sector = firstSector;
            while (pos < data.length) {
                int n = Math.min(0x200, data.length - pos);
                byte[] tweak = tweakFor(sector);
                for (int b = 0; b < n; b += 16) {
                    byte[] in = new byte[16];
                    for (int j = 0; j < 16; j++) {
                        in[j] = (byte) (data[pos + b + j] ^ tweak[j]);
                    }
                    byte[] r = enc ? ecbEncrypt(k1, in) : ecbDecrypt(k1, in);
                    for (int j = 0; j < 16; j++) out[pos + b + j] = (byte) (r[j] ^ tweak[j]);
                    tweak = mulX(tweak);
                }
                sector++;
                pos += n;
            }
            return out;
        }

        private byte[] tweakFor(long sector) {
            byte[] raw = new byte[16];
            long s = sector;
            for (int i = 0; i < 8; i++) {
                raw[i] = (byte) (s & 0xFF);
                s >>>= 8;
            }
            return ecbEncrypt(k2, raw);
        }
    }

    /** Little-endian 128-bit multiply-by-x used by XTS. */
    private static byte[] mulX(byte[] t) {
        byte[] out = new byte[16];
        int carry = 0;
        for (int i = 0; i < 16; i++) {
            int v = t[i] & 0xFF;
            out[i] = (byte) (((v << 1) & 0xFF) | carry);
            carry = (v >>> 7) & 1;
        }
        if (carry != 0) out[0] ^= (byte) 0x87;
        return out;
    }
}
