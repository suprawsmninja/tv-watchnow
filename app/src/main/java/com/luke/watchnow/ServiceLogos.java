package com.luke.watchnow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;

/**
 * Each service's wordmark, a transparent PNG from TMDB's network logos, for the header. Fetched once and kept on
 * disk; drawn white so the black and coloured ones read the same over any art.
 */
final class ServiceLogos {
    private static final String TAG = "WatchNow";
    private static final Map<String, Bitmap> loaded = new HashMap<>();

    private ServiceLogos() {}

    private static File file(Context ctx, Services.Service s) {
        File dir = new File(ctx.getFilesDir(), "svc");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, s.networkId + ".png");
    }

    /** Downloads any logo not on disk yet. Off the main thread. */
    static void ensure(Context ctx, String key) {
        if (key == null || key.isEmpty()) return;
        for (Services.Service s : Services.ALL) {
            if (s.networkId == 0) continue;
            File f = file(ctx, s);
            if (f.exists() && f.length() > 0) continue;
            try {
                JSONObject n = new JSONObject(Http.text("https://api.themoviedb.org/3/network/" + s.networkId + "?api_key=" + key));
                String path = n.optString("logo_path", "");
                if (path.isEmpty() || !path.endsWith(".png")) continue;
                HttpURLConnection c = Http.open(Tmdb.IMG + "w300" + path);
                File tmp = new File(f.getPath() + ".part");
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(Http.bytes(in));
                } finally {
                    c.disconnect();
                }
                if (!tmp.renameTo(f)) Log.w(TAG, "logo rename failed for " + s.name);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "service logo " + s.name + " failed", e);
            }
        }
    }

    /** The wordmark, or null when there is none (the header then shows the name as text). Main thread is fine: tiny files. */
    static Bitmap get(Context ctx, Services.Service s) {
        if (s == null || s.networkId == 0) return null;
        String k = s.name;
        if (loaded.containsKey(k)) return loaded.get(k);
        File f = file(ctx, s);
        Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (b != null) loaded.put(k, b);
        return b;
    }
}
