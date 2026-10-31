package com.luke.watchnow;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The streaming services in the house: how TMDB names each one for Australia, how to find its app on the
 * TV (by package, then by the app's label, so a renamed or regional package still matches), and its colour.
 */
final class Services {

    static final class Service {
        final String name;
        /** Lower-case names TMDB uses for it in the AU provider list; matched exactly. */
        final String[] tmdbNames;
        /** Known packages first, then lower-case words the app's label contains. */
        final String[] packages;
        final String[] labelWords;
        final int color;
        /** Free-to-air catch-up: in the family and new rows, not in the subscription Top 10. */
        final boolean fta;
        int providerId;
        String pkg;

        Service(String name, String[] tmdbNames, String[] packages, String[] labelWords, int color, boolean fta) {
            this.name = name;
            this.tmdbNames = tmdbNames;
            this.packages = packages;
            this.labelWords = labelWords;
            this.color = color;
            this.fta = fta;
        }
    }

    static final List<Service> ALL = new ArrayList<>();

    static {
        ALL.add(new Service("Netflix", new String[]{"netflix", "netflix basic with ads", "netflix standard with ads"},
                new String[]{"com.netflix.ninja"}, new String[]{"netflix"}, 0xFFE50914, false));
        ALL.add(new Service("Disney+", new String[]{"disney plus", "disney+"},
                new String[]{"com.disney.disneyplus"}, new String[]{"disney"}, 0xFF113CCF, false));
        ALL.add(new Service("Prime Video", new String[]{"amazon prime video", "amazon prime video with ads", "prime video"},
                new String[]{"com.amazon.amazonvideo.livingroom"}, new String[]{"prime video"}, 0xFF1A98FF, false));
        ALL.add(new Service("Apple TV", new String[]{"apple tv plus", "apple tv+", "apple tv"},
                new String[]{"com.apple.atve.sony.appletv", "com.apple.atve.androidtv.appletv"}, new String[]{"apple tv"}, 0xFF2B2B2E, false));
        ALL.add(new Service("Max", new String[]{"max", "hbo max", "max amazon channel"},
                new String[]{"com.wbd.stream", "com.hbo.hbonow"}, new String[]{"hbo max", "max"}, 0xFF002BE7, false));
        ALL.add(new Service("ABC iview", new String[]{"abc iview"},
                new String[]{"au.net.abc.iview"}, new String[]{"iview"}, 0xFF1C1C1C, true));
        ALL.add(new Service("SBS On Demand", new String[]{"sbs on demand"},
                new String[]{}, new String[]{"sbs"}, 0xFFFFC20E, true));
        ALL.add(new Service("7plus", new String[]{"7plus"},
                new String[]{}, new String[]{"7plus", "7 plus"}, 0xFFE4002B, true));
        ALL.add(new Service("9Now", new String[]{"9now"},
                new String[]{}, new String[]{"9now"}, 0xFF00A6E3, true));
        ALL.add(new Service("10", new String[]{"10 play", "10play", "10"},
                new String[]{}, new String[]{"10 play", "10play", "10"}, 0xFF0045B5, true));
    }

    private Services() {}

    /** Finds each service's app on this TV. Call once, off the main thread. */
    static void resolve(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        for (Service s : ALL) {
            s.pkg = null;
            for (String p : s.packages) {
                if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) {
                    s.pkg = p;
                    break;
                }
            }
            if (s.pkg != null) continue;
            for (ResolveInfo ri : apps) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).trim();
                if (matchesLabel(label, s.labelWords)) {
                    s.pkg = ri.activityInfo.packageName;
                    break;
                }
            }
        }
    }

    /** A short word ("max", "10") must be the whole label; a longer one may be part of it. */
    static boolean matchesLabel(String label, String[] words) {
        for (String w : words) {
            if (w.length() <= 3 ? label.equals(w) : label.contains(w)) return true;
        }
        return false;
    }

    static Service forPackage(String pkg) {
        if (pkg == null) return null;
        for (Service s : ALL) if (pkg.equals(s.pkg)) return s;
        return null;
    }

    static Service forTmdbName(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        for (Service s : ALL) for (String t : s.tmdbNames) if (n.equals(t)) return s;
        return null;
    }

    static Service byName(String name) {
        for (Service s : ALL) if (s.name.equals(name)) return s;
        return null;
    }

    /** Services the TV has an app for and TMDB has an AU provider id for. */
    static List<Service> usable(boolean includeFta) {
        List<Service> out = new ArrayList<>();
        for (Service s : ALL) if (s.pkg != null && s.providerId != 0 && (includeFta || !s.fta)) out.add(s);
        return out;
    }
}
