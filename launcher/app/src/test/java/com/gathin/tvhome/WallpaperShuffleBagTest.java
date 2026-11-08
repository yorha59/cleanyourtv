package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WallpaperShuffleBagTest {
    private final List<File> images = Arrays.asList(
            new File("a.jpg"), new File("b.jpg"), new File("c.jpg"), new File("d.jpg"));

    @Test public void everyRoundContainsEachImageExactlyOnce() {
        Deque<String> bag = new ArrayDeque<>();
        Random random = new Random(42);
        String displayed = "";
        for (int round = 0; round < 10; round++) {
            Set<File> seen = new HashSet<>();
            for (int i = 0; i < images.size(); i++) {
                File next = WallpaperRotation.nextFromBag(bag, images, displayed, random);
                assertTrue(seen.add(next));
                displayed = next.getName();
            }
            assertEquals(new HashSet<>(images), seen);
            assertTrue(bag.isEmpty());
        }
    }

    @Test public void emptyBagAutomaticallyRefills() {
        Deque<String> bag = new ArrayDeque<>(Collections.singletonList("b.jpg"));
        Random random = new Random(7);
        assertEquals(images.get(1), WallpaperRotation.nextFromBag(bag, images, "", random));
        assertTrue(bag.isEmpty());
        File next = WallpaperRotation.nextFromBag(bag, images, "b.jpg", random);
        assertTrue(images.contains(next));
        assertEquals(images.size() - 1, bag.size());
    }

    @Test public void deletedNamesAreRemovedThroughoutTheBag() {
        Deque<String> bag = new ArrayDeque<>(Arrays.asList("gone.jpg", "b.jpg", "also-gone.jpg", "d.jpg"));
        assertEquals(images.get(1), WallpaperRotation.nextFromBag(bag, images, "", new Random(1)));
        assertEquals(Collections.singletonList("d.jpg"), Arrays.asList(bag.toArray(new String[0])));
    }

    @Test public void deletingAllPendingImagesStartsANewRound() {
        Deque<String> bag = new ArrayDeque<>(Collections.singletonList("gone.jpg"));
        assertTrue(images.contains(WallpaperRotation.nextFromBag(bag, images, "", new Random(1))));
        assertEquals(images.size() - 1, bag.size());
    }

    @Test public void newRoundSwapsMatchingFirstImageWithTheTail() {
        List<String> shuffled = Arrays.asList("a.jpg", "b.jpg", "c.jpg", "d.jpg");
        Collections.shuffle(shuffled, new Random(42));
        String displayed = shuffled.get(0);
        Deque<String> bag = new ArrayDeque<>();
        File next = WallpaperRotation.nextFromBag(bag, images, displayed, new Random(42));
        assertFalse(displayed.equals(next.getName()));
        assertEquals(shuffled.get(shuffled.size() - 1), next.getName());
        assertEquals(displayed, bag.peekLast());
    }

    @Test public void emptyCollectionClearsTheBag() {
        Deque<String> bag = new ArrayDeque<>(Collections.singletonList("a.jpg"));
        assertNull(WallpaperRotation.nextFromBag(bag, Collections.<File>emptyList(), "a.jpg", new Random(1)));
        assertTrue(bag.isEmpty());
    }

    @Test public void singleImageCanRepeatAcrossRounds() {
        Deque<String> bag = new ArrayDeque<>();
        List<File> single = Collections.singletonList(images.get(0));
        for (int i = 0; i < 3; i++) {
            assertEquals(images.get(0), WallpaperRotation.nextFromBag(bag, single, "a.jpg", new Random(1)));
            assertTrue(bag.isEmpty());
        }
    }
}
