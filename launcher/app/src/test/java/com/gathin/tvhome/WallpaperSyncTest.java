package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class WallpaperSyncTest {
    @Test public void parsesOriginalPathsInOrder() throws Exception {
        Map<String, Long> entries = WallpaperSync.parseManifest("{\"files\":["
                + "{\"p\":\"目录/子目录/文件 名,1.JPG\",\"s\":7914432},"
                + "{\"p\":\"a-b_2.jpeg\",\"s\":0},"
                + "{\"p\":\"a.bmp\",\"s\":12},{\"p\":\"a.PNG\",\"s\":42}]}");
        assertEquals(Arrays.asList("目录/子目录/文件 名,1.JPG", "a-b_2.jpeg", "a.bmp", "a.PNG"),
                new ArrayList<String>(entries.keySet()));
        assertEquals(Long.valueOf(7914432), entries.get("目录/子目录/文件 名,1.JPG"));
        assertTrue(WallpaperSync.parseManifest("{\"files\":[]}").isEmpty());
    }

    @Test public void rejectsMalformedManifests() {
        for (String json : Arrays.asList("not json", "{}", "{\"files\":[{}]}",
                "{\"files\":[{\"n\":\"old.jpg\",\"s\":12}]}",
                "{\"files\":[{\"p\":12,\"s\":12}]}")) rejects(json);
        for (String size : Arrays.asList("-1", "1.5", "1.0", "true", "\"12\"", "9223372036854775808"))
            rejects("{\"files\":[{\"p\":\"a.jpg\",\"s\":" + size + "}]}");
        rejects("{\"files\":[{\"p\":\"a.jpg\",\"s\":1},{\"p\":\"a.jpg\",\"s\":2}]}");
    }

    @Test public void rejectsUnsafePathsAndExtensions() {
        for (String path : Arrays.asList("../a.jpg", "dir/../a.jpg", "a..jpg", "/a.jpg",
                "dir\\a.jpg", "%2e%2e.jpg", "a.gif", "a.webp", "a.jpg?x=1", "a.jpg#x", "a:1.jpg", "a\n.jpg"))
            rejects("{\"files\":[{\"p\":" + JSONObject.quote(path) + ",\"s\":12}]}");
    }

    @Test public void keyUsesUtf8PathAndSize() {
        assertEquals("bb0da44b4c9aba34", WallpaperSync.key("目录/子目录/文件 名,1.JPG", 7914432));
        assertFalse(WallpaperSync.key("a.jpg", 12).equals(WallpaperSync.key("a.jpg", 13)));
        assertFalse(WallpaperSync.key("a.jpg", 12).equals(WallpaperSync.key("b.jpg", 12)));
    }

    @Test public void processedKeysRoundTripAndDeduplicate() {
        Set<String> keys = WallpaperSync.processedKeys("");
        assertTrue(keys.isEmpty());
        String first = WallpaperSync.key("a.jpg", 12), second = WallpaperSync.key("b.jpg", 13);
        keys.add(first);
        keys.add(first);
        assertEquals(first, WallpaperSync.saveKeys(keys));
        keys = WallpaperSync.processedKeys(WallpaperSync.saveKeys(keys));
        keys.add(second);
        assertEquals(first + "," + second, WallpaperSync.saveKeys(keys));
        assertEquals(keys, WallpaperSync.processedKeys(first + ",invalid,," + second + "," + first));
        keys.remove(second); // A failed persistence can roll back the in-memory addition.
        assertEquals(first, WallpaperSync.saveKeys(keys));
    }

    private static void rejects(String json) {
        try {
            WallpaperSync.parseManifest(json);
            fail("Expected invalid manifest to be rejected");
        } catch (JSONException expected) {
            // Invalid input never becomes a download path.
        }
    }
}
