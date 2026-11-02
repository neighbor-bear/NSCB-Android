package io.nscb.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** FAT32 split / merge helpers (NSCB style: part files are name[0..-2] + index). */
public final class Split {
    /** 4294901760 bytes, the block size NSCB uses for FAT32 output. */
    public static final long FAT32_BLOCK = 4294901760L;

    private Split() {
    }

    public static String partName(String name, int index) {
        if (name.isEmpty()) return String.valueOf(index);
        return name.substring(0, name.length() - 1) + index;
    }

    /** Splits src into parts written to sink; returns part count. */
    public static int split(ByteSource src, String name, Sink sink, long block, Progress p) throws IOException {
        long size = src.size();
        if (size <= block) {
            try (OutputStream out = sink.open(name)) {
                Util.copyRange(src, 0, size, out);
            }
            p.onProgress("Copied " + name, size, size);
            return 1;
        }
        int parts = (int) ((size + block - 1) / block);
        long done = 0;
        for (int i = 0; i < parts; i++) {
            long len = Math.min(block, size - done);
            String part = partName(name, i);
            p.onProgress("Writing " + part, done, size);
            try (OutputStream out = sink.open(part)) {
                Util.copyRange(src, done, len, out);
            }
            done += len;
            p.onLog(part + " (" + Util.size(len) + ")");
        }
        return parts;
    }

    /** Concatenates parts in order into out. */
    public static long merge(List<ByteSource> parts, OutputStream out, Progress p) throws IOException {
        long total = 0;
        for (ByteSource s : parts) total += s.size();
        long done = 0;
        for (int i = 0; i < parts.size(); i++) {
            ByteSource s = parts.get(i);
            p.onProgress("Merging part " + (i + 1) + "/" + parts.size(), done, total);
            Util.copyRange(s, 0, s.size(), out);
            done += s.size();
        }
        return done;
    }

    /** Sorts NSCB style split names (name.ns0, name.ns1, ...) numerically. */
    public static List<String> sortPartNames(List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.sort((a, b) -> {
            int da = lastDigitRun(a);
            int db = lastDigitRun(b);
            if (da != db) return Integer.compare(da, db);
            return a.compareTo(b);
        });
        return out;
    }

    private static int lastDigitRun(String s) {
        int end = s.length();
        int start = end;
        while (start > 0 && Character.isDigit(s.charAt(start - 1))) start--;
        if (start == end) return -1;
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
