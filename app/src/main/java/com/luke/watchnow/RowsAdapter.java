package com.luke.watchnow;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The vertical list of rows; each row is a header and a horizontal list of cards. */
final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.RowHolder> {

    interface Listener {
        void onFocused(int row, Item item);
        void onClicked(Item item);
        /** Held OK on a TMDB title: watched, thumbs up or down. */
        void onLongPressed(Item item, View card);
        /** The row's "Surprise Me" tile. */
        void onShuffle(Feed.Row row);
    }

    private final Context ctx;
    private final Images images;
    private final Listener listener;
    private final float dp;
    private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
    private List<Feed.Row> rows = new ArrayList<>();

    RowsAdapter(Context ctx, Images images, Listener listener) {
        this.ctx = ctx;
        this.images = images;
        this.listener = listener;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        pool.setMaxRecycledViews(0, 30);
    }

    void setRows(List<Feed.Row> rows) {
        this.rows = rows;
        notifyDataSetChanged();
    }

    List<Feed.Row> rows() {
        return rows;
    }

    @Override public int getItemCount() {
        return rows.size();
    }

    @NonNull @Override public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView header = new TextView(ctx);
        header.setTextColor(0xE6FFFFFF);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setPadding(px(56), 0, 0, px(4));
        row.addView(header);
        RecyclerView list = new RecyclerView(ctx);
        list.setLayoutManager(new RowLayoutManager(ctx));
        list.setRecycledViewPool(pool);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        // Room for the focused card to grow without being clipped.
        list.setPadding(px(56), px(10), px(56), px(14));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        row.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClipChildren(false);
        return new RowHolder(row, header, list);
    }

    @Override public void onBindViewHolder(@NonNull RowHolder h, int position) {
        Feed.Row row = rows.get(position);
        h.header.setText(row.title);
        h.list.setAdapter(new CardAdapter(row, position));
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView header;
        final RecyclerView list;

        RowHolder(View v, TextView header, RecyclerView list) {
            super(v);
            this.header = header;
            this.list = list;
        }
    }

    // ---- cards ----------------------------------------------------------------------------------

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final Feed.Row row;
        private final int rowIndex;

        CardAdapter(Feed.Row row, int rowIndex) {
            this.row = row;
            this.rowIndex = rowIndex;
        }

        @Override public int getItemCount() {
            return row.items.size();
        }

        @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(buildCard());
        }

        @Override public void onBindViewHolder(@NonNull CardHolder h, int position) {
            Item it = row.items.get(position);
            h.rank.setVisibility(row.ranked && it.rank > 0 ? View.VISIBLE : View.GONE);
            h.rank.setText(String.valueOf(it.rank));
            h.title.setText(it.title);
            h.badge.setVisibility(it.service == null ? View.GONE : View.VISIBLE);
            if (it.service != null) h.badge.setText(it.service.name);
            if (it.progress >= 0f) {
                h.progressTrack.setVisibility(View.VISIBLE);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.progress.getLayoutParams();
                lp.width = Math.max(px(6), Math.round(it.progress * (CARD_W * dp - px(24))));
                h.progress.setLayoutParams(lp);
            } else {
                h.progressTrack.setVisibility(View.GONE);
            }
            // Cover cards (Disney+ style): a portrait poster at rest, the wide picture and details once focused.
            boolean cover = it.poster != null && !it.poster.isEmpty() && !it.shuffle;
            h.cover = cover;
            h.wideTitle.setVisibility(View.GONE);
            setFrameWidth(h, cover ? POSTER_W : CARD_W, cover ? COVER_H : CARD_H);
            h.title.setVisibility(cover ? View.INVISIBLE : View.VISIBLE);
            if (cover) {
                h.title.setText(it.subtitle);
                h.wideTitle.setText(it.title);
                images.load(it.poster, px(POSTER_W), h.image);
            } else if (!it.shuffle) {
                images.load(it.image, Math.round(CARD_W * dp), h.image);
            }
            h.frame.setOnClickListener(v -> {
                if (it.shuffle) listener.onShuffle(row);
                else listener.onClicked(it);
            });
            h.frame.setOnLongClickListener(v -> {
                if (it.shuffle || it.tmdbId == 0) return false;
                listener.onLongPressed(it, v);
                return true;
            });
            h.shuffleLabel.setVisibility(it.shuffle ? View.VISIBLE : View.GONE);
            if (it.shuffle) {
                h.image.setImageResource(R.drawable.shuffle_tile);
                h.badge.setVisibility(View.GONE);
            }
            h.frame.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) step(h, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            });
            h.frame.setOnFocusChangeListener((v, focused) -> {
                if (focused) parkAtStart(h);
                if (h.cover) {
                    growTo(h, focused ? WIDE_W : POSTER_W);
                    h.title.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                    h.title.setTextColor(0xCCFFFFFF);
                    h.wideTitle.setVisibility(focused ? View.VISIBLE : View.GONE);
                    images.load(focused ? it.image : it.poster, px(focused ? WIDE_W : POSTER_W), h.image, true);
                } else {
                    float s = focused ? 1.1f : 1f;
                    h.itemView.animate().scaleX(s).scaleY(s).translationZ(focused ? px(12) : 0).setDuration(150).start();
                    h.title.setTextColor(focused ? 0xFFFFFFFF : 0x99FFFFFF);
                }
                if (focused) listener.onFocused(rowIndex, it);
            });
        }
    }

    /**
     * Left/Right move to the next card in this row only (never another row, never wrapping), even when that card is
     * not laid out yet: a growing cover card leaves the default focus search guessing.
     */
    private void step(CardHolder h, int by) {
        if (!(h.itemView.getParent() instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) h.itemView.getParent();
        RecyclerView.Adapter<?> a = rv.getAdapter();
        int target = h.getBindingAdapterPosition() + by;
        if (a == null || target < 0 || target >= a.getItemCount()) return;
        RecyclerView.ViewHolder t = rv.findViewHolderForAdapterPosition(target);
        if (t instanceof CardHolder) {
            ((CardHolder) t).frame.requestFocus();
            return;
        }
        rv.scrollToPosition(target);
        rv.post(() -> {
            RecyclerView.ViewHolder late = rv.findViewHolderForAdapterPosition(target);
            if (late instanceof CardHolder) ((CardHolder) late).frame.requestFocus();
        });
    }

    /** The focused card sits at the row's left edge, as on Disney+, so a card that grows to the right always fits. */
    private void parkAtStart(CardHolder h) {
        if (!(h.itemView.getParent() instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) h.itemView.getParent();
        int dx = h.itemView.getLeft() - rv.getPaddingLeft();
        if (dx != 0) rv.smoothScrollBy(dx, 0);
    }

    /** A row that scrolls only when parkAtStart says so. */
    private static final class RowLayoutManager extends LinearLayoutManager {
        RowLayoutManager(Context ctx) {
            super(ctx, HORIZONTAL, false);
        }

        @Override public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                              @NonNull android.graphics.Rect rect, boolean immediate,
                                                              boolean focusedChildVisible) {
            return false;
        }
    }

    static final int CARD_W = 220, CARD_H = 124;
    /** Cover cards: poster at rest, wide when focused, the same height either way. */
    static final int COVER_H = 168, POSTER_W = 112, WIDE_W = 299;

    private void setFrameWidth(CardHolder h, int wDp, int hDp) {
        if (h.grow != null) h.grow.cancel();
        ViewGroup.LayoutParams lp = h.frame.getLayoutParams();
        lp.width = px(wDp);
        lp.height = px(hDp);
        h.frame.setLayoutParams(lp);
        ViewGroup.LayoutParams tl = h.title.getLayoutParams();
        tl.width = px(wDp);
        h.title.setLayoutParams(tl);
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        h.itemView.setTranslationZ(0);
    }

    /** Animates the card's width; the row lays out around it as it grows, as Disney+ does. */
    private void growTo(CardHolder h, int wDp) {
        if (h.grow != null) h.grow.cancel();
        ViewGroup.LayoutParams lp = h.frame.getLayoutParams();
        h.grow = android.animation.ValueAnimator.ofInt(h.frame.getWidth() > 0 ? h.frame.getWidth() : lp.width, px(wDp));
        h.grow.setDuration(180);
        h.grow.setInterpolator(new android.view.animation.DecelerateInterpolator());
        h.grow.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            h.frame.setLayoutParams(lp);
            // The details line follows the card's width, or a poster's column would be as wide as a focused card.
            ViewGroup.LayoutParams tl = h.title.getLayoutParams();
            tl.width = lp.width;
            h.title.setLayoutParams(tl);
        });
        h.grow.start();
    }

    private View buildCard() {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.BOTTOM);
        outer.setClipChildren(false);
        RecyclerView.LayoutParams olp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMarginEnd(px(22));
        outer.setLayoutParams(olp);

        // Apple TV's Top 10 look: a big rank number tucked behind the card's left edge.
        TextView rank = new TextView(ctx);
        rank.setTextColor(0xD9FFFFFF);
        rank.setTextSize(TypedValue.COMPLEX_UNIT_SP, 84);
        rank.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        rank.setIncludeFontPadding(false);
        rank.setShadowLayer(16, 0, 4, 0x99000000);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMarginEnd(-px(14));
        rlp.bottomMargin = px(18);
        outer.addView(rank, rlp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        outer.addView(col);

        FrameLayout frame = new FrameLayout(ctx);
        frame.setFocusable(true);
        frame.setFocusableInTouchMode(true);
        frame.setBackgroundResource(R.drawable.card_bg);
        frame.setForeground(ctx.getDrawable(R.drawable.card_focus));
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * dp);
            }
        });
        frame.setClipToOutline(true);
        col.addView(frame, new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H)));

        ImageView image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(ctx);
        badge.setBackgroundResource(R.drawable.badge);
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setPadding(px(6), px(2), px(6), px(2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        blp.setMargins(px(8), px(8), 0, 0);
        frame.addView(badge, blp);

        // The title over the wide picture of a focused cover card, bottom left, as Disney+ puts its logos.
        TextView wideTitle = new TextView(ctx);
        wideTitle.setTextColor(0xFFFFFFFF);
        wideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        wideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        wideTitle.setShadowLayer(14, 0, 2, 0xE6000000);
        wideTitle.setMaxLines(2);
        wideTitle.setEllipsize(TextUtils.TruncateAt.END);
        wideTitle.setPadding(px(14), px(30), px(14), px(12));
        wideTitle.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        wideTitle.setVisibility(View.GONE);
        frame.addView(wideTitle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        TextView shuffleLabel = new TextView(ctx);
        shuffleLabel.setText("🎰  Surprise Me");
        shuffleLabel.setTextColor(0xFFFFFFFF);
        shuffleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        shuffleLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        shuffleLabel.setGravity(Gravity.CENTER);
        shuffleLabel.setVisibility(View.GONE);
        frame.addView(shuffleLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout track = new FrameLayout(ctx);
        track.setBackgroundColor(0x66FFFFFF);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4), Gravity.BOTTOM);
        tlp.setMargins(px(12), 0, px(12), px(10));
        frame.addView(track, tlp);
        View bar = new View(ctx);
        bar.setBackgroundColor(0xFFFFFFFF);
        track.addView(bar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(ctx);
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(px(CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = px(8);
        col.addView(title, tl);

        CardHolder.tags(outer, rank, frame, image, badge, track, bar, title, shuffleLabel, wideTitle);
        return outer;
    }

    static final class CardHolder extends RecyclerView.ViewHolder {
        final TextView rank, badge, title, shuffleLabel, wideTitle;
        boolean cover;
        android.animation.ValueAnimator grow;
        final FrameLayout frame, progressTrack;
        final ImageView image;
        final View progress;

        CardHolder(View v) {
            super(v);
            Object[] t = (Object[]) v.getTag();
            rank = (TextView) t[0];
            frame = (FrameLayout) t[1];
            image = (ImageView) t[2];
            badge = (TextView) t[3];
            progressTrack = (FrameLayout) t[4];
            progress = (View) t[5];
            title = (TextView) t[6];
            shuffleLabel = (TextView) t[7];
            wideTitle = (TextView) t[8];
        }

        static void tags(View outer, Object... parts) {
            outer.setTag(parts);
        }
    }
}
