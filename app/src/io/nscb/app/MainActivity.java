package io.nscb.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nscb.engine.Archive;
import io.nscb.engine.ByteSource;
import io.nscb.engine.Container;
import io.nscb.engine.Keys;
import io.nscb.engine.Nca;
import io.nscb.engine.Nsz;
import io.nscb.engine.Packer;
import io.nscb.engine.Progress;
import io.nscb.engine.Renamer;
import io.nscb.engine.Report;
import io.nscb.engine.Romfs;
import io.nscb.engine.Sink;
import io.nscb.engine.SliceSource;
import io.nscb.engine.Split;
import io.nscb.engine.Util;
import io.nscb.engine.Xci;

public class MainActivity extends Activity {
    private static final int REQ_KEYS = 1;
    private static final int REQ_ARCHIVE = 2;
    private static final int REQ_TREE_EXTRACT = 3;
    private static final int REQ_TREE_CONVERT = 4;
    private static final int REQ_PACK_FILES = 5;
    private static final int REQ_TREE_PACK = 6;
    private static final int REQ_TREE_SPLIT = 7;
    private static final int REQ_MERGE_FILES = 8;
    private static final int REQ_TREE_MERGE = 9;
    private static final int REQ_TREE_NSZ = 10;
    private static final int REQ_XCI_FILES = 11;
    private static final int REQ_TREE_CREATE_XCI = 12;
    private static final int REQ_TREE_TRIM = 13;
    private static final int REQ_TREE_XCXI = 14;
    private static final int REQ_TREE_ROMFS = 15;
    private static final int REQ_NSZ_FILES = 16;
    private static final int REQ_TREE_NSZ_OUT = 17;
    private static final int REQ_NSP_XCI_FILES = 18;
    private static final int REQ_UPDATE_FILES = 19;
    private static final int REQ_TREE_INTEGRATE = 20;
    private static final int REQ_INTEGRATE_FILES = 21;
    private static final int REQ_TREE_INTEGRATE_NSP = 22;
    private static final int REQ_INPUT_TREE = 23;

    private final int C_BG = Color.rgb(10, 14, 19);
    private final int C_CARD = Color.rgb(20, 26, 34);
    private final int C_ITEM = Color.rgb(26, 33, 43);
    private final int C_STROKE = Color.rgb(38, 48, 62);
    private final int C_TEXT = Color.rgb(232, 238, 245);
    private final int C_MUTED = Color.rgb(140, 154, 170);
    private final int C_ACCENT = Color.rgb(88, 166, 255);
    private final int C_LOG = Color.rgb(14, 19, 25);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Uri> selected = new ArrayList<>();

    private TextView status;
    private TextView keysChip;
    private ProgressBar busyBar;
    private TextView logView;
    private ScrollView logScroll;
    private Keys keys;
    private boolean busy;
    private char pendingTreeAction;
    private boolean treePickerActive;
    private char pendingFileAction;
    private int pendingTreeReq;
    private String[] pendingExts = new String[0];
    private final List<Uri> pendingFiles = new ArrayList<>();

    private final StringBuilder logBuffer = new StringBuilder();

    private final Progress progress = new Progress() {
        private long last;

        @Override
        public void onProgress(String message, final long done, final long total) {
            long now = SystemClock.uptimeMillis();
            if (now - last < 200 && done < total) return;
            last = now;
            final String text = total > 0
                    ? String.format(Locale.ROOT, "%s  %d%%  (%s / %s)", message,
                    (int) (done * 100 / Math.max(1, total)), Util.size(done), Util.size(total))
                    : message;
            ui.post(() -> status.setText(text));
        }

        @Override
        public void onLog(String message) {
            ui.post(() -> append(message));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        setContentView(buildUi());
        loadKeysFromDisk();
        append("原生引擎已就绪：不依赖 Python / 模拟器。");
        append("在「设置」中导入 prod.keys；未导入时加密 NCA 头无法解密。");
        append("NSZ / NSP 转换会先选输入文件夹，再选输出文件夹。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (keysChip != null) loadKeysFromDisk();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("NSCB PORT");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Nintendo Switch 文件工具 · 原生引擎");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(C_MUTED);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = chip("设置");
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        head.addView(gear);
        root.addView(head);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(16);
        statusCard.setLayoutParams(cardLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setText("空闲");
        status.setTextColor(C_ACCENT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusRow.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        keysChip = new TextView(this);
        keysChip.setText("密钥 · 未导入");
        keysChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        keysChip.setTextColor(C_MUTED);
        statusRow.addView(keysChip);
        statusCard.addView(statusRow);

        busyBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setIndeterminate(true);
        busyBar.setVisibility(View.INVISIBLE);
        busyBar.setIndeterminateTintList(ColorStateList.valueOf(C_ACCENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        barLp.topMargin = dp(10);
        busyBar.setLayoutParams(barLp);
        statusCard.addView(busyBar);
        root.addView(statusCard);

        ScrollView scroller = new ScrollView(this);
        scroller.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(2), 0, dp(2));

        section(body, "归档");
        action(body, "选择归档文件（可多选）", v -> pickArchives());
        action(body, "查看信息 / 校验", v -> runTask("读取信息", this::showInfo));

        section(body, "转换");
        action(body, "NSZ → NSP（只显示 .nsz）",
                v -> pickInputTree('C', REQ_TREE_NSZ_OUT, new String[]{".nsz"}));
        action(body, "NSP → XCI（只显示 .nsp）",
                v -> pickInputTree('B', REQ_TREE_NSZ_OUT, new String[]{".nsp"}));
        action(body, "NSP → NSZ（zstd 压缩）", v -> pickTree(REQ_TREE_NSZ, 'Z'));
        action(body, "XCZ → XCI", v -> pickTree(REQ_TREE_XCXI, 'X'));
        action(body, "提取 RomFS（Program NCA）", v -> pickTree(REQ_TREE_ROMFS, 'R'));

        section(body, "整合与打包");
        action(body, "XCI + NSP 整合更新补丁",
                v -> pickFiles(REQ_UPDATE_FILES, 'U', REQ_TREE_INTEGRATE));
        action(body, "XCI 真合并更新（工具显示新版本）",
                v -> pickFiles(REQ_UPDATE_FILES, 'V', REQ_TREE_INTEGRATE));
        action(body, "整合为 NSP（游戏 + 补丁/DLC）",
                v -> pickFiles(REQ_INTEGRATE_FILES, 'G', REQ_TREE_INTEGRATE_NSP));
        action(body, "从 .nca 创建 XCI",
                v -> pickFiles(REQ_XCI_FILES, 'I', REQ_TREE_CREATE_XCI));
        action(body, "从 .nca 文件打包 NSP",
                v -> pickFiles(REQ_PACK_FILES, 'P', REQ_TREE_PACK));

        section(body, "工具");
        action(body, "解包到目录", v -> pickTree(REQ_TREE_EXTRACT, 'E'));
        action(body, "裁剪 XCI（保留 update+secure）", v -> pickTree(REQ_TREE_TRIM, 'T'));
        action(body, "拆分到 FAT32（4GB 分卷）", v -> pickTree(REQ_TREE_SPLIT, 'S'));
        action(body, "合并分卷", v -> pickFiles(REQ_MERGE_FILES, 'M', REQ_TREE_MERGE));

        scroller.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = dp(6);
        scroller.setLayoutParams(scLp);
        root.addView(scroller);

        LinearLayout logCard = card();
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logLp.topMargin = dp(12);
        logCard.setLayoutParams(logLp);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(C_TEXT);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clear.setTextColor(C_ACCENT);
        clear.setPadding(dp(10), dp(4), dp(4), dp(4));
        clear.setOnClickListener(v -> {
            logBuffer.setLength(0);
            logView.setText("");
        });
        logHead.addView(clear);
        logCard.addView(logHead);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextColor(Color.rgb(180, 195, 210));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));

        logScroll = new ScrollView(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(C_LOG);
        logBg.setCornerRadius(dp(12));
        logScroll.setBackground(logBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        lp.topMargin = dp(8);
        logScroll.setLayoutParams(lp);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        logCard.addView(logScroll);
        root.addView(logCard);
        return root;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), C_STROKE);
        l.setBackground(g);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        return l;
    }

    private void section(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(C_ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        parent.addView(t);
    }

    private void action(LinearLayout parent, String text, View.OnClickListener listener) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(C_TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable base = new GradientDrawable();
        base.setColor(C_ITEM);
        base.setCornerRadius(dp(14));
        base.setStroke(dp(1), C_STROKE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x404EA1FF), base, null));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        parent.addView(b);
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(C_ACCENT);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x223B82F6);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), 0x664EA1FF);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334EA1FF), g, null));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------- pickers ----------------

    private void pickKeys() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(i, REQ_KEYS);
    }

    private void pickArchives() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_ARCHIVE);
    }

    private void pickFiles(int req, char action, int treeReq) {
        pendingFiles.clear();
        pendingFileAction = action;
        pendingTreeReq = treeReq;
        pendingTreeAction = action;
        treePickerActive = false;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    private void pickTree(int req, char action) {
        pendingTreeAction = action;
        treePickerActive = true;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    private void pickInputTree(char action, int outReq, String[] exts) {
        pendingFiles.clear();
        pendingFileAction = action;
        pendingTreeReq = outReq;
        pendingExts = exts;
        treePickerActive = false;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_INPUT_TREE);
    }

    private interface DocPick {
        void onPicked(List<Uri> files);
    }

    private static final class DocItem {
        String name;
        String docId;
        long size;
        Uri uri;
        boolean dir;
    }

    private static boolean matchesExt(String name, String[] exts) {
        String l = name.toLowerCase(Locale.ROOT);
        for (String e : exts) if (l.endsWith(e)) return true;
        return false;
    }

    /** In-app picker that lists only files with the requested extensions. */
    private void showDocPicker(final Uri tree, final String[] exts, final DocPick done) {
        final ContentResolver cr = getContentResolver();
        final List<String> stack = new ArrayList<>();
        stack.add(DocumentsContract.getTreeDocumentId(tree));
        final List<DocItem> items = new ArrayList<>();
        final List<Uri> picked = new ArrayList<>();
        final Set<String> pickedIds = new HashSet<>();

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(C_TEXT);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tv.setPadding(dp(18), dp(12), dp(18), dp(12));
                return tv;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDividerHeight(0);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择文件")
                .setView(list)
                .setPositiveButton("确定", null)
                .setNeutralButton("上一级", null)
                .setNegativeButton("取消", null)
                .create();

        final Runnable load = new Runnable() {
            @Override
            public void run() {
                items.clear();
                adapter.clear();
                try {
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                            stack.get(stack.size() - 1));
                    try (android.database.Cursor c = cr.query(children, new String[]{
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                        if (c != null) {
                            while (c.moveToNext()) {
                                String name = c.getString(0);
                                String id = c.getString(1);
                                String mime = c.getString(2);
                                long size = c.isNull(3) ? 0 : c.getLong(3);
                                if (name == null || id == null) continue;
                                DocItem it = new DocItem();
                                it.name = name;
                                it.docId = id;
                                it.size = size;
                                it.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                                if (!it.dir && !matchesExt(name, exts)) continue;
                                items.add(it);
                            }
                        }
                    }
                } catch (Exception e) {
                    append("读取目录失败: " + e.getMessage());
                }
                Collections.sort(items, new Comparator<DocItem>() {
                    @Override
                    public int compare(DocItem a, DocItem b) {
                        if (a.dir != b.dir) return a.dir ? -1 : 1;
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
                for (DocItem it : items) {
                    if (it.dir) {
                        adapter.add("▸  " + it.name);
                    } else {
                        boolean on = pickedIds.contains(it.docId);
                        adapter.add((on ? "☑  " : "☐  ") + it.name + "    " + Util.size(it.size));
                    }
                    it.uri = DocumentsContract.buildDocumentUriUsingTree(tree, it.docId);
                }
                adapter.notifyDataSetChanged();
                dialog.setTitle("选择文件 · 已选 " + picked.size());
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(
                        stack.size() > 1 ? View.VISIBLE : View.INVISIBLE);
            }
        };

        list.setOnItemClickListener((parent, view, position, id) -> {
            DocItem it = items.get(position);
            if (it.dir) {
                stack.add(it.docId);
                load.run();
                return;
            }
            if (pickedIds.contains(it.docId)) {
                pickedIds.remove(it.docId);
                picked.remove(it.uri);
            } else {
                pickedIds.add(it.docId);
                picked.add(it.uri);
            }
            load.run();
        });

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (picked.isEmpty()) {
                    Toast.makeText(this, "请至少选择一个文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                done.onPicked(new ArrayList<>(picked));
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (stack.size() > 1) {
                    stack.remove(stack.size() - 1);
                    load.run();
                }
            });
        });
        dialog.show();
        load.run();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        List<Uri> uris = collectUris(data);
        try {
            if (requestCode == REQ_KEYS && uri != null) {
                takeRead(uri);
                importKeys(uri);
                return;
            }
            if (requestCode == REQ_ARCHIVE) {
                selected.clear();
                for (Uri u : uris) {
                    takeRead(u);
                    selected.add(u);
                }
                append("已选择 " + selected.size() + " 个归档:");
                for (Uri u : selected) append("  " + displayName(u) + "  (" + Util.size(sizeOf(u)) + ")");
                status.setText("已选择 " + selected.size() + " 个文件");
                return;
            }
            if (requestCode == REQ_PACK_FILES || requestCode == REQ_MERGE_FILES
                    || requestCode == REQ_XCI_FILES || requestCode == REQ_NSZ_FILES
                    || requestCode == REQ_NSP_XCI_FILES || requestCode == REQ_UPDATE_FILES
                    || requestCode == REQ_INTEGRATE_FILES) {
                pendingFiles.clear();
                for (Uri u : uris) {
                    takeRead(u);
                    pendingFiles.add(u);
                }
                append("已选择 " + pendingFiles.size() + " 个文件，接下来选择输出目录。");
                if (!pendingFiles.isEmpty()) pickTree(pendingTreeReq, pendingFileAction);
                return;
            }
            if (requestCode == REQ_INPUT_TREE && uri != null) {
                treePickerActive = false;
                takeTree(uri);
                showDocPicker(uri, pendingExts, files -> {
                    pendingFiles.clear();
                    pendingFiles.addAll(files);
                    if (pendingFiles.isEmpty()) {
                        append("未选择文件。");
                        return;
                    }
                    append("已选择 " + pendingFiles.size() + " 个文件，接下来选择输出目录。");
                    pickTree(pendingTreeReq, pendingFileAction);
                });
                return;
            }
            if (treePickerActive && uri != null) {
                treePickerActive = false;
                takeTree(uri);
                runWithTree(pendingTreeAction, uri);
            }
        } catch (Exception e) {
            append("错误: " + e);
        }
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    private void takeRead(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void takeTree(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void runWithTree(char action, Uri tree) {
        Sink sink = new SafSink(getContentResolver(), tree);
        switch (action) {
            case 'E':
                runTask("解包", () -> extractAll(sink));
                break;
            case 'C':
                runTask("NSZ 转 NSP", () -> convertAll(sink));
                break;
            case 'B':
                runTask("NSP 转 XCI", () -> nspToXci(sink));
                break;
            case 'U':
                runTask("整合更新补丁", () -> integrateUpdate(sink));
                break;
            case 'V':
                runTask("真合并 XCI", () -> mergeUpdateXci(sink));
                break;
            case 'G':
                runTask("整合为 NSP", () -> integrateNsp(sink));
                break;
            case 'Z':
                runTask("压缩", () -> toNszAll(sink));
                break;
            case 'X':
                runTask("XCZ 转 XCI", () -> xczToXci(sink));
                break;
            case 'R':
                runTask("提取 RomFS", () -> romfsAll(sink));
                break;
            case 'I':
                runTask("创建 XCI", () -> createXci(sink));
                break;
            case 'T':
                runTask("裁剪 XCI", () -> trimAll(sink));
                break;
            case 'P':
                runTask("打包", () -> packAll(sink));
                break;
            case 'S':
                runTask("拆分", () -> splitAll(sink));
                break;
            case 'M':
                runTask("合并", () -> mergeAll(sink));
                break;
            default:
        }
    }

    // ---------------- keys ----------------

    private File keysFile() {
        return new File(getFilesDir(), "prod.keys");
    }

    private void loadKeysFromDisk() {
        File f = keysFile();
        if (!f.exists()) {
            keys = null;
            if (keysChip != null) keysChip.setText("密钥 · 未导入");
            return;
        }
        try {
            keys = Keys.fromFile(f);
            if (keysChip != null) {
                keysChip.setText(keys.canDecryptHeaders() ? "密钥 · 已加载" : "密钥 · 缺少 header_key");
            }
        } catch (Exception e) {
            keys = null;
            if (keysChip != null) keysChip.setText("密钥 · 读取失败");
            append("读取 prod.keys 失败: " + e.getMessage());
        }
    }

    private void importKeys(Uri uri) throws Exception {
        File dst = keysFile();
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new Exception("无法读取所选文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        keys = Keys.fromFile(dst);
        append("已导入 prod.keys: " + keys.size() + " 条目"
                + (keys.canDecryptHeaders() ? "，header_key 可用" : "，缺少 header_key"));
        status.setText("密钥已导入");
    }

    // ---------------- tasks ----------------

    private void showInfo() {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            append("== " + name + " ==");
            try (Archive a = openArchive(uri, name)) {
                report(a.report());
                for (String line : a.verify(progress)) append("  " + line);
            } catch (Exception e) {
                append("读取失败: " + e.getMessage());
            }
        }
    }

    private void report(Report r) {
        append(r.render());
        if (r.icon != null && r.icon.length > 0) {
            append("图标: " + r.icon.length + " bytes (PNG/JPEG)");
        }
    }

    private void extractAll(Sink sink) throws Exception {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            append("解包 " + name);
            try (Archive a = openArchive(uri, name)) {
                a.extract(sink, true, progress);
                append("完成: " + name);
            }
        }
        sink.finish();
        append("解包任务结束。");
    }

    private void convertAll(Sink sink) throws Exception {
        List<Uri> inputs = pendingFiles.isEmpty() ? new ArrayList<>(selected) : new ArrayList<>(pendingFiles);
        if (inputs.isEmpty()) {
            append("请先选择 NSZ 文件。");
            return;
        }
        int ok = 0;
        for (Uri uri : inputs) {
            String name = displayName(uri);
            try (Archive a = openArchive(uri, name)) {
                if (a.type != Archive.Type.NSZ) {
                    append("跳过 " + name + "（不是 NSZ）");
                    continue;
                }
                String out = Renamer.suggest(a.report(), name, ".nsp");
                append("转换 " + name + " -> " + out);
                Nsz.toNsp(a, sink, out, progress);
                append("完成: " + out);
                ok++;
            }
        }
        sink.finish();
        append("转换任务结束，成功 " + ok + " 个。");
    }

    private void nspToXci(Sink sink) throws Exception {
        if (pendingFiles.isEmpty()) {
            append("请先选择 NSP 文件。");
            return;
        }
        int ok = 0;
        for (Uri uri : pendingFiles) {
            String name = displayName(uri);
            try (Archive a = openArchive(uri, name)) {
                if (a.type != Archive.Type.NSP) {
                    append("跳过 " + name + "（不是 NSP；NSZ 请先转 NSP）");
                    continue;
                }
                String out = Renamer.suggest(a.report(), name, ".xci");
                append("转换 " + name + " -> " + out);
                Xci.fromArchive(a, out, sink, progress);
                append("完成: " + out + " (" + Util.size(sizeOf(uri)) + " -> XCI)");
                ok++;
            }
        }
        sink.finish();
        append("XCI 创建结束，成功 " + ok + " 个。");
    }

    private void integrateUpdate(Sink sink) throws Exception {
        if (pendingFiles.size() < 2) {
            append("请选择 1 个 XCI/XCZ 基底 + 1 个更新 NSP。");
            return;
        }
        List<Archive> opened = new ArrayList<>();
        try {
            Archive base = null;
            Archive update = null;
            for (Uri uri : pendingFiles) {
                String name = displayName(uri);
                Archive a = openArchive(uri, name);
                opened.add(a);
                if (a.type == Archive.Type.XCI || a.type == Archive.Type.XCZ) {
                    if (base == null) base = a;
                    else append("忽略多余基底: " + name);
                } else if (a.pfs != null) {
                    if (update == null) update = a;
                    else append("忽略多余更新包: " + name);
                } else {
                    append("忽略不支持的文件: " + name);
                }
            }
            if (base == null) {
                append("没有找到 XCI/XCZ 基底文件。");
                return;
            }
            if (update == null) {
                append("没有找到更新 NSP。");
                return;
            }
            Report ru = update.report();
            String ut = ru.titleType == null ? "" : ru.titleType;
            if (ut.equals("Application") || ut.equals("AddOnContent")) {
                append("所选 NSP 是 " + ut + "，不是更新补丁（Patch）。");
                append("DLC / 本体不能放进 XCI 的 update 分区；请选择 [更新补丁] NSP。");
                append("若要把 DLC 与游戏合在一起，请用「整合为 NSP（游戏 + 补丁/DLC）」。");
                return;
            }
            append("基底: " + base.fileName + "  版本 v" + Math.max(0, base.report().version));
            append("更新包: " + update.fileName + "  类型 " + (ut.isEmpty() ? "未知" : ut)
                    + "  版本 v" + Math.max(0, ru.version));
            if (ut.isEmpty()) append("提示: 无法识别更新包类型（可能需要先导入 prod.keys），仍按更新包处理。");
            String out = Renamer.suggest(base.report(), base.fileName, ".update.xci");
            append("整合 " + base.fileName + " + " + update.fileName + " -> " + out);
            Xci.integrateUpdate(base, update, sink, out, getCacheDir(), progress);
            append("完成: " + out);
            append("说明: XCI 内嵌的是 update 分区；多数查看工具显示的仍是基底 NACP 版本。");
            append("主机启动时会读取 update 分区应用补丁；若工具要显示新版本，请整合为 NSP 后安装。");
        } finally {
            for (Archive a : opened) {
                try {
                    a.close();
                } catch (Exception ignored) {
                }
            }
        }
        sink.finish();
    }

    /** True merge: replaces the XCI secure partition with the update NSP content. */
    private void mergeUpdateXci(Sink sink) throws Exception {
        if (pendingFiles.size() < 2) {
            append("请选择 1 个 XCI/XCZ 基底 + 1 个更新 NSP。");
            return;
        }
        List<Archive> opened = new ArrayList<>();
        try {
            Archive base = null;
            Archive update = null;
            for (Uri uri : pendingFiles) {
                String name = displayName(uri);
                Archive a = openArchive(uri, name);
                opened.add(a);
                if (a.type == Archive.Type.XCI || a.type == Archive.Type.XCZ) {
                    if (base == null) base = a;
                    else append("忽略多余基底: " + name);
                } else if (a.pfs != null) {
                    if (update == null) update = a;
                    else append("忽略多余更新包: " + name);
                } else {
                    append("忽略不支持的文件: " + name);
                }
            }
            if (base == null) {
                append("没有找到 XCI/XCZ 基底文件。");
                return;
            }
            if (update == null) {
                append("没有找到更新 NSP。");
                return;
            }
            Report ru = update.report();
            String ut = ru.titleType == null ? "" : ru.titleType;
            if (ut.equals("Application") || ut.equals("AddOnContent")) {
                append("所选 NSP 是 " + ut + "，不是更新补丁（Patch）。");
                append("真合并需要更新补丁 NSP；DLC 请用「整合为 NSP」。");
                return;
            }
            append("基底: " + base.fileName + "  版本 v" + Math.max(0, base.report().version));
            append("更新包: " + update.fileName + "  类型 " + (ut.isEmpty() ? "未知" : ut)
                    + "  版本 v" + Math.max(0, ru.version));
            String out = Renamer.suggest(base.report(), base.fileName, ".merged.xci");
            append("真合并 " + base.fileName + " + " + update.fileName + " -> " + out);
            Xci.mergeUpdate(base, update, sink, out, getCacheDir(), progress);
            append("完成: " + out);
            append("已用更新内容替换 secure 分区，并清空 update 分区；查看工具应显示更新版本。");
        } finally {
            for (Archive a : opened) {
                try {
                    a.close();
                } catch (Exception ignored) {
                }
            }
        }
        sink.finish();
    }

    private void integrateNsp(Sink sink) throws Exception {
        if (pendingFiles.isEmpty()) {
            append("请先选择游戏 + 补丁/DLC 文件。");
            return;
        }
        List<Archive> opened = new ArrayList<>();
        List<Packer.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String baseName = "";
        try {
            for (Uri uri : pendingFiles) {
                String name = displayName(uri);
                Archive a = openArchive(uri, name);
                opened.add(a);
                List<Container.Entry> entries = a.pfs != null ? a.pfs.entries
                        : a.secure != null ? a.secure.entries : null;
                if (entries == null) {
                    append("跳过 " + name + "（不支持的格式）");
                    continue;
                }
                if (baseName.isEmpty()) baseName = name;
                List<Packer.Item> group = new ArrayList<>();
                for (Container.Entry e : entries) {
                    String l = e.name.toLowerCase(Locale.ROOT);
                    if (Nsz.isCompressedName(e.name)) {
                        throw new Exception("包含压缩 NCA，请先 NSZ 转 NSP：" + name);
                    }
                    if (!(l.endsWith(".nca") || l.endsWith(".tik") || l.endsWith(".cert") || l.endsWith(".xml"))) {
                        continue;
                    }
                    if (!seen.add(l)) {
                        append("跳过重复文件: " + e.name);
                        continue;
                    }
                    group.add(new Packer.Item(e.name, e.size, new SliceSource(a.src, e.dataStart, e.size)));
                }
                if (group.isEmpty()) {
                    append("跳过 " + name + "（没有 NCA）");
                    continue;
                }
                items.addAll(Packer.order(group, a.cnmt));
                append("加入 " + name + "：" + group.size() + " 个文件");
            }
            if (items.isEmpty()) {
                append("没有可整合的内容。");
                return;
            }
            String base = baseName.replaceAll("\\.(xci|xcz|nsp|nsz)$", "");
            String out = Util.sanitize(base) + " [整合].nsp";
            if (out.startsWith(" ")) out = "integrated" + out;
            append("输出 " + out + "（" + items.size() + " 个文件）");
            Packer.packNsp(items, out, sink, progress);
            append("整合 NSP 完成: " + out);
        } finally {
            for (Archive a : opened) {
                try {
                    a.close();
                } catch (Exception ignored) {
                }
            }
        }
        sink.finish();
    }

    private void toNszAll(Sink sink) throws Exception {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            try (Archive a = openArchive(uri, name)) {
                if (a.pfs == null) {
                    append("跳过 " + name + "（不是 NSP/NSZ）");
                    continue;
                }
                String out = Renamer.suggest(a.report(), name, ".nsz");
                append("压缩 " + name + " -> " + out);
                long n = Nsz.toNsz(a, sink, out, 17, getCacheDir(), progress);
                append("完成: " + out + " (" + Util.size(n) + ")");
            }
        }
        sink.finish();
        append("压缩任务结束。");
    }

    private void xczToXci(Sink sink) throws Exception {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            try (Archive a = openArchive(uri, name)) {
                if (a.type != Archive.Type.XCZ) {
                    append("跳过 " + name + "（不是 XCZ）");
                    continue;
                }
                String out = Renamer.suggest(a.report(), name, ".xci");
                append("转换 " + name + " -> " + out);
                Xci.rebuild(a, true, true, sink, out, getCacheDir(), progress);
                append("完成: " + out);
            }
        }
        sink.finish();
        append("XCZ 转换任务结束。");
    }

    private void romfsAll(Sink sink) throws Exception {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            try (Archive a = openArchive(uri, name)) {
                Nca program = a.programNca();
                if (program == null) {
                    append("跳过 " + name + "（没有可读的 Program NCA）");
                    continue;
                }
                String prefix = a.titleId.isEmpty() ? "" : a.titleId + "/";
                append("提取 RomFS: " + name + " -> " + (prefix.isEmpty() ? "(根目录)" : prefix));
                int n = Romfs.extract(program, sink, prefix, progress);
                append("完成: " + n + " 个文件");
            }
        }
        sink.finish();
        append("RomFS 任务结束。");
    }

    private void createXci(Sink sink) throws Exception {
        if (pendingFiles.isEmpty()) {
            append("没有选择 .nca 文件。");
            return;
        }
        List<Packer.Item> items = new ArrayList<>();
        List<ByteSource> sources = new ArrayList<>();
        try {
            for (Uri uri : pendingFiles) {
                String name = displayName(uri);
                String l = name.toLowerCase(Locale.ROOT);
                if (!(l.endsWith(".nca") || l.endsWith(".ncz") || l.endsWith(".tik")
                        || l.endsWith(".cert") || l.endsWith(".xml"))) {
                    append("忽略: " + name);
                    continue;
                }
                SafSource s = new SafSource(getContentResolver(), uri);
                sources.add(s);
                items.add(new Packer.Item(name, s.size(), s));
            }
            if (items.isEmpty()) {
                append("没有有效输入文件。");
                return;
            }
            String out = "created.xci";
            Xci.create(items, keys, out, sink, progress);
            append("已创建 XCI: " + out + "（" + items.size() + " 个条目）");
        } finally {
            for (ByteSource s : sources) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        sink.finish();
    }

    private void trimAll(Sink sink) throws Exception {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            try (Archive a = openArchive(uri, name)) {
                if (a.type != Archive.Type.XCI && a.type != Archive.Type.XCZ) {
                    append("跳过 " + name + "（不是 XCI/XCZ）");
                    continue;
                }
                String out = Renamer.suggest(a.report(), name, ".trim.xci");
                append("裁剪 " + name + " -> " + out);
                Xci.rebuild(a, false, false, sink, out, getCacheDir(), progress);
                append("完成: " + out);
            }
        }
        sink.finish();
        append("裁剪任务结束。");
    }

    private void packAll(Sink sink) throws Exception {
        if (pendingFiles.isEmpty()) {
            append("没有选择 .nca 文件。");
            return;
        }
        List<Packer.Item> items = new ArrayList<>();
        List<ByteSource> sources = new ArrayList<>();
        try {
            for (Uri uri : pendingFiles) {
                String name = displayName(uri);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".nca")) {
                    append("忽略非 .nca 文件: " + name);
                    continue;
                }
                SafSource s = new SafSource(getContentResolver(), uri);
                sources.add(s);
                items.add(new Packer.Item(name, s.size(), s));
            }
            if (items.isEmpty()) {
                append("没有有效的 .nca 文件。");
                return;
            }
            items = Packer.order(items, null);
            Packer.packNsp(items, "packed.nsp", sink, progress);
            append("已打包 " + items.size() + " 个 NCA -> packed.nsp");
        } finally {
            for (ByteSource s : sources) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void splitAll(Sink sink) throws Exception {
        if (selected.isEmpty()) {
            append("请先选择归档文件。");
            return;
        }
        for (Uri uri : selected) {
            String name = displayName(uri);
            append("拆分 " + name + " (4GB 分卷)");
            try (ByteSource s = new SafSource(getContentResolver(), uri)) {
                int parts = Split.split(s, name, sink, Split.FAT32_BLOCK, progress);
                append("完成: " + parts + " 个分卷");
            }
        }
        sink.finish();
    }

    private void mergeAll(Sink sink) throws Exception {
        if (pendingFiles.isEmpty()) {
            append("没有选择分卷文件。");
            return;
        }
        List<String> names = new ArrayList<>();
        for (Uri u : pendingFiles) names.add(displayName(u));
        names = Split.sortPartNames(names);
        List<Uri> ordered = new ArrayList<>();
        for (String n : names) {
            for (Uri u : pendingFiles) {
                if (displayName(u).equals(n)) {
                    ordered.add(u);
                    break;
                }
            }
        }
        String merged = mergedName(names.get(0));
        List<ByteSource> sources = new ArrayList<>();
        try {
            for (Uri u : ordered) sources.add(new SafSource(getContentResolver(), u));
            try (OutputStream out = sink.open(merged)) {
                long n = Split.merge(sources, out, progress);
                append("合并 " + ordered.size() + " 个分卷 -> " + merged + " (" + Util.size(n) + ")");
            }
        } finally {
            for (ByteSource s : sources) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        sink.finish();
    }

    private static String mergedName(String first) {
        String base = first.replaceAll("\\d+$", "");
        if (base.endsWith(".ns")) return base + "p";
        if (base.endsWith(".xc")) return base + "i";
        return base + ".merged";
    }

    // ---------------- helpers ----------------

    private Archive openArchive(Uri uri, String name) throws Exception {
        SafSource src = new SafSource(getContentResolver(), uri);
        try {
            return Archive.open(src, name, keys);
        } catch (Exception e) {
            src.close();
            throw e;
        }
    }

    private String displayName(Uri uri) {
        ContentResolver cr = getContentResolver();
        try (android.database.Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "file" : p;
    }

    private long sizeOf(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void runTask(String label, Task task) {
        if (busy) {
            Toast.makeText(this, "已有任务在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        if (busyBar != null) busyBar.setVisibility(View.VISIBLE);
        append("--------------------------------");
        append("开始: " + label);
        status.setText(label + "…");
        worker.execute(() -> {
            try {
                task.run();
                ui.post(() -> append("完成: " + label));
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                ui.post(() -> {
                    append("任务失败: " + msg);
                    Toast.makeText(MainActivity.this, "任务失败: " + msg, Toast.LENGTH_LONG).show();
                    status.setText("失败: " + msg);
                });
            } finally {
                ui.post(() -> {
                    busy = false;
                    if (busyBar != null) busyBar.setVisibility(View.INVISIBLE);
                    if (status.getText().toString().startsWith("失败")) return;
                    status.setText("空闲");
                });
            }
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    private void append(String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final String text = line;
            ui.post(() -> append(text));
            return;
        }
        logBuffer.append(line).append('\n');
        if (logBuffer.length() > 200000) logBuffer.delete(0, logBuffer.length() - 180000);
        logView.setText(logBuffer.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
