package io.nscb.engine;

public class MemorySource implements ByteSource {
    private final byte[] b;

    public MemorySource(byte[] b) {
        this.b = b;
    }

    @Override
    public long size() {
        return b.length;
    }

    @Override
    public int read(long pos, byte[] dst, int off, int len) {
        if (pos >= b.length) return -1;
        int n = (int) Math.min(len, b.length - pos);
        System.arraycopy(b, (int) pos, dst, off, n);
        return n;
    }
}
