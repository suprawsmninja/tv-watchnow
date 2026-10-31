package com.luke.watchnow;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the family has said about a title from the hold-OK menu, kept on the TV: watched and thumbs-down titles
 * leave the rows; thumbs-up titles seed Family Movie Night with TMDB's "more like this".
 */
final class Ratings {
    enum Mark { NONE, LIKED, DISLIKED }

    private static final String PREFS = "ratings";
    private final SharedPreferences prefs;

    Ratings(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean watched(Item it) {
        return set("watched").contains(it.key());
    }

    Mark mark(Item it) {
        if (set("liked").contains(it.key())) return Mark.LIKED;
        if (set("disliked").contains(it.key())) return Mark.DISLIKED;
        return Mark.NONE;
    }

    void setWatched(Item it, boolean on) {
        put("watched", it.key(), on);
    }

    void setMark(Item it, Mark m) {
        put("liked", it.key(), m == Mark.LIKED);
        put("disliked", it.key(), m == Mark.DISLIKED);
    }

    /** Titles that should not be offered again. */
    boolean hidden(Item it) {
        return it.tmdbId != 0 && (watched(it) || mark(it) == Mark.DISLIKED);
    }

    /** Liked films, newest first, as TMDB ids. */
    List<Integer> likedMovies() {
        List<Integer> out = new ArrayList<>();
        List<String> liked = new ArrayList<>(set("liked"));
        for (int i = liked.size() - 1; i >= 0; i--) {
            String k = liked.get(i);
            if (k.startsWith("movie:")) {
                try {
                    out.add(Integer.parseInt(k.substring(6)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    /** Insertion-ordered: stored as one string so the order (newest last) survives. */
    private Set<String> set(String name) {
        String raw = prefs.getString(name, "");
        Set<String> out = new LinkedHashSet<>();
        if (!raw.isEmpty()) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    private void put(String name, String key, boolean on) {
        Set<String> s = set(name);
        s.remove(key);
        if (on) s.add(key);
        prefs.edit().putString(name, String.join(",", s)).apply();
    }
}
