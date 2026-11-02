package io.nscb.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class FileChannelSource implements ByteSource {
    private final FileChannel ch;
    private final long size;

    public FileChannelSource(FileChannel ch) throws IOException {
        this.ch = ch;
        this.size = ch.size();
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int read(long pos, byte[] dst, int off, int len) throws IOException {
        if (pos >= size) return -1;
        ByteBuffer bb = ByteBuffer.wrap(dst, off, len);
        return ch.read(bb, pos);
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
