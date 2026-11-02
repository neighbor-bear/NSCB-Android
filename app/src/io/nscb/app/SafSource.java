package io.nscb.app;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import io.nscb.engine.ByteSource;

/** Read-only ByteSource over a SAF document, backed by a FileChannel. */
final class SafSource implements ByteSource {
    private final ParcelFileDescriptor pfd;
    private final FileInputStream stream;
    private final FileChannel channel;
    private final long size;

    SafSource(ContentResolver cr, Uri uri) throws IOException {
        pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("无法打开文件: " + uri);
        stream = new FileInputStream(pfd.getFileDescriptor());
        channel = stream.getChannel();
        size = channel.size();
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int read(long pos, byte[] dst, int off, int len) throws IOException {
        if (pos >= size) return -1;
        ByteBuffer bb = ByteBuffer.wrap(dst, off, len);
        return channel.read(bb, pos);
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            try {
                stream.close();
            } finally {
                pfd.close();
            }
        }
    }
}
