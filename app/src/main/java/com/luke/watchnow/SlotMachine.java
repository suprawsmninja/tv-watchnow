package com.luke.watchnow;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * "Surprise Me": a fruit-machine reel that spins through Family Movie Night and slows to a stop on one film.
 * OK watches it; Left or Right spins again; Back closes.
 */
final class SlotMachine {
    private final Activity activity;
    private final Images images;
    private final List<Item> films;
    private final Consumer<Item> onPick;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final float dp;
    private Dialog dialog;
    private ImageView reel;
    private TextView title, hint;
    private int at;
    private boolean spinning;
    private Item picked;

    SlotMachine(Activity activity, Images images, List<Item> films, Consumer<Item> onPick) {
        this.activity = activity;
        this.images = images;
        this.films = new ArrayList<>(films);
        this.onPick = onPick;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    void show() {
        if (films.isEmpty()) return;
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xE6000000));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextView heading = text(18, 0xB3FFFFFF);
        heading.setText("🎰  Family Movie Night");
        heading.setLetterSpacing(0.1f);
        col.addView(heading);

        // The reel: one window onto the film strip, in a gold arcade frame.
        FrameLayout frame = new FrameLayout(activity);
        GradientDrawable gold = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFD54F, 0xFFFF8F00});
        gold.setCornerRadius(px(26));
        frame.setBackground(gold);
        frame.setPadding(px(10), px(10), px(10), px(10));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(px(520), px(298));
        flp.topMargin = px(20);
        col.addView(frame, flp);
        reel = new ImageView(activity);
        reel.setScaleType(ImageView.ScaleType.CENTER_CROP);
        reel.setBackgroundColor(0xFF111111);
        reel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 18 * dp);
            }
        });
        reel.setClipToOutline(true);
        frame.addView(reel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        title = text(30, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = px(22);
        col.addView(title, tlp);
        hint = text(15, 0x99FFFFFF);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = px(8);
        col.addView(hint, hlp);

        dialog.setContentView(col);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP) return keyCode != KeyEvent.KEYCODE_BACK;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!spinning && picked != null) {
                        dialog.dismiss();
                        onPick.accept(picked);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!spinning) spin();
                    return true;
                default:
                    return false;
            }
        });
        dialog.setOnDismissListener(d -> main.removeCallbacksAndMessages(null));
        dialog.show();
        at = random.nextInt(films.size());
        spin();
    }

    /** About 3 seconds: fast at first, each step a little slower, then a bump as it lands. */
    private void spin() {
        spinning = true;
        picked = null;
        hint.setText("");
        int steps = 18 + random.nextInt(films.size() + 4);
        step(steps, 45);
    }

    private void step(int left, long delay) {
        at = (at + 1) % films.size();
        Item it = films.get(at);
        images.load(it.image, px(520), reel);
        title.setText(it.title);
        reel.setTranslationY(-px(24));
        reel.animate().translationY(0).setDuration(Math.min(delay, 220)).start();
        if (left <= 0) {
            land(it);
            return;
        }
        long next = left < 6 ? (long) (delay * 1.45) : (long) (delay * 1.07);
        main.postDelayed(() -> step(left - 1, next), delay);
    }

    private void land(Item it) {
        spinning = false;
        picked = it;
        reel.animate().cancel();
        reel.setTranslationY(0);   // the cancelled drop would otherwise leave the picture short of the frame
        reel.setScaleX(1.08f);
        reel.setScaleY(1.08f);
        reel.animate().scaleX(1f).scaleY(1f).setDuration(380).start();
        String where = it.service != null ? " on " + it.service.name : "";
        hint.setText("OK to watch" + where + "   ·   ◀ ▶ spin again");
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        return t;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
