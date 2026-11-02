package io.nscb.engine;

import java.util.Locale;

/** Suggests clean output file names from parsed metadata. */
public final class Renamer {
    private Renamer() {
    }

    public static String suggest(Report r, String fallbackName, String ext) {
        String id = r.titleId == null || r.titleId.isEmpty() ? "0000000000000000" : r.titleId.toUpperCase(Locale.ROOT);
        long version = Math.max(0, r.version);
        String name = r.nacpName == null ? "" : r.nacpName.trim();
        if (name.isEmpty()) {
            int dot = fallbackName.lastIndexOf('.');
            name = dot > 0 ? fallbackName.substring(0, dot) : fallbackName;
        }
        name = Util.sanitize(name);
        if (name.isEmpty()) name = id;
        return name + " [" + id + "][v" + version + "]" + ext;
    }
}
