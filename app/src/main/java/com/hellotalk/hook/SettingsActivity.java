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

public class SettingsActivity extends Activity {

    public static final String PREFS = "htvip";
    public static final String KEY_FAKE_VIP = "fake_vip";

    private Switch swFakeVip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

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
        swFakeVip.setChecked(prefs.getBoolean(KEY_FAKE_VIP, false));
        row.addView(swFakeVip);
        root.addView(row);

        TextView hint = new TextView(this);
        hint.setText("关闭后对 HelloTalk 零修改。改动需保存并重启 HelloTalk 生效。");
        hint.setTextSize(13f);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 0, 0, pad);
        root.addView(hint);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean(KEY_FAKE_VIP, swFakeVip.isChecked())
                    .apply();
            Toast.makeText(this, "已保存，重启 HelloTalk 生效", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        setContentView(root);
    }
}
