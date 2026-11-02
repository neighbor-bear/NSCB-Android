package io.nscb.engine;

import java.io.IOException;
import java.io.OutputStream;

/** Destination abstraction used for extraction / packing (folder or SAF tree). */
public interface Sink {
    OutputStream open(String relativePath) throws IOException;

    default void finish() throws IOException {
    }
}
