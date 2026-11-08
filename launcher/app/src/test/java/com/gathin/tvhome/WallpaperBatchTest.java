package com.gathin.tvhome;

import org.junit.Test;
import org.json.JSONException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class WallpaperBatchTest {
    @Test public void wrapsBatchAcrossManifestEnd() {
        assertEquals(Arrays.asList(3, 4, 0, 1), WallpaperBatch.indices(3, 4, 5));
        assertEquals(Arrays.asList(1, 2, 0), WallpaperBatch.indices(1, 42, 3));
    }
    @Test public void emptyManifestHasNoWork() throws Exception {
        assertTrue(WallpaperBatch.parseManifest("[]").isEmpty());
        assertTrue(WallpaperBatch.indices(42, 42, 0).isEmpty());
        assertTrue(WallpaperBatch.indices(0, 0, 5).isEmpty());
    }
    @Test public void wrapsOutOfRangeCursor() {
        assertEquals(Arrays.asList(2, 3), WallpaperBatch.indices(907, 2, 5));
        assertEquals(Arrays.asList(4, 0), WallpaperBatch.indices(-1, 2, 5));
        assertEquals(Arrays.asList(2), WallpaperBatch.indices(Integer.MAX_VALUE, 1, 5));
    }
    @Test public void parsesPathsWithoutFetchingThem() throws Exception {
        String json = "[{\"i\":0,\"path\":\"照片/a.jpg\",\"url\":\"http://example.test/a.jpg\"}]";
        WallpaperBatch.Entry entry = WallpaperBatch.parseManifest(json).get(0);
        assertEquals("照片/a.jpg", entry.path);
        assertEquals("http://example.test/a.jpg", entry.url);
    }
    @Test(expected = JSONException.class) public void rejectsIndexGap() throws Exception {
        WallpaperBatch.parseManifest("[{\"i\":1,\"path\":\"a\",\"url\":\"http://example.test/a\"}]");
    }
    @Test(expected = JSONException.class) public void rejectsNonHttpUrl() throws Exception {
        WallpaperBatch.parseManifest("[{\"i\":0,\"path\":\"a\",\"url\":\"file:///tmp/a\"}]");
    }
    @Test(expected = JSONException.class) public void rejectsMalformedJson() throws Exception {
        WallpaperBatch.parseManifest("not json");
    }
    @Test(expected = JSONException.class) public void rejectsAbsolutePath() throws Exception {
        WallpaperBatch.parseManifest("[{\"i\":0,\"path\":\"/a\",\"url\":\"http://example.test/a\"}]");
    }
}
