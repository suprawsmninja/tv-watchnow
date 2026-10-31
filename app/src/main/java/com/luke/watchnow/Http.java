package com.luke.watchnow;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Http {
    private Http() {}

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "WatchNow/0.1 (Android TV)");
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + url.replaceAll("api_key=[^&]*", "api_key=…"));
        }
        return c;
    }

    static String text(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return new String(bytes(in), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Reads lines until the handler returns false, then hangs up: for big files whose start is enough. */
    interface Lines { boolean line(String line); }

    static void lines(String url, Lines handler) throws IOException {
        HttpURLConnection c = open(url);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!handler.line(line)) break;
        } finally {
            c.disconnect();
        }
    }
}
