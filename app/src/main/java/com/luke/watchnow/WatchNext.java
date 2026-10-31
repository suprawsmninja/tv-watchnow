package com.luke.watchnow;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Continue watching from the system's Watch Next table. With READ_TV_LISTINGS (granted over ADB) a query
 * returns this app's own rows plus every row another app marked searchable. A selection argument throws
 * without ACCESS_ALL_EPG_DATA, so the whole table is read and filtered here.
 */
final class WatchNext {
    static final String TAG = "WatchNow";
    /** Rows not touched for this long are left out: apps leave old rows behind for months. */
    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;

    private WatchNext() {}

    static List<Item> read(Context ctx) {
        List<Item> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        Map<String, Integer> perPackage = new HashMap<>();
        long now = System.currentTimeMillis();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return out;
            int iPkg = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
            int iTitle = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE);
            int iEpTitle = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
            int iSeason = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
            int iEpisode = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
            int iPoster = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
            int iThumb = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
            int iIntent = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
            int iType = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
            int iTime = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            int iPos = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
            int iDur = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
            int iDesc = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION);
            int iBrowsable = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_BROWSABLE);
            while (c.moveToNext()) {
                String pkg = str(c, iPkg);
                perPackage.merge(pkg == null ? "?" : pkg, 1, Integer::sum);
                if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.title = str(c, iTitle);
                if (it.title == null) continue;
                it.time = iTime >= 0 && !c.isNull(iTime) ? c.getLong(iTime) : 0;
                if (it.time > 0 && now - it.time > MAX_AGE_MS) continue;
                long pos = iPos >= 0 && !c.isNull(iPos) ? c.getLong(iPos) : 0;
                long dur = iDur >= 0 && !c.isNull(iDur) ? c.getLong(iDur) : 0;
                if (pos > 0 && dur > 0) {
                    it.progress = Math.min(1f, (float) pos / dur);
                    if (it.progress > 0.95f) continue;   // finished; the app just never removed it
                }
                String season = str(c, iSeason), episode = str(c, iEpisode), epTitle = str(c, iEpTitle);
                StringBuilder sub = new StringBuilder();
                if (season != null) sub.append('S').append(season);
                if (episode != null) sub.append(sub.length() > 0 ? " · " : "").append('E').append(episode);
                if (epTitle != null) sub.append(sub.length() > 0 ? "  " : "").append(epTitle);
                int type = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : 0;
                if (sub.length() == 0 && type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW) sub.append("New");
                it.subtitle = sub.toString();
                it.overview = str(c, iDesc) == null ? "" : str(c, iDesc);
                it.image = str(c, iPoster) != null ? str(c, iPoster) : str(c, iThumb);
                it.intentUri = str(c, iIntent);
                it.service = Services.forPackage(pkg);
                out.add(it);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Watch Next not readable: grant android.permission.READ_TV_LISTINGS", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Watch Next query failed", e);
        }
        Log.i(TAG, "Watch Next rows by app: " + perPackage + ", kept " + out.size());
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        // One card per show: apps sometimes publish both "continue" and "next episode" rows for it.
        Set<String> seen = new HashSet<>();
        List<Item> unique = new ArrayList<>();
        for (Item it : out) if (seen.add(it.pkg + "|" + it.title)) unique.add(it);
        return unique;
    }

    /** Preview channel rows per app, logged only: tells which apps share anything at all. */
    static void logPreviewPrograms(Context ctx) {
        Map<String, Integer> perPackage = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) perPackage.merge(c.isNull(0) ? "?" : c.getString(0), 1, Integer::sum);
        } catch (RuntimeException e) {
            Log.w(TAG, "preview programs query failed", e);
        }
        Log.i(TAG, "Preview programs by app: " + perPackage);
    }

    private static String str(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
