package com.sdevs.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads Continue Watching / Leanback Preview programs published by Nuvio (com.nuvio.tv)
 * and other Leanback apps via Android's TvProvider.
 */
final class NuvioContinueWatching {
    private static final String TAG = "TvHome.CW";

    private static final LruCache<String, Bitmap> POSTER_CACHE = new LruCache<String, Bitmap>(32) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    static final class Item {
        final long id;
        final String title;
        final String episodeDetail;
        final String timeRemaining;
        final String posterUri;
        final String intentUri;
        final String packageName;
        final float progress;
        Bitmap posterBitmap;
        int ambientColor;

        Item(long id, String title, String episodeDetail, String timeRemaining,
             String posterUri, String intentUri, String packageName, float progress) {
            this.id = id;
            this.title = title;
            this.episodeDetail = episodeDetail;
            this.timeRemaining = timeRemaining;
            this.posterUri = posterUri;
            this.intentUri = intentUri;
            this.packageName = (packageName != null && !packageName.isEmpty()) ? packageName : "com.nuvio.tv";
            this.progress = progress;
        }
    }

    private NuvioContinueWatching() {}

    static List<Item> query(Context context) {
        List<Item> items = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        ContentResolver cr = context.getContentResolver();

        // 1. First priority: Android TV's official Watch Next / Continue Watching table
        Uri watchNextUri = Uri.parse("content://android.media.tv/watch_next_program");
        extractItems(cr, watchNextUri, "last_engagement_time_utc_millis DESC", items, seenKeys);

        // 2. Also check preview_programs if more items are available
        if (items.size() < 16) {
            Uri previewUri = Uri.parse("content://android.media.tv/preview_program");
            extractItems(cr, previewUri, "weight DESC, _id DESC", items, seenKeys);
        }

        Log.i(TAG, "Total CW items selected: " + items.size());
        return items;
    }

    private static void extractItems(ContentResolver cr, Uri uri, String sortOrder, List<Item> out, Set<String> seenKeys) {
        try (Cursor c = cr.query(uri, null, null, null, sortOrder)) {
            Log.i(TAG, "Query " + uri + " -> cursor: " + (c != null ? c.getCount() : "null"));
            if (c == null) return;

            int colId = c.getColumnIndex("_id");
            int colPkg = c.getColumnIndex("package_name");
            int colTitle = c.getColumnIndex("title");
            int colEpTitle = c.getColumnIndex("episode_title");
            int colSeason = c.getColumnIndex("season_display_number");
            int colEpisode = c.getColumnIndex("episode_display_number");
            int colPoster = c.getColumnIndex("poster_art_uri");
            int colIntent = c.getColumnIndex("intent_uri");
            int colPos = c.getColumnIndex("last_playback_position_millis");
            int colDur = c.getColumnIndex("duration_millis");

            while (c.moveToNext()) {
                String pkg = colPkg >= 0 ? c.getString(colPkg) : "";
                if (pkg != null && !pkg.isEmpty() && !"com.nuvio.tv".equals(pkg) && !"com.stremio.one".equals(pkg)) {
                    continue;
                }
                long id = colId >= 0 ? c.getLong(colId) : 0;
                String title = colTitle >= 0 ? c.getString(colTitle) : "";
                String epTitle = colEpTitle >= 0 ? c.getString(colEpTitle) : "";
                String season = colSeason >= 0 ? c.getString(colSeason) : "";
                String episode = colEpisode >= 0 ? c.getString(colEpisode) : "";
                String poster = colPoster >= 0 ? c.getString(colPoster) : "";
                String intent = colIntent >= 0 ? c.getString(colIntent) : "";

                long pos = colPos >= 0 ? c.getLong(colPos) : 0;
                long dur = colDur >= 0 ? c.getLong(colDur) : 0;
                float progress = 0f;
                if (dur > 0 && pos > 0) {
                    progress = Math.min(0.95f, Math.max(0.08f, (float) pos / (float) dur));
                } else {
                    progress = 0.35f; // subtle default
                }

                String timeRem = formatRemaining(pos, dur);
                String epDetail = formatEpisode(season, episode, epTitle);

                if (title != null && !title.trim().isEmpty()) {
                    String key = title.trim().toLowerCase();
                    if (seenKeys.add(key)) {
                        Log.i(TAG, "CW: " + title.trim() + " (" + epDetail + ", " + timeRem + ") -> " + poster);
                        out.add(new Item(id, title.trim(), epDetail, timeRem, poster, intent, pkg, progress));
                        if (out.size() >= 24) break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot query " + uri + ": " + e.getMessage());
        }
    }

    static String formatRemaining(long pos, long dur) {
        if (pos <= 0 || dur <= 0 || dur <= pos) return null;
        long rem = dur - pos;
        if (rem < 60_000L) return "< 1m left";
        long mins = rem / 60_000L;
        if (mins < 60) return mins + "m left";
        long hours = mins / 60;
        long remMins = mins % 60;
        if (remMins > 0) return hours + "h " + remMins + "m left";
        return hours + "h left";
    }

    static String formatEpisode(String season, String episode, String epTitle) {
        StringBuilder sb = new StringBuilder();
        if (season != null && !season.trim().isEmpty() && episode != null && !episode.trim().isEmpty()) {
            sb.append("S").append(season.trim()).append(" \u00B7 E").append(episode.trim());
        }
        if (epTitle != null && !epTitle.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(epTitle.trim());
        }
        return sb.toString();
    }

    static Bitmap loadPoster(Context context, String uriString) {
        if (uriString == null || uriString.isEmpty()) return null;
        Bitmap cached = POSTER_CACHE.get(uriString);
        if (cached != null) return cached;

        try {
            Uri uri = Uri.parse(uriString);
            InputStream is = null;
            if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                is = context.getContentResolver().openInputStream(uri);
            } else if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                HttpURLConnection conn = (HttpURLConnection) new URL(uriString).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(7000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    is = conn.getInputStream();
                }
            }
            if (is != null) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                is.close();
                if (bmp != null) {
                    POSTER_CACHE.put(uriString, bmp);
                    return bmp;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to decode poster: " + e.getMessage());
        }
        return null;
    }
}
