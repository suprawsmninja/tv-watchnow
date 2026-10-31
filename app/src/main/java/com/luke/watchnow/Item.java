package com.luke.watchnow;

/** One card: a Watch Next row from an app, or a title from TMDB / the Netflix Top 10. */
final class Item {
    String title;
    /** Second line on the card and in the hero, e.g. "S2 · E4" or "Movie · 2026". */
    String subtitle = "";
    String overview = "";
    /** Landscape art for the card (and the hero when there is no backdrop). https:// or content:// */
    String image;
    /** Full-screen hero art. */
    String backdrop;
    /** The streaming service it is on, null if unknown. */
    Services.Service service;
    /** Watch Next: the app's own package and intent. */
    String pkg;
    String intentUri;
    /** 0..1, or -1 when there is no progress to show. */
    float progress = -1f;
    /** Top 10 rank, 0 when not ranked. */
    int rank;
    /** For sorting / de-duplicating. */
    long time;
    double popularity;
    int tmdbId;
    boolean tv;
    /** The "Surprise Me" tile at the end of Family Movie Night. */
    boolean shuffle;

    String key() {
        if (shuffle) return "shuffle";
        if (tmdbId != 0) return (tv ? "tv:" : "movie:") + tmdbId;
        return (pkg == null ? "" : pkg) + "|" + title;
    }
}
