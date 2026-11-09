package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Random;
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

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void poolSizeDefaultsClampsAndAllowsLegacyMode() throws Exception {
        assertEquals(120, Config.wallpaperPoolSize(null));
        assertEquals(120, Config.wallpaperPoolSize(new JSONObject()));
        int[] input = {-100, 0, 1, 10, 120, 500, 501};
        int[] expected = {-100, 0, 10, 10, 120, 500, 500};
        for (int i = 0; i < input.length; i++)
            assertEquals(expected[i], Config.wallpaperPoolSize(
                    new JSONObject().put("wallpaper_pool_size", input[i])));
    }

    @Test public void samplingHandlesLargerEqualSmallerAndEmptyManifest() {
        Set<String> keys = new LinkedHashSet<String>(Arrays.asList("a", "b", "c"));
        Set<String> empty = Collections.emptySet();
        assertEquals(keys, WallpaperSync.sample(keys, empty, 10, new Random(1)));
        assertEquals(keys, WallpaperSync.sample(keys, empty, 3, new Random(1)));
        Set<String> chosen = WallpaperSync.sample(keys, empty, 2, new Random(1));
        assertEquals(2, chosen.size());
        assertTrue(keys.containsAll(chosen));
        assertTrue(WallpaperSync.sample(empty, empty, 10, new Random(1)).isEmpty());
        assertEquals(Collections.singleton("c"), WallpaperSync.sample(keys,
                new LinkedHashSet<String>(Arrays.asList("a", "b")), 10, new Random(1)));
    }

    // Any processed-key read or preference write fails: pool mode must only read image.
    private SharedPreferences imageState(final String image) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                assertEquals("getString", method.getName());
                assertEquals("image", args[0]);
                return image;
            }
        });
    }

    private WallpaperSync.PoolProcessor processor(final boolean fail) {
        return new WallpaperSync.PoolProcessor() {
            @Override public void process(String path, long size, File target) throws Exception {
                if (fail) throw new IOException("Download failed");
                assertTrue(target.createNewFile());
            }
            @Override public void warn(String message) { }
        };
    }

    @Test public void cleanupKeepsSelectionAndCurrentlyDisplayedImage() throws Exception {
        File dir = temporary.newFolder();
        String current = WallpaperSync.key("current.jpg", 1);
        String selected = WallpaperSync.key("selected.jpg", 1);
        File old = new File(dir, WallpaperSync.key("old.jpg", 1) + ".jpg");
        File shown = new File(dir, current + ".jpg");
        File kept = new File(dir, selected + ".jpg");
        File unrelated = new File(dir, "local.jpg");
        assertTrue(old.createNewFile());
        assertTrue(shown.createNewFile());
        assertTrue(kept.createNewFile());
        assertTrue(unrelated.createNewFile());
        WallpaperSync.cleanPool(dir, Collections.singleton(selected),
                imageState(shown.getName()), processor(false));
        assertFalse(old.exists());
        assertTrue(shown.exists());
        assertTrue(kept.exists());
        assertTrue(unrelated.exists());
    }

    @Test public void poolFillsBeyondLegacySessionLimitWithoutProcessedPreferences() throws Exception {
        File dir = temporary.newFolder();
        Map<String, Long> manifest = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 125; i++) manifest.put("image" + i + ".jpg", 1L);
        assertFalse(WallpaperSync.syncPool(manifest, dir, 120, imageState(""),
                new Random(1), processor(false)));
        assertEquals(120, WallpaperSync.installedKeys(dir).size());
    }

    @Test public void oldKeysCanReturnInLaterPoolSessions() throws Exception {
        File dir = temporary.newFolder();
        Map<String, Long> manifest = new LinkedHashMap<String, Long>();
        manifest.put("a.jpg", 1L);
        manifest.put("b.jpg", 1L);
        manifest.put("c.jpg", 1L);
        SharedPreferences state = imageState("");
        assertFalse(WallpaperSync.syncPool(manifest, dir, 2, state, new Random(1), processor(false)));
        Set<String> first = WallpaperSync.installedKeys(dir);
        assertEquals(2, first.size());
        assertFalse(WallpaperSync.syncPool(manifest, dir, 2, state, new Random(1), processor(false)));
        Set<String> second = WallpaperSync.installedKeys(dir);
        assertEquals(1, second.size());
        assertTrue(Collections.disjoint(first, second));
        assertFalse(WallpaperSync.syncPool(manifest, dir, 2, state, new Random(1), processor(false)));
        assertEquals(first, WallpaperSync.installedKeys(dir));
    }

    @Test public void failedReplacementRetainsOldPool() throws Exception {
        File dir = temporary.newFolder();
        File old = new File(dir, WallpaperSync.key("old.jpg", 1) + ".jpg");
        assertTrue(old.createNewFile());
        assertTrue(WallpaperSync.syncPool(Collections.singletonMap("new.jpg", 1L),
                dir, 10, imageState(""), new Random(1), processor(true)));
        assertTrue(old.exists());
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
