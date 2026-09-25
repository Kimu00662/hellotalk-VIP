package com.hellotalk.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

public class SettingsActivity extends Activity {

    // hook 端（HelloTalk 进程）直接读这个文件，故放在双方都可读的 /data/local/tmp。
    public static final String CONFIG_PATH = "/data/local/tmp/htvip_config.txt";
    public static final String KEY_FAKE_VIP = "fake_vip";

    private static final String PREFS = "htvip";

    private Switch swFakeVip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (getResources().getDisplayMetrics().density * 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("HelloTalk Hook 设置");
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, pad);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad / 2, 0, pad / 2);

        TextView label = new TextView(this);
        label.setText("假VIP（6.0.90，解锁高级筛选等VIP功能）");
        label.setTextSize(15f);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        swFakeVip = new Switch(this);
        swFakeVip.setChecked(readFakeVip());
        row.addView(swFakeVip);
        root.addView(row);

        TextView hint = new TextView(this);
        hint.setText("关闭后对 HelloTalk 零修改。改动需保存并重启 HelloTalk 生效（保存需要 root）。");
        hint.setTextSize(13f);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 0, 0, pad);
        root.addView(hint);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            boolean on = swFakeVip.isChecked();
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_FAKE_VIP, on)
                    .apply();
            if (writeFakeVip(on)) {
                Toast.makeText(this, "已保存，重启 HelloTalk 生效", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败：未获取到 root 权限", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save);

        setContentView(root);
    }

    private boolean readFakeVip() {
        try {
            File f = new File(CONFIG_PATH);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith(KEY_FAKE_VIP + "=")) {
                        r.close();
                        return "true".equalsIgnoreCase(
                                line.substring(KEY_FAKE_VIP.length() + 1).trim());
                    }
                }
                r.close();
            }
        } catch (Throwable ignored) {
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean(KEY_FAKE_VIP, false);
    }

    private boolean writeFakeVip(boolean on) {
        String cmd = "echo '" + KEY_FAKE_VIP + "=" + on + "' > " + CONFIG_PATH
                + " && chmod 644 " + CONFIG_PATH;

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (r.readLine() != null) {
            }
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
