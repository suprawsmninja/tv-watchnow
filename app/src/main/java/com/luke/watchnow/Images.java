package com.luke.watchnow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads card and hero art: https (cached on disk for a week), content:// and android.resource://. Decoded
 * at about the size it is shown, into a memory cache sized to an eighth of the heap.
 */
final class Images {
    private static final String TAG = "WatchNow";
    private static final long DISK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory;
    private final File dir;

    Images(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(kb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        dir = new File(ctx.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        pool.execute(this::prune);
    }

    /** Drops disk-cached art older than a week, so the cache never grows past what the rows show. */
    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (now - f.lastModified() > DISK_MAX_AGE_MS) f.delete();
        }
    }

    /** Shows the image in the view once loaded, unless the view was given another one in the meantime. */
    void load(String uri, int widthPx, ImageView view) {
        view.setTag(R.id.image_uri, uri);
        if (uri == null || uri.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        String key = uri + "@" + widthPx;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bmp = decode(uri, widthPx);
            if (bmp == null) return;
            memory.put(key, bmp);
            main.post(() -> {
                if (uri.equals(view.getTag(R.id.image_uri))) view.setImageBitmap(bmp);
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private Bitmap decode(String uri, int widthPx) {
        try {
            byte[] data = read(uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "image failed: " + uri + ": " + e);
            return null;
        }
    }

    private byte[] read(String uri) throws IOException {
        if (uri.startsWith("https://") || uri.startsWith("http://")) {
            File f = new File(dir, Integer.toHexString(uri.hashCode()) + "_" + uri.length());
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_MAX_AGE_MS) {
                return Files.readAllBytes(f.toPath());
            }
            HttpURLConnection c = Http.open(uri);
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                data = Http.bytes(in);
            } finally {
                c.disconnect();
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return data;
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
            return in == null ? null : Http.bytes(in);
        }
    }
}
