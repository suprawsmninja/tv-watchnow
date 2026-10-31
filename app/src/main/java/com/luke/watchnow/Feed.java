package com.luke.watchnow;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Builds the rows below the hero. The TMDB and Netflix rows are cached on disk and rebuilt at most every
 * few hours; Continue watching is read fresh every time, as it is local and cheap.
 */
final class Feed {
    static final String TAG = "WatchNow";
    private static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final String NETFLIX_TOP10 = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv";

    static final class Row {
        final String title;
        final List<Item> items;
        final boolean ranked;

        Row(String title, List<Item> items, boolean ranked) {
            this.title = title;
            this.items = items;
            this.ranked = ranked;
        }
    }

    private Feed() {}

    static Row continueWatching(Context ctx) {
        return new Row("Continue Watching", WatchNext.read(ctx), false);
    }

    static boolean cacheFresh(Context ctx) {
        File f = cacheFile(ctx);
        return f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS;
    }

    /** The TMDB / Netflix rows from the network; saves them to the cache. Run off the main thread. */
    static List<Row> buildOnline(Context ctx, Tmdb tmdb) {
        List<Row> rows = new ArrayList<>();
        if (!tmdb.hasKey()) return rows;
        try {
            tmdb.loadProviders();
        } catch (IOException | JSONException e) {
            Log.w(TAG, "TMDB providers failed", e);
            return rows;
        }
        List<Services.Service> subs = Services.usable(false);
        List<Services.Service> all = Services.usable(true);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Future<List<Item>> topTv = pool.submit(() -> merged(tmdb, subs, true, Map.of("sort_by", "popularity.desc")));
            Future<List<Item>> topMovies = pool.submit(() -> merged(tmdb, subs, false, Map.of("sort_by", "popularity.desc")));
            Future<List<Item>> netflix = pool.submit(() -> netflixTop10(tmdb));
            Future<List<Item>> newMovies = pool.submit(() -> merged(tmdb, all, false, Map.of(
                    "sort_by", "popularity.desc", "primary_release_date.gte", Tmdb.daysAgo(120),
                    "primary_release_date.lte", Tmdb.daysAgo(0), "vote_count.gte", "10")));
            Future<List<Item>> newTv = pool.submit(() -> merged(tmdb, all, true, Map.of(
                    "sort_by", "popularity.desc", "air_date.gte", Tmdb.daysAgo(30), "air_date.lte", Tmdb.daysAgo(-7),
                    "first_air_date.gte", Tmdb.daysAgo(365))));
            Future<List<Item>> family = pool.submit(() -> merged(tmdb, all, false, Map.of(
                    "sort_by", "popularity.desc", "certification_country", "AU", "certification.lte", "PG",
                    "with_genres", "10751", "vote_average.gte", "6", "vote_count.gte", "50")));

            add(rows, "Top 10 Shows", rank(topTv.get(), 10), true);
            add(rows, "Top 10 Movies", rank(topMovies.get(), 10), true);
            add(rows, "Top 10 on Netflix in Australia", netflix.get(), true);
            List<Item> fresh = new ArrayList<>(newTv.get());
            fresh.addAll(newMovies.get());
            sortByPopularity(fresh);
            add(rows, "New on Your Services", first(dedupe(fresh), 20), false);
            add(rows, "Family Movie Night", first(family.get(), 20), false);
        } catch (Exception e) {
            Log.w(TAG, "building rows failed", e);
        } finally {
            pool.shutdownNow();
        }
        if (!rows.isEmpty()) save(ctx, rows);
        return rows;
    }

    private static void add(List<Row> rows, String title, List<Item> items, boolean ranked) {
        if (!items.isEmpty()) rows.add(new Row(title, items, ranked));
    }

    /** One discover call per service, merged by popularity, each title once. */
    private static List<Item> merged(Tmdb tmdb, List<Services.Service> services, boolean tv, Map<String, String> params) {
        List<Item> all = new ArrayList<>();
        for (Services.Service s : services) {
            try {
                all.addAll(tmdb.discover(tv, s, params));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "discover " + s.name + " failed", e);
            }
        }
        sortByPopularity(all);
        return dedupe(all);
    }

    /** This week's Netflix Top 10 TV in Australia. The file is sorted by country, newest week first, so only its start is read. */
    static List<Item> netflixTop10(Tmdb tmdb) {
        String[] titles = new String[11];
        String[] week = {null};
        try {
            Http.lines(NETFLIX_TOP10, line -> {
                String[] f = line.split("\t", -1);
                if (f.length < 7 || f[0].equals("country_name")) return true;
                // Sorted by country, then newest week first: skip to Australia, stop when its block or week ends.
                if (!f[1].equals("AU")) return week[0] == null;
                if (week[0] == null) week[0] = f[2];
                if (!f[2].equals(week[0])) return false;
                if (f[3].startsWith("TV")) {
                    try {
                        int rank = Integer.parseInt(f[4]);
                        if (rank >= 1 && rank <= 10) titles[rank] = f[5];
                    } catch (NumberFormatException ignored) {
                    }
                }
                return true;
            });
        } catch (IOException e) {
            Log.w(TAG, "Netflix Top 10 failed", e);
        }
        List<Item> out = new ArrayList<>();
        Services.Service netflixService = Services.byName("Netflix");
        for (int rank = 1; rank <= 10; rank++) {
            if (titles[rank] == null) continue;
            try {
                Item it = tmdb.search(titles[rank], true);
                if (it == null) continue;
                it.rank = rank;
                it.service = netflixService;
                out.add(it);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "search failed: " + titles[rank], e);
            }
        }
        Log.i(TAG, "Netflix AU Top 10 week " + week[0] + ": " + out.size() + " titles");
        return out;
    }

    private static List<Item> rank(List<Item> items, int n) {
        List<Item> out = first(items, n);
        for (int i = 0; i < out.size(); i++) out.get(i).rank = i + 1;
        return out;
    }

    private static void sortByPopularity(List<Item> items) {
        Collections.sort(items, (a, b) -> Double.compare(b.popularity, a.popularity));
    }

    private static List<Item> dedupe(List<Item> items) {
        Set<String> seen = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (seen.add(it.key())) out.add(it);
        return out;
    }

    private static List<Item> first(List<Item> items, int n) {
        return new ArrayList<>(items.subList(0, Math.min(n, items.size())));
    }

    // ---- cache ----------------------------------------------------------------------------------

    private static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), "rows.json");
    }

    private static void save(Context ctx, List<Row> rows) {
        try {
            JSONArray arr = new JSONArray();
            for (Row r : rows) {
                JSONObject o = new JSONObject().put("title", r.title).put("ranked", r.ranked);
                JSONArray items = new JSONArray();
                for (Item it : r.items) {
                    items.put(new JSONObject().put("title", it.title).put("subtitle", it.subtitle)
                            .put("overview", it.overview).put("image", it.image).put("backdrop", it.backdrop)
                            .put("service", it.service == null ? "" : it.service.name).put("rank", it.rank)
                            .put("tmdbId", it.tmdbId).put("tv", it.tv));
                }
                arr.put(o.put("items", items));
            }
            File tmp = new File(ctx.getFilesDir(), "rows.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(cacheFile(ctx))) Log.w(TAG, "cache rename failed");
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cache save failed", e);
        }
    }

    static List<Row> load(Context ctx) {
        List<Row> rows = new ArrayList<>();
        File f = cacheFile(ctx);
        if (!f.exists()) return rows;
        try {
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray items = o.getJSONArray("items");
                List<Item> list = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) {
                    JSONObject x = items.getJSONObject(j);
                    Item it = new Item();
                    it.title = x.optString("title");
                    it.subtitle = x.optString("subtitle");
                    it.overview = x.optString("overview");
                    it.image = x.optString("image");
                    it.backdrop = x.optString("backdrop");
                    it.service = Services.byName(x.optString("service"));
                    it.rank = x.optInt("rank");
                    it.tmdbId = x.optInt("tmdbId");
                    it.tv = x.optBoolean("tv");
                    list.add(it);
                }
                rows.add(new Row(o.getString("title"), list, o.optBoolean("ranked")));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cache load failed", e);
        }
        return rows;
    }
}
