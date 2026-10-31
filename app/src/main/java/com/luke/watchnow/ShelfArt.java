package com.luke.watchnow;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen art for the launcher's shelf: for each service's app, the backdrops of what is popular on it in
 * Australia, downloaded here so the launcher (which has no network access) reads them through ShelfProvider.
 * Watch Now's own tile gets a mix of every service.
 */
final class ShelfArt {
    private static final String TAG = "WatchNow";
    static final long MAX_AGE_MS = 12L * 60 * 60 * 1000;
    private static final int PER_APP = 8;
    private static final Object LOCK = new Object();
    private static volatile boolean running;

    static final class Slide {
        final String title, file, service;

        Slide(String title, String file, String service) {
            this.title = title;
            this.file = file;
            this.service = service;
        }
    }

    private ShelfArt() {}

    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "shelf");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File index(Context ctx) {
        return new File(ctx.getFilesDir(), "shelf.json");
    }

    static boolean stale(Context ctx) {
        File f = index(ctx);
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS;
    }

    /** Starts a rebuild in the background unless one is running or the art is fresh. */
    static void refreshIfStale(Context ctx) {
        if (running || !stale(ctx)) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> refresh(app), "shelf-art").start();
    }

    static void refresh(Context ctx) {
        synchronized (LOCK) {
            if (running) return;
            running = true;
        }
        try {
            Tmdb tmdb = new Tmdb(BuildConfig.TMDB_KEY);
            if (!tmdb.hasKey()) return;
            Services.resolve(ctx);
            tmdb.loadProviders();
            JSONObject out = new JSONObject();
            List<List<Item>> perService = new ArrayList<>();
            Set<String> keep = new HashSet<>();
            for (Services.Service s : Services.usable(true)) {
                List<Item> items = new ArrayList<>();
                for (boolean tv : new boolean[]{true, false}) {
                    try {
                        // What is current: shows with an episode in the last six months, films from the last two years.
                        items.addAll(tmdb.discover(tv, s, tv
                                ? Map.of("sort_by", "popularity.desc", "vote_count.gte", "30", "air_date.gte", Tmdb.daysAgo(180),
                                        "without_genres", "10763,10764,10767")   // no news, reality or talk shows
                                : Map.of("sort_by", "popularity.desc", "vote_count.gte", "30", "primary_release_date.gte", Tmdb.daysAgo(730))));
                    } catch (IOException | JSONException e) {
                        Log.w(TAG, "shelf art " + s.name + " failed", e);
                    }
                }
                Collections.sort(items, (a, b) -> Double.compare(b.popularity, a.popularity));
                // Netflix publishes what Australia actually watched this week: that goes first.
                if ("Netflix".equals(s.name)) items.addAll(0, Feed.netflixTop10(tmdb));
                Set<String> seen = new HashSet<>();
                items.removeIf(it -> !seen.add(it.key()));
                List<Item> picked = new ArrayList<>();
                for (Item it : items) {
                    if (picked.size() >= PER_APP) break;
                    if (download(ctx, it) != null) picked.add(it);
                }
                if (picked.isEmpty()) continue;
                out.put(s.pkg, slides(picked, keep));
                if (!s.fta) perService.add(picked);
            }
            // Watch Now's tile: the top two of each subscription in turn.
            List<Item> mix = new ArrayList<>();
            for (int i = 0; i < 2; i++) for (List<Item> list : perService) if (list.size() > i) mix.add(list.get(i));
            if (!mix.isEmpty()) out.put(ctx.getPackageName(), slides(mix, keep));
            File tmp = new File(ctx.getFilesDir(), "shelf.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(index(ctx))) Log.w(TAG, "shelf index rename failed");
            File[] files = dir(ctx).listFiles();
            if (files != null) for (File f : files) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
                f.delete();
            Log.i(TAG, "shelf art for " + out.length() + " apps");
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "shelf art failed", e);
        } finally {
            running = false;
        }
    }

    private static JSONArray slides(List<Item> items, Set<String> keep) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            String file = fileName(it);
            keep.add(file);
            arr.put(new JSONObject().put("title", it.title).put("file", file)
                    .put("service", it.service == null ? "" : it.service.name));
        }
        return arr;
    }

    private static String fileName(Item it) {
        return (it.tv ? "tv" : "movie") + "_" + it.tmdbId + ".jpg";
    }

    /** The backdrop at 1280 wide, kept on disk; null when it could not be fetched. */
    private static File download(Context ctx, Item it) {
        File f = new File(dir(ctx), fileName(it));
        if (f.exists() && f.length() > 0) return f;
        if (it.backdrop == null) return null;
        try {
            HttpURLConnection c = Http.open(it.backdrop);
            File tmp = new File(dir(ctx), f.getName() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            return tmp.renameTo(f) ? f : null;
        } catch (IOException e) {
            Log.d(TAG, "backdrop failed: " + it.title + ": " + e);
            return null;
        }
    }

    static List<Slide> read(Context ctx, String pkg) {
        List<Slide> out = new ArrayList<>();
        File f = index(ctx);
        if (!f.exists()) return out;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray arr = o.optJSONArray(pkg);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                out.add(new Slide(s.optString("title"), s.optString("file"), s.optString("service")));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "shelf index unreadable", e);
        }
        return out;
    }
}
