package io.nscb.engine;

import java.io.IOException;

/** A read-only window into a region of another ByteSource. */
public final class SliceSource implements ByteSource {
    private final ByteSource base;
    private final long offset;
    private final long length;

    public SliceSource(ByteSource base, long offset, long length) {
        this.base = base;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public int read(long pos, byte[] dst, int off, int len) throws IOException {
        if (pos < 0) throw new IOException("negative position");
        if (pos >= length) return -1;
        int max = (int) Math.min(length - pos, len);
        return base.read(offset + pos, dst, off, max);
    }
}
