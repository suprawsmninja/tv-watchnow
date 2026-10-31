package com.luke.watchnow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Hands the launcher its shelf art, read-only:
 *   content://com.luke.watchnow.shelf/slides/<package>  rows of title, service and image (a content:// uri)
 *   content://com.luke.watchnow.shelf/img/<file>        the backdrop itself
 * Only public poster art goes through here. A query with stale art starts a refresh in the background.
 */
public final class ShelfProvider extends ContentProvider {
    static final String AUTHORITY = "com.luke.watchnow.shelf";

    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"title", "service", "image", "logo", "meta", "package"});
        List<String> path = uri.getPathSegments();
        if (getContext() == null || path.size() != 2 || !"slides".equals(path.get(0))) return c;
        ShelfArt.refreshIfStale(getContext());
        for (ShelfArt.Slide s : ShelfArt.read(getContext(), path.get(1))) {
            String base = "content://" + AUTHORITY + "/img/";
            c.addRow(new Object[]{s.title, s.service, base + s.file, s.logo.isEmpty() ? null : base + s.logo, s.meta, s.pkg});
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (getContext() == null || !"r".equals(mode) || path.size() != 2 || !"img".equals(path.get(0))) {
            throw new FileNotFoundException(uri.toString());
        }
        String name = path.get(1);
        if (name.contains("/") || name.contains("..")) throw new FileNotFoundException(name);
        File f = new File(ShelfArt.dir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(@NonNull Uri uri) {
        if (uri.getPathSegments().size() != 2 || !"img".equals(uri.getPathSegments().get(0))) return null;
        return uri.getLastPathSegment().endsWith(".png") ? "image/png" : "image/jpeg";
    }

    @Override public Uri insert(@NonNull Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
