package io.nscb.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class FolderSink implements Sink {
    private final File root;

    public FolderSink(File root) {
        this.root = root;
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("cannot create " + root);
        }
    }

    @Override
    public OutputStream open(String relativePath) throws IOException {
        File f = new File(root, relativePath);
        File p = f.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) throw new IOException("cannot create " + p);
        return new FileOutputStream(f);
    }
}
