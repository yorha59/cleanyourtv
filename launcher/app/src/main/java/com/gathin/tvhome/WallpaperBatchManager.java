package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Process-only, serial downloader/compositor. Never holds a reference to an Activity. */
final class WallpaperBatchManager {
    static final Object CACHE_LOCK = new Object();
    static volatile long generation;
    private static WallpaperBatchManager instance;
    private final Context context;
    private final Handler handler;
    private final File base;
    private final SharedPreferences state;

    static synchronized void start(Context context, Config config) {
        if (instance == null && config.wallpaperRemoteBatching && Config.dir(context) != null)
            instance = new WallpaperBatchManager(context.getApplicationContext());
    }

    private WallpaperBatchManager(Context context) {
        this.context = context;
        base = Config.dir(context);
        state = context.getSharedPreferences("wallpaper_batch", Context.MODE_PRIVATE);
        HandlerThread thread = new HandlerThread("WallpaperBatch");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.postDelayed(tick, 30_000L);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long started = SystemClock.uptimeMillis();
            int minutes = 60;
            try {
                Config config;
                synchronized (CACHE_LOCK) { config = Config.load(context); }
                minutes = config.wallpaperBatchIntervalMinutes;
                if (!config.wallpaperRemoteBatching) { log("Skipped: batching disabled"); return; }
                runBatch(config.wallpaperBatchSize);
            } catch (Exception | OutOfMemoryError e) {
                log("Batch failed: " + e.getClass().getSimpleName());
            } finally {
                handler.postAtTime(this, Math.max(started + minutes * 60_000L, SystemClock.uptimeMillis()));
            }
        }
    };

    private void runBatch(int size) throws IOException {
        File staging = new File(base, "wallpapers_staging");
        try {
            // A previous process may have died between the two renames. Restore before any cleanup.
            synchronized (CACHE_LOCK) { recover(); }
            clear(staging);
            File manifest = new File(base, "wallpapers_manifest.json");
            if (!manifest.isFile()) { log("Skipped: manifest missing"); return; }
            if (manifest.length() > 8 * 1024 * 1024) throw new IOException("Manifest too large");
            List<WallpaperBatch.Entry> entries = WallpaperBatch.parseManifest(
                    new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            if (entries.isEmpty()) { log("Skipped: empty manifest"); return; }
            List<Integer> indices = WallpaperBatch.indices(state.getInt("next_idx", 0), size, entries.size());
            if (!staging.mkdir()) throw new IOException("Cannot create staging");
            for (int n = 0; n < indices.size(); n++) {
                File raw = new File(staging, String.format(Locale.ROOT, "tmp_%03d.bin", n));
                download(entries.get(indices.get(n)).url, raw);
                compose(raw, new File(staging, String.format(Locale.ROOT, "%03d.jpg", n)));
                if (!raw.delete()) throw new IOException("Cannot delete temporary original");
            }
            for (int n = 0; n < indices.size(); n++) {
                File image = new File(staging, String.format(Locale.ROOT, "%03d.jpg", n));
                if (!image.isFile() || image.length() == 0) throw new IOException("Incomplete batch");
            }
            int next = (indices.get(0) + indices.size()) % entries.size();
            synchronized (CACHE_LOCK) { install(staging, next); }
            log("Batch installed: count=" + indices.size() + " next_idx=" + next);
        } catch (Exception | OutOfMemoryError e) {
            log("Batch failed; cache retained: " + e.getClass().getSimpleName());
        } finally {
            clear(staging);
        }
    }

    private void recover() throws IOException {
        File old = new File(base, "wallpapers_previous"), live = new File(base, "wallpapers");
        if (old.exists()) {
            // A durable cursor update marks the transaction committed.
            if (!state.getBoolean("install_pending", false)) clear(old);
            else {
                clear(live);
                if (!old.renameTo(live)) throw new IOException("Cannot restore previous batch");
            }
        } else if (state.getBoolean("install_pending", false)
                && !state.getBoolean("had_previous", false)) clear(live);
        if (state.getBoolean("install_pending", false)) {
            if (!state.edit().remove("install_pending").remove("had_previous").commit())
                throw new IOException("Cannot clear transaction marker");
            generation++;
        }
    }

    private void install(File staging, int next) throws IOException {
        File live = new File(base, "wallpapers"), old = new File(base, "wallpapers_previous");
        boolean hadPrevious = live.exists();
        int previousIndex = state.getInt("next_idx", 0);
        if (!state.edit().putBoolean("install_pending", true)
                .putBoolean("had_previous", hadPrevious).commit()) throw new IOException("Cannot journal swap");
        try {
            if (hadPrevious && !live.renameTo(old)) throw new IOException("Cannot save previous cache");
            if (!staging.renameTo(live)) throw new IOException("Cannot install cache");
            if (!state.edit().putInt("next_idx", next).remove("install_pending")
                    .remove("had_previous").commit()) throw new IOException("Cannot save cursor");
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // commit() also updates the in-memory preferences on an I/O failure: explicitly restore cursor.
            if (old.exists()) {
                clear(live);
                if (!old.renameTo(live)) throw new IOException("Rollback failed; previous cache retained", e);
            } else if (!hadPrevious) clear(live);
            state.edit().putInt("next_idx", previousIndex).remove("install_pending").remove("had_previous").commit();
            throw new IOException("Cache installation failed", e);
        }
        generation++;
        context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).edit()
                .remove("collection").remove("image").remove("changed").apply();
        // Cleanup is after the commit: a cleanup error cannot invalidate an installed batch.
        try { clear(old); } catch (IOException e) { log("Previous cache cleanup deferred"); }
    }

    private void download(String address, File target) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(60_000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP status");
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    long received = 0;
                    while ((count = in.read(buffer)) != -1) {
                        received += count;
                        if (received > 128L * 1024 * 1024) throw new IOException("Original too large");
                        out.write(buffer, 0, count);
                    }
                    if (received == 0) throw new IOException("Empty response");
                    long expected = connection.getContentLengthLong();
                    if (expected >= 0 && received != expected) throw new IOException("Truncated response");
                }
                return;
            } catch (IOException e) {
                log("Download failed: " + target.getName() + " attempt=" + (attempt + 1));
                if (attempt == 2) throw e;
            } finally { if (connection != null) connection.disconnect(); }
        }
    }

    private static void compose(File source, File target) throws IOException {
        Bitmap decoded = null, upright = null, small = null, output = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
            int sample = 1;
            while ((Math.max(options.outWidth, options.outHeight) + (long) sample - 1) / sample > 2560) sample *= 2;
            options.inSampleSize = sample;
            options.inJustDecodeBounds = false;
            decoded = BitmapFactory.decodeFile(source.getPath(), options);
            if (decoded == null) throw new IOException("Decode failed");
            ExifInterface exif = new ExifInterface(source);
            Matrix matrix = new Matrix();
            // ExifInterface exposes mirrored + clockwise rotation for all eight orientations.
            if (exif.isFlipped()) matrix.postScale(-1, 1);
            matrix.postRotate(exif.getRotationDegrees());
            upright = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (upright != decoded) { decoded.recycle(); decoded = null; }
            small = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            new Canvas(small).drawBitmap(upright, null, fit(upright, 240, 135, true), paint);
            output = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            ColorMatrix brightness = new ColorMatrix(new float[]{
                    .55f,0,0,0,0, 0,.55f,0,0,0, 0,0,.55f,0,0, 0,0,0,1,0});
            paint.setColorFilter(new ColorMatrixColorFilter(brightness));
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(small, null, new Rect(0, 0, 1920, 1080), paint);
            paint.setColorFilter(null);
            canvas.drawBitmap(upright, null, fit(upright, 1920, 1080, false), paint);
            try (FileOutputStream out = new FileOutputStream(target)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 78, out)) throw new IOException("JPEG failed");
                out.getFD().sync();
            }
        } finally {
            if (output != null) output.recycle();
            if (small != null) small.recycle();
            if (upright != null) upright.recycle();
            if (decoded != null && decoded != upright) decoded.recycle();
        }
    }

    private static RectF fit(Bitmap bitmap, int width, int height, boolean cover) {
        float x = width / (float) bitmap.getWidth(), y = height / (float) bitmap.getHeight();
        float scale = cover ? Math.max(x, y) : Math.min(x, y);
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        return new RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
    }

    private static void clear(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list cache");
            for (File child : children) clear(child);
        }
        if (!file.delete()) throw new IOException("Cannot clear cache");
    }

    private void log(String message) {
        Log.i("TvHome.WallpaperBatch", message);
        File log = new File(base, "wp_batch_log.txt");
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message + "\n";
        try (FileOutputStream out = new FileOutputStream(log, log.length() + line.length() <= 1024 * 1024)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { Log.w("TvHome.WallpaperBatch", "Cannot write batch log"); }
    }
}
