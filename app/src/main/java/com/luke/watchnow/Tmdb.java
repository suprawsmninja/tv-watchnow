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
        it.subtitle = (tv ? "TV" : "Movie") + (year.isEmpty() ? "" : " · " + year);
        return it;
    }

    static String daysAgo(int days) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(System.currentTimeMillis() - days * 86400000L));
    }
}
