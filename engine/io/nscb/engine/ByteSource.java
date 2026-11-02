package io.nscb.engine;

import java.io.IOException;

/** Random-access read-only byte source. */
public interface ByteSource extends AutoCloseable {
    long size() throws IOException;

    /** Read up to len bytes at absolute position; returns bytes read or -1 at EOF. */
    int read(long pos, byte[] dst, int off, int len) throws IOException;

    default byte[] readFully(long pos, int len) throws IOException {
        byte[] out = new byte[len];
        int got = 0;
        while (got < len) {
            int n = read(pos + got, out, got, len - got);
            if (n <= 0) break;
            got += n;
        }
        if (got < len) {
            byte[] part = new byte[got];
            System.arraycopy(out, 0, part, 0, got);
            return part;
        }
        return out;
    }

    @Override
    default void close() throws IOException {
    }
}
