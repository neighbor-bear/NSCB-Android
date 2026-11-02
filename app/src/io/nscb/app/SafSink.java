package io.nscb.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.nscb.engine.Sink;

/**
 * Sink that writes into a SAF tree. Sub paths become real sub directories when the provider
 * supports it; otherwise names are flattened as "a_b".
 */
final class SafSink implements Sink {
    private final ContentResolver cr;
    private final Uri tree;
    private final Uri rootDoc;
    private final Map<String, Set<String>> used = new HashMap<>();
    private final Map<String, Uri> dirs = new HashMap<>();

    SafSink(ContentResolver cr, Uri tree) {
        this.cr = cr;
        this.tree = tree;
        Uri root;
        try {
            root = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
        } catch (Exception e) {
            root = tree;
        }
        this.rootDoc = root;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Cursor c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                Set<String> names = new HashSet<>();
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n != null) names.add(n.toLowerCase(Locale.ROOT));
                }
                c.close();
                used.put("", names);
            }
        } catch (Exception ignored) {
        }
    }

    static String flat(String path) {
        String s = path.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        s = s.replace('/', '_');
        if (s.isEmpty()) s = "output";
        return s;
    }

    private Uri dirFor(List<String> parts) throws IOException {
        Uri parent = rootDoc;
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append('/').append(part);
            String k = key.toString();
            Uri cached = dirs.get(k);
            if (cached != null) {
                parent = cached;
                continue;
            }
            Uri created = DocumentsContract.createDocument(cr, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, part);
            if (created == null) throw new IOException("无法创建目录: " + part);
            dirs.put(k, created);
            parent = created;
        }
        return parent;
    }

    private String unique(String dirKey, String name) {
        Set<String> set = used.get(dirKey);
        if (set == null) {
            set = new HashSet<>();
            used.put(dirKey, set);
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot);
            base = name.substring(0, dot);
        }
        String candidate = name;
        int i = 2;
        while (set.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + " (" + i + ")" + ext;
            i++;
        }
        set.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    @Override
    public OutputStream open(String relativePath) throws IOException {
        String p = relativePath.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if (!s.isEmpty()) parts.add(s);
        }
        if (parts.isEmpty()) parts.add("output");
        String name = parts.remove(parts.size() - 1);

        Uri parent;
        String dirKey;
        try {
            parent = dirFor(parts);
            dirKey = parts.toString();
        } catch (Exception ex) {
            // Provider does not allow creating sub directories: fall back to a flat name.
            parent = rootDoc;
            dirKey = "";
            name = flat(relativePath);
        }
        name = unique(dirKey, name);
        Uri doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
        if (doc == null) throw new IOException("无法在所选目录创建文件: " + name);
        OutputStream out = cr.openOutputStream(doc, "w");
        if (out == null) throw new IOException("无法写入: " + name);
        return out;
    }
}
