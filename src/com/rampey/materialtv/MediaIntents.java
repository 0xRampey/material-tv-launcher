package com.rampey.materialtv;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

/** Android app integration, not a command parser: the LLM supplies the full query and installed package. */
final class MediaIntents {
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String SPOTIFY = "com.spotify.tv.android";

    static List<Intent> candidates(String pkg, String query, boolean play) {
        List<Intent> out = new ArrayList<>();
        if (pkg == null || pkg.isEmpty() || query == null || query.trim().isEmpty() || query.length() > 1000) return out;
        if (play) {
            out.add(playSearch(pkg, query));
            // YouTube TV declares PLAY_FROM_SEARCH only with its URL/scheme filters.
            if (YOUTUBE.equals(pkg)) out.add(playSearch(pkg, query).setData(searchLink(pkg, query)));
        }
        out.add(new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query));
        Uri link = searchLink(pkg, query);
        if (link != null) out.add(new Intent(Intent.ACTION_VIEW, link).setPackage(pkg));
        return out;
    }

    private static Intent playSearch(String pkg, String query) {
        return new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra("android.intent.extra.START_PLAYBACK", true);
    }

    private static Uri searchLink(String pkg, String query) {
        if (YOUTUBE.equals(pkg)) return new Uri.Builder().scheme("https").authority("www.youtube.com")
                .path("/results").appendQueryParameter("search_query", query).build();
        if (SPOTIFY.equals(pkg)) return Uri.parse("spotify:search:" + Uri.encode(query));
        return null;
    }

    static boolean requestsPlayback(Intent intent) {
        return MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction());
    }

    static boolean safeLink(String pkg, Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if (YOUTUBE.equals(pkg)) return ("https".equals(scheme) || "http".equals(scheme))
                && ("youtube.com".equals(uri.getHost()) || "www.youtube.com".equals(uri.getHost())
                    || "m.youtube.com".equals(uri.getHost()) || "youtu.be".equals(uri.getHost()))
                || "vnd.youtube".equals(scheme);
        if (SPOTIFY.equals(pkg)) return "spotify".equals(scheme)
                || "https".equals(scheme) && "open.spotify.com".equals(uri.getHost());
        return "https".equals(scheme) || "http".equals(scheme);
    }
}
