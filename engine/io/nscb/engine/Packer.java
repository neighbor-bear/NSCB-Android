package io.nscb.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Builds a PFS0 (NSP) container from a set of input files. */
public final class Packer {
    public static final class Item {
        public final String name;
        public final long size;
        public final ByteSource src;

        public Item(String name, long size, ByteSource src) {
            this.name = name;
            this.size = size;
            this.src = src;
        }
    }

    private Packer() {
    }

    /** Writes an NSP to sink.open(outputName); returns the total byte count. */
    public static long packNsp(List<Item> items, String outputName, Sink sink, Progress p) throws IOException {
        List<String> names = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        long total = 0;
        for (Item it : items) {
            names.add(it.name);
            sizes.add(it.size);
            total += it.size;
        }
        byte[] header = Container.buildPfs0Header(names, sizes);
        long written = 0;
        try (OutputStream out = sink.open(outputName)) {
            out.write(header);
            written += header.length;
            p.onLog("NSP header: " + header.length + " bytes, " + items.size() + " files");
            for (Item it : items) {
                p.onProgress("Appending " + it.name, written, header.length + total);
                Util.copyRange(it.src, 0, it.size, out);
                written += it.size;
                p.onLog("  + " + it.name + " (" + Util.size(it.size) + ")");
            }
        }
        p.onProgress("NSP written", written, written);
        return written;
    }

    /**
     * Order files the way NSCB does: CNMT first, then NCAs in CNMT order, then tickets,
     * certificates and everything else.
     */
    public static List<Item> order(List<Item> files, Cnmt cnmt) {
        if (cnmt == null) return files;
        List<Item> meta = new ArrayList<>();
        List<Item> ncas = new ArrayList<>();
        List<Item> other = new ArrayList<>();
        for (Item it : files) {
            String l = it.name.toLowerCase(java.util.Locale.ROOT);
            if (l.endsWith(".cnmt.nca") || l.endsWith(".cnmt.ncz")) meta.add(it);
            else if (l.endsWith(".nca") || l.endsWith(".ncz")) ncas.add(it);
            else other.add(it);
        }
        List<Item> ordered = new ArrayList<>();
        ordered.addAll(meta);
        for (Cnmt.Content c : cnmt.contents) {
            String id = c.ncaId.toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < ncas.size(); i++) {
                String n = ncas.get(i).name.toLowerCase(java.util.Locale.ROOT);
                if (n.contains(id)) {
                    ordered.add(ncas.remove(i));
                    break;
                }
            }
        }
        ordered.addAll(ncas);
        ordered.addAll(other);
        return ordered;
    }
}
