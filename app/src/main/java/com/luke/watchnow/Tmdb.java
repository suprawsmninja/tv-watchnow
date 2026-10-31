package com.luke.watchnow;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TMDB, for Australia: which provider id each service has, and titles that are streaming on them. TMDB's
 * availability data comes from JustWatch. Everything is a plain GET with the v3 key.
 */
final class Tmdb {
    static final String TAG = "WatchNow";
    static final String REGION = "AU";
    private static final String API = "https://api.themoviedb.org/3";
    static final String IMG = "https://image.tmdb.org/t/p/";

    private final String key;

    Tmdb(String key) {
        this.key = key;
    }

    boolean hasKey() {
        return key != null && !key.isEmpty();
    }

    private JSONObject get(String path, Map<String, String> params) throws IOException, JSONException {
        Uri.Builder b = Uri.parse(API + path).buildUpon().appendQueryParameter("api_key", key)
                .appendQueryParameter("language", "en-AU");
        if (params != null) for (Map.Entry<String, String> e : params.entrySet()) b.appendQueryParameter(e.getKey(), e.getValue());
        return new JSONObject(Http.text(b.build().toString()));
    }

    /** Sets providerId on every service TMDB lists for AU; logs the names it could not place. */
    void loadProviders() throws IOException, JSONException {
        List<String> unmatched = new ArrayList<>();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray results = get("/watch/providers/" + kind, Map.of("watch_region", REGION)).optJSONArray("results");
            if (results == null) continue;
            for (int i = 0; i < results.length(); i++) {
                JSONObject p = results.getJSONObject(i);
                String name = p.optString("provider_name");
                Services.Service s = Services.forTmdbName(name);
                if (s == null) {
                    if (unmatched.size() < 400 && !unmatched.contains(name)) unmatched.add(name);
                    continue;
                }
                // The first id for the plain service wins over "with ads" and channel variants.
                if (s.providerId == 0 || name.equalsIgnoreCase(s.tmdbNames[0])) s.providerId = p.optInt("provider_id");
            }
        }
        StringBuilder found = new StringBuilder();
        for (Services.Service s : Services.ALL) found.append(s.name).append('=').append(s.providerId).append(" app=").append(s.pkg).append("; ");
        Log.i(TAG, "TMDB AU providers: " + found);
        Log.d(TAG, "TMDB AU providers not used: " + unmatched);
    }

    /** One page of /discover for one service; each item is tagged with that service. */
    List<Item> discover(boolean tv, Services.Service s, Map<String, String> extra) throws IOException, JSONException {
        java.util.HashMap<String, String> p = new java.util.HashMap<>(extra);
        p.put("watch_region", REGION);
        p.put("with_watch_providers", String.valueOf(s.providerId));
        p.put("with_watch_monetization_types", "flatrate|free|ads");
        p.put("include_adult", "false");
        JSONArray results = get("/discover/" + (tv ? "tv" : "movie"), p).optJSONArray("results");
        List<Item> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            Item it = fromJson(results.getJSONObject(i), tv);
            if (it == null) continue;
            it.service = s;
            out.add(it);
        }
        return out;
    }

    /**
     * TMDB's "more like this" for a film, kept to what suits Family Movie Night: family or animation, rated G or PG
     * in Australia (or the US when Australia has no rating), and streaming on one of these services here.
     */
    List<Item> familyRecommendations(int movieId, List<Services.Service> services, int max) throws IOException, JSONException {
        List<Item> out = new ArrayList<>();
        JSONArray results = get("/movie/" + movieId + "/recommendations", null).optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < max; i++) {
            JSONObject o = results.getJSONObject(i);
            JSONArray genres = o.optJSONArray("genre_ids");
            boolean family = false;
            for (int g = 0; genres != null && g < genres.length(); g++) family |= genres.optInt(g) == 10751 || genres.optInt(g) == 16;
            if (!family) continue;
            Item it = fromJson(o, false);
            if (it == null || !kidSafe(it.tmdbId)) continue;
            it.service = streamingOn(it.tmdbId, services);
            if (it.service != null) out.add(it);
        }
        return out;
    }

    private boolean kidSafe(int movieId) throws IOException, JSONException {
        JSONArray countries = get("/movie/" + movieId + "/release_dates", null).optJSONArray("results");
        String au = null, us = null;
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.getJSONObject(i);
            JSONArray dates = c.optJSONArray("release_dates");
            String cert = null;
            for (int d = 0; dates != null && d < dates.length(); d++) {
                String x = dates.getJSONObject(d).optString("certification", "");
                if (!x.isEmpty()) cert = x;
            }
            if ("AU".equals(c.optString("iso_3166_1"))) au = cert;
            if ("US".equals(c.optString("iso_3166_1"))) us = cert;
        }
        if (au != null) return au.equals("G") || au.equals("PG");
        return us != null && (us.equals("G") || us.equals("PG"));
    }

    private Services.Service streamingOn(int movieId, List<Services.Service> services) throws IOException, JSONException {
        JSONObject au = get("/movie/" + movieId + "/watch/providers", null).optJSONObject("results");
        au = au == null ? null : au.optJSONObject(REGION);
        if (au == null) return null;
        for (String kind : new String[]{"flatrate", "free", "ads"}) {
            JSONArray list = au.optJSONArray(kind);
            for (int i = 0; list != null && i < list.length(); i++) {
                int id = list.getJSONObject(i).optInt("provider_id");
                for (Services.Service s : services) if (s.providerId == id) return s;
            }
        }
        return null;
    }

    /** The English (or language-free) logo for a title, at 500 wide; null when TMDB has none. */
    String logo(boolean tv, int id) throws IOException, JSONException {
        JSONArray logos = get("/" + (tv ? "tv" : "movie") + "/" + id + "/images",
                Map.of("include_image_language", "en,null")).optJSONArray("logos");
        if (logos == null || logos.length() == 0) return null;
        JSONObject best = null;
        for (int i = 0; i < logos.length(); i++) {
            JSONObject l = logos.getJSONObject(i);
            if (best == null || "en".equals(l.optString("iso_639_1")) && !"en".equals(best.optString("iso_639_1"))) best = l;
        }
        String path = best.optString("file_path", "");
        // An SVG logo would need a renderer the launcher does not have.
        return path.isEmpty() || path.endsWith(".svg") ? null : IMG + "w500" + path;
    }

    /** The best TMDB match for a title, for the Netflix Top 10's artwork. */
    Item search(String title, boolean tv) throws IOException, JSONException {
        JSONArray results = get("/search/" + (tv ? "tv" : "movie"), Map.of("query", title, "include_adult", "false"))
                .optJSONArray("results");
        if (results == null) return null;
        for (int i = 0; i < results.length(); i++) {
            Item it = fromJson(results.getJSONObject(i), tv);
            if (it != null) return it;
        }
        return null;
    }

    private static Item fromJson(JSONObject o, boolean tv) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return null;   // nothing to show on a card
        Item it = new Item();
        it.tv = tv;
        it.tmdbId = o.optInt("id");
        it.title = o.optString(tv ? "name" : "title");
        it.overview = o.optString("overview", "");
        it.popularity = o.optDouble("popularity", 0);
        it.image = IMG + "w780" + backdrop;
        it.backdrop = IMG + "w1280" + backdrop;
        String date = o.optString(tv ? "first_air_date" : "release_date", "");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) it.poster = IMG + "w342" + poster;
        StringBuilder genres = new StringBuilder();
        JSONArray ids = o.optJSONArray("genre_ids");
        for (int i = 0; ids != null && i < ids.length() && i < 2; i++) {
            String g = GENRES.get(ids.optInt(i));
            if (g != null) genres.append(genres.length() > 0 ? ", " : "").append(g);
        }
        it.subtitle = (year.isEmpty() ? (tv ? "TV" : "Movie") : year) + (genres.length() > 0 ? " · " + genres : "");
        return it;
    }

    /** TMDB's genre ids (movie and TV), for the line under a focused card. */
    private static final Map<Integer, String> GENRES = new java.util.HashMap<>();

    static {
        int[] ids = {28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 53, 10752, 37,
                10759, 10762, 10763, 10764, 10765, 10766, 10767, 10768};
        String[] names = {"Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family",
                "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "Thriller", "War",
                "Western", "Action & Adventure", "Kids", "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "Politics"};
        for (int i = 0; i < ids.length; i++) GENRES.put(ids[i], names[i]);
    }

    static String daysAgo(int days) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(System.currentTimeMillis() - days * 86400000L));
    }
}
