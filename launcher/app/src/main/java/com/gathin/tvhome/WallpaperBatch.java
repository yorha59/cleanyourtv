package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Pure, top-level Java helper: no Context, files, clock or Android JSON stubs in tests. */
final class WallpaperBatch {
    static final class Entry {
        final String path, url;
        Entry(String path, String url) { this.path = path; this.url = url; }
    }

    static List<Entry> parseManifest(String text) throws JSONException {
        JSONArray array = new JSONArray(text);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            Object index = item.get("i");
            if (!(index instanceof Integer) || ((Integer) index) != i)
                throw new JSONException("Manifest indices must be consecutive from zero");
            String path = item.getString("path"), url = item.getString("url");
            try {
                URI remote = new URI(url);
                if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("..")
                        || !("http".equalsIgnoreCase(remote.getScheme())
                        || "https".equalsIgnoreCase(remote.getScheme()))
                        || remote.getHost() == null || remote.getUserInfo() != null)
                    throw new IllegalArgumentException();
            } catch (Exception e) { throw new JSONException("Invalid manifest path or HTTP URL at " + i); }
            entries.add(new Entry(path, url));
        }
        return entries;
    }

    static List<Integer> indices(int next, int size, int total) {
        List<Integer> result = new ArrayList<>();
        if (total <= 0 || size <= 0) return result;
        int start = Math.floorMod(next, total);
        for (int i = 0; i < Math.min(size, total); i++) result.add((int) (((long) start + i) % total));
        return result;
    }
}
