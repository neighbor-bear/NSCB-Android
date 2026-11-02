package io.nscb.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import io.nscb.engine.Keys;

/** Settings screen: key management and app info. */
public class SettingsActivity extends Activity {
    private static final int REQ_KEYS = 1001;

    private final int C_BG = Color.rgb(10, 14, 19);
    private final int C_CARD = Color.rgb(20, 26, 34);
    private final int C_ITEM = Color.rgb(26, 33, 43);
    private final int C_STROKE = Color.rgb(38, 48, 62);
    private final int C_TEXT = Color.rgb(232, 238, 245);
    private final int C_MUTED = Color.rgb(140, 154, 170);
    private final int C_ACCENT = Color.rgb(88, 166, 255);

    private TextView keyStatus;
    private TextView keyDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        setContentView(buildUi());
        refresh();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = chip("‹ 返回");
        back.setOnClickListener(v -> finish());
        head.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        title.setPadding(dp(14), 0, 0, 0);
        head.addView(title);
        root.addView(head);

        ScrollView scroller = new ScrollView(this);
        scroller.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout keysCard = card();
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(20);
        keysCard.setLayoutParams(klp);

        TextView keysTitle = new TextView(this);
        keysTitle.setText("密钥");
        keysTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        keysTitle.setTypeface(Typeface.DEFAULT_BOLD);
        keysTitle.setTextColor(C_TEXT);
        keysCard.addView(keysTitle);

        keyStatus = new TextView(this);
        keyStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keyStatus.setTextColor(C_ACCENT);
        keyStatus.setPadding(0, dp(8), 0, dp(2));
        keysCard.addView(keyStatus);

        keyDetail = new TextView(this);
        keyDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        keyDetail.setTextColor(C_MUTED);
        keyDetail.setPadding(0, dp(2), 0, dp(6));
        keysCard.addView(keyDetail);

        action(keysCard, "导入 prod.keys", v -> pickKeys());
        action(keysCard, "移除已保存的 prod.keys", v -> removeKeys());

        TextView note = new TextView(this);
        note.setText("导入后可解密 NCA 头，用于显示标题 ID、版本与类型。\n"
                + "文件保存在应用私有目录（prod.keys），不会上传。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(C_MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(dp(2), 1f);
        keysCard.addView(note);
        body.addView(keysCard);

        LinearLayout aboutCard = card();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);
        aboutCard.setLayoutParams(alp);

        TextView about = new TextView(this);
        about.setText("关于");
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        about.setTypeface(Typeface.DEFAULT_BOLD);
        about.setTextColor(C_TEXT);
        aboutCard.addView(about);

        TextView info = new TextView(this);
        info.setText("NSCB 原生移植版 1.1\n原生 Java 引擎，不依赖 Python / 模拟器\n"
                + "格式：NSP · NSZ · XCI · XCZ · NCA · RomFS");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(C_MUTED);
        info.setPadding(0, dp(8), 0, 0);
        info.setLineSpacing(dp(3), 1f);
        aboutCard.addView(info);
        body.addView(aboutCard);

        scroller.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroller.setLayoutParams(scLp);
        root.addView(scroller);
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
        l.setPadding(dp(16), dp(14), dp(16), dp(16));
        return l;
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

    private File keysFile() {
        return new File(getFilesDir(), "prod.keys");
    }

    private void refresh() {
        File f = keysFile();
        if (!f.exists()) {
            keyStatus.setText("未导入");
            keyDetail.setText("导入 prod.keys 后可读取加密 NCA 头。");
            return;
        }
        try {
            Keys k = Keys.fromFile(f);
            keyStatus.setText("已导入 · " + k.size() + " 条");
            keyDetail.setText(k.canDecryptHeaders()
                    ? "header_key 可用，可读取版本 / 类型。"
                    : "缺少 header_key，只能处理未加密的 NCA 头。");
        } catch (Exception e) {
            keyStatus.setText("读取失败");
            keyDetail.setText(e.getMessage());
        }
    }

    private void pickKeys() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(i, REQ_KEYS);
    }

    private void removeKeys() {
        File f = keysFile();
        if (!f.exists()) {
            Toast.makeText(this, "当前没有已保存的密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        if (f.delete()) {
            Toast.makeText(this, "已移除 prod.keys", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        } else {
            Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_KEYS || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        File tmp = new File(getFilesDir(), "prod.keys.tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new Exception("无法读取所选文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            tmp.delete();
            return;
        }
        try {
            Keys k = Keys.fromFile(tmp);
            File dst = keysFile();
            if (dst.exists() && !dst.delete()) throw new Exception("无法覆盖旧密钥");
            if (!tmp.renameTo(dst)) throw new Exception("无法保存密钥");
            Toast.makeText(this, "已导入 " + k.size() + " 条密钥", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        } catch (Exception e) {
            tmp.delete();
            Toast.makeText(this, "不是有效的 prod.keys: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refresh();
    }
}
