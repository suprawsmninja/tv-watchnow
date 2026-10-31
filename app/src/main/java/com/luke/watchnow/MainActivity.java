package com.luke.watchnow;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watch Now: one screen in the style of the Apple TV app. The focused title fills the screen behind its name;
 * the rows below are Continue Watching (from the TV's Watch Next table) and TMDB / Netflix rows for the
 * services in the house, in Australia.
 */
public class MainActivity extends Activity implements RowsAdapter.Listener {
    private static final String TAG = "WatchNow";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Images images;
    private Tmdb tmdb;
    private RowsAdapter adapter;
    private RecyclerView rowsView;
    private ImageView heroA, heroB;
    private boolean heroAShown = true;
    private String heroUri;
    private TextView heroService, heroTitle, heroSub, heroOverview, clock, status;
    private int focusedRow = -1;
    private Item pendingHero;
    private boolean refreshing;

    private final Runnable showPendingHero = () -> {
        if (pendingHero != null) showHero(pendingHero);
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(MainActivity.this).format(new java.util.Date()));
            main.postDelayed(this, 30_000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        heroA = findViewById(R.id.heroA);
        heroB = findViewById(R.id.heroB);
        heroService = findViewById(R.id.heroService);
        heroTitle = findViewById(R.id.heroTitle);
        heroSub = findViewById(R.id.heroSub);
        heroOverview = findViewById(R.id.heroOverview);
        clock = findViewById(R.id.clock);
        status = findViewById(R.id.status);
        rowsView = findViewById(R.id.rows);

        images = new Images(this);
        tmdb = new Tmdb(BuildConfig.TMDB_KEY);
        adapter = new RowsAdapter(this, images, this);
        rowsView.setLayoutManager(new RowsLayoutManager(this));
        rowsView.setItemAnimator(null);
        rowsView.setAdapter(adapter);
        rowsView.setClipChildren(false);
    }

    @Override protected void onResume() {
        super.onResume();
        main.post(tick);
        load();
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        images.shutdown();
    }

    /** Local rows and the cached online rows straight away; then the online rows again if the cache is stale. */
    private void load() {
        if (refreshing) return;
        refreshing = true;
        Context app = getApplicationContext();
        work.execute(() -> {
            Services.resolve(app);
            Feed.Row cw = Feed.continueWatching(app);
            WatchNext.logPreviewPrograms(app);
            List<Feed.Row> cached = Feed.load(app);
            main.post(() -> show(cw, cached));
            if (!Feed.cacheFresh(app) || cached.isEmpty()) {
                List<Feed.Row> online = Feed.buildOnline(app, tmdb);
                if (!online.isEmpty()) main.post(() -> show(cw, online));
            }
            main.post(() -> {
                refreshing = false;
                updateStatus();
            });
        });
    }

    private void show(Feed.Row continueWatching, List<Feed.Row> online) {
        List<Feed.Row> rows = new ArrayList<>();
        if (!continueWatching.items.isEmpty()) rows.add(continueWatching);
        rows.addAll(online);
        if (sameRows(rows, adapter.rows())) return;
        boolean hadFocus = rowsView.hasFocus() || adapter.getItemCount() == 0;
        adapter.setRows(rows);
        updateStatus();
        if (rows.isEmpty()) return;
        if (hadFocus) {
            focusedRow = -1;
            rowsView.scrollToPosition(0);
            rowsView.post(() -> {
                RecyclerView.ViewHolder h = rowsView.findViewHolderForAdapterPosition(0);
                if (h != null) h.itemView.requestFocus();
            });
        }
        if (pendingHero == null) showHero(rows.get(0).items.get(0));
    }

    private static boolean sameRows(List<Feed.Row> a, List<Feed.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Feed.Row x = a.get(i), y = b.get(i);
            if (!x.title.equals(y.title) || x.items.size() != y.items.size()) return false;
            for (int j = 0; j < x.items.size(); j++) if (!x.items.get(j).key().equals(y.items.get(j).key())) return false;
        }
        return true;
    }

    private void updateStatus() {
        int msg = 0;
        if (!tmdb.hasKey()) msg = R.string.no_key;
        else if (adapter.getItemCount() == 0) msg = refreshing ? R.string.loading : R.string.nothing;
        status.setVisibility(msg == 0 ? View.GONE : View.VISIBLE);
        if (msg != 0) status.setText(msg);
    }

    // ---- focus and the hero ---------------------------------------------------------------------

    @Override public void onFocused(int row, Item item) {
        if (row != focusedRow) {
            focusedRow = row;
            LinearSmoothScroller s = new LinearSmoothScroller(this) {
                @Override protected int getVerticalSnapPreference() {
                    return SNAP_TO_START;
                }
            };
            s.setTargetPosition(row);
            RecyclerView.LayoutManager lm = rowsView.getLayoutManager();
            if (lm != null) lm.startSmoothScroll(s);
        }
        // Only once focus rests: scrolling along a row should not load every backdrop it passes.
        pendingHero = item;
        main.removeCallbacks(showPendingHero);
        main.postDelayed(showPendingHero, 220);
    }

    private void showHero(Item it) {
        heroTitle.setText(it.title);
        heroSub.setText(it.subtitle);
        heroOverview.setText(it.overview);
        heroService.setVisibility(it.service == null ? View.INVISIBLE : View.VISIBLE);
        if (it.service != null) {
            heroService.setText(it.service.name);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(it.service.color);
            bg.setCornerRadius(6 * getResources().getDisplayMetrics().density);
            heroService.setBackground(bg);
        }
        String uri = it.backdrop != null && !it.backdrop.isEmpty() ? it.backdrop : it.image;
        if (uri == null || uri.equals(heroUri)) return;
        heroUri = uri;
        ImageView next = heroAShown ? heroB : heroA;
        ImageView prev = heroAShown ? heroA : heroB;
        heroAShown = !heroAShown;
        next.animate().cancel();
        next.setAlpha(0f);
        images.load(uri, getResources().getDisplayMetrics().widthPixels, next);
        next.animate().alpha(1f).setStartDelay(120).setDuration(450).start();
        prev.animate().alpha(0f).setStartDelay(120).setDuration(450).start();
    }

    // ---- opening a title ------------------------------------------------------------------------

    @Override public void onClicked(Item it) {
        if (it.intentUri != null) {
            try {
                Intent i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Watch Next intent failed for " + it.pkg, e);
            }
        }
        String pkg = it.pkg != null ? it.pkg : it.service != null ? it.service.pkg : null;
        String name = it.service != null ? it.service.name : pkg;
        if (pkg == null) {
            Toast.makeText(this, getString(R.string.not_installed, name == null ? it.title : name), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, getString(R.string.not_installed, name), Toast.LENGTH_SHORT).show();
            return;
        }
        if (it.intentUri == null) Toast.makeText(this, getString(R.string.opening, name, it.title), Toast.LENGTH_LONG).show();
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The rows list scrolls itself (onFocused) instead of nudging the focused row just into view. */
    static final class RowsLayoutManager extends LinearLayoutManager {
        RowsLayoutManager(Context ctx) {
            super(ctx, VERTICAL, false);
        }

        @Override public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                              @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            return false;
        }

        @Override protected int getExtraLayoutSpace(RecyclerView.State state) {
            return 600;
        }
    }
}
