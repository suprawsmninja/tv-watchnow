package com.luke.watchnow;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** A small frosted panel of choices, the same look as the launcher's Scenes panel. OK picks one; Back closes. */
final class GlassMenu {
    private GlassMenu() {}

    static void show(Activity activity, String heading, String[] options, IntConsumer onPick) {
        float dp = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.4f);
        }
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(10 * dp);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xE61C1C20);
        glass.setCornerRadius(22 * dp);
        glass.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
        panel.setBackground(glass);

        TextView title = new TextView(activity);
        title.setText(heading);
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setLetterSpacing(0.12f);
        title.setAllCaps(true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Math.round(14 * dp), Math.round(4 * dp), Math.round(14 * dp), Math.round(8 * dp));
        panel.addView(title);

        for (int i = 0; i < options.length; i++) {
            int which = i;
            TextView row = new TextView(activity);
            row.setText(options[i]);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.setTextColor(0xFFFFFFFF);
            int h = Math.round(16 * dp), v = Math.round(12 * dp);
            row.setPadding(h, v, h, v);
            row.setFocusable(true);
            row.setClickable(true);
            GradientDrawable focus = new GradientDrawable();
            focus.setColor(0xFFFFFFFF);
            focus.setCornerRadius(14 * dp);
            row.setOnFocusChangeListener((view, f) -> {
                row.setBackground(f ? focus : null);
                row.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
            });
            row.setOnClickListener(view -> {
                dialog.dismiss();
                onPick.accept(which);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(2 * dp);
            panel.addView(row, lp);
        }
        dialog.setContentView(panel, new ViewGroup.LayoutParams(Math.round(320 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (w != null) {
            w.setLayout(Math.round(320 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
        panel.getChildAt(1).requestFocus();
    }
}
