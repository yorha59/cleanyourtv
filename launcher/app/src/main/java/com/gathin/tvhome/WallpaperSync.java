package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, process-local pool sync. Downloads never run on Home's UI thread. */
final class WallpaperSync {
    private static final String TAG = "TvHome.WallpaperSync";
    private static final String LAST_SYNC = "last_wallpaper_sync";
    private static final String PROCESSED = "processed_wallpaper_keys";
    private static final int SESSION_LIMIT = 50;
    private static final int WIDTH = 1920, HEIGHT = 1080;
    private static final int TIMEOUT = 15000;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static Map<String, Long> parseManifest(String json) throws JSONException {
        JSONArray files = new JSONObject(json).getJSONArray("files");
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.getJSONObject(i);
            Object path = entry.get("p"), size = entry.get("s");
            if (!(path instanceof String) || !((String) path).matches(
                    "[\\w ./,()\\-\\u4e00-\\u9fff]+\\.(?i:jpg|jpeg|bmp|png)")
                    || ((String) path).contains("..") || ((String) path).startsWith("/")
                    || !(size instanceof Integer || size instanceof Long)
                    || ((Number) size).longValue() < 0 || result.containsKey(path)) {
                throw new JSONException("Invalid wallpaper manifest entry");
            }
            result.put((String) path, ((Number) size).longValue());
        }
        return result;
    }

    static String key(String path, long size) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    (path + "|" + size).getBytes(java.nio.charset.Charset.forName("UTF-8")));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(Character.forDigit((digest[i] & 255) >>> 4, 16));
                result.append(Character.forDigit(digest[i] & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Set<String> processedKeys(String saved) {
        Set<String> result = new LinkedHashSet<String>();
        for (String key : saved.split(",")) {
            if (key.matches("[0-9a-f]{16}")) result.add(key);
        }
        return result;
    }

    static String saveKeys(Set<String> keys) {
        StringBuilder result = new StringBuilder();
        for (String key : keys) {
            if (result.length() > 0) result.append(',');
            result.append(key);
        }
        return result.toString();
    }

    /** Called at Home start and while visible; duplicate requests share one process-wide guard. */
    static void start(Context context, final Config config, final Runnable completed) {
        debug(context, "start url=" + config.wallpaperSyncUrl);
        if (config.wallpaperSyncUrl == null || config.wallpaperSyncUrl.trim().length() == 0) return;
        final Context app = context.getApplicationContext();
        final SharedPreferences state = app.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), last = state.getLong(LAST_SYNC, 0);
        if (last > 0 && now >= last && now - last < config.wallpaperSyncIntervalHours * 3600000L) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        File base = Config.dir(app);
                        if (base == null) throw new IOException("External files unavailable");
                        File directory = new File(base, "wallpapers");
                        String address = config.wallpaperSyncUrl.trim();
                        if (!address.endsWith("/")) address += "/";
                        Map<String, Long> manifest = parseManifest(readManifest(address + "manifest.json"));
                        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create wallpapers");
                        Set<String> processed = processedKeys(state.getString(PROCESSED, ""));
                        int attempted = 0;
                        boolean pending = false;
                        for (Map.Entry<String, Long> entry : manifest.entrySet()) {
                            String key = key(entry.getKey(), entry.getValue());
                            if (processed.contains(key)) continue;
                            if (attempted >= SESSION_LIMIT) {
                                pending = true;
                                break;
                            }
                            attempted++;
                            File original = null;
                            try {
                                original = File.createTempFile("wallpaper-source-", ".tmp", app.getCacheDir());
                                download(address + URLEncoder.encode(entry.getKey(), "UTF-8"), original, entry.getValue());
                                compose(original, new File(directory, key + ".jpg"));
                                processed.add(key);
                                if (!state.edit().putString(PROCESSED, saveKeys(processed)).commit()) {
                                    processed.remove(key);
                                    throw new IOException("Cannot save processed wallpaper");
                                }
                            } catch (Exception failure) {
                                pending = true;
                                Log.w(TAG, "Wallpaper processing failed: " + failure.getClass().getSimpleName());
                            } catch (OutOfMemoryError failure) {
                                pending = true;
                                Log.w(TAG, "Not enough memory for wallpaper");
                            } finally {
                                if (original != null && original.exists() && !original.delete()) Log.w(TAG, "Cannot remove source wallpaper");
                            }
                        }
                        // Incomplete sessions continue at the next tick instead of waiting hours.
                        if (!state.edit().putLong(LAST_SYNC, pending ? 0 : System.currentTimeMillis()).commit())
                            throw new IOException("Cannot save sync time");
                        completed.run();
                    } catch (Exception failure) {
                        // Keep installed files; a later start/tick retries the remaining work.
                        debug(app, "sync failed: " + failure.getClass().getSimpleName() + " " + failure.getMessage());
                        Log.w(TAG, "Wallpaper sync failed: " + failure.getClass().getSimpleName());
                    } finally {
                        RUNNING.set(false);
                    }
                }
            }, "WallpaperSync").start();
        } catch (RuntimeException failure) {
            RUNNING.set(false);
            debug(context, "cannot start thread: " + failure);
            Log.w(TAG, "Cannot start wallpaper sync");
        }
    }

    /** Diagnostic file log; readable over adb even though this ROM blocks logcat. */
    private static void debug(Context context, String message) {
        try {
            File dir = Config.dir(context.getApplicationContext());
            if (dir == null) return;
            java.io.FileWriter writer = new java.io.FileWriter(new File(dir, "sync_debug.txt"), true);
            writer.append(new Date().toString()).append(" ").append(message).append('\n');
            writer.close();
        } catch (Exception ignored) {
        }
    }

    /** Decode bounds first; keep every source/intermediate bitmap at most 1920 pixels long. */
    private static void compose(File original, File target) throws IOException {
        Bitmap source = null, foreground = null, small = null, output = null;
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(original.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > WIDTH)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            source = BitmapFactory.decodeFile(original.getPath(), options);
            if (source == null) throw new IOException("Cannot decode image");
            int orientation = new ExifInterface(original.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(270); break;
                default: break;
            }
            if (!matrix.isIdentity()) {
                Bitmap upright = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
                if (upright != source) source.recycle();
                source = upright;
            }
            float fit = Math.min(1f, Math.min((float) WIDTH / source.getWidth(), (float) HEIGHT / source.getHeight()));
            foreground = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * fit)),
                    Math.max(1, Math.round(source.getHeight() * fit)), true);
            // Drawing cover directly at 64x36 avoids allocating a full-size cropped background.
            small = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
            Canvas background = new Canvas(small);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            float cover = Math.max(64f / source.getWidth(), 36f / source.getHeight());
            float w = source.getWidth() * cover, h = source.getHeight() * cover;
            background.drawBitmap(source, null, new RectF((64 - w) / 2, (36 - h) / 2,
                    (64 + w) / 2, (36 + h) / 2), paint);
            output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                    .55f, 0, 0, 0, 0, 0, .55f, 0, 0, 0,
                    0, 0, .55f, 0, 0, 0, 0, 0, 1, 0 })));
            canvas.drawBitmap(small, null, new RectF(0, 0, WIDTH, HEIGHT), paint);
            paint.setColorFilter(null);
            canvas.drawBitmap(foreground, (WIDTH - foreground.getWidth()) / 2f,
                    (HEIGHT - foreground.getHeight()) / 2f, paint);
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw new IOException("JPEG failed");
                stream.getFD().sync();
            }
            synchronized (WallpaperBatchManager.CACHE_LOCK) {
                if (!temporary.renameTo(target)) throw new IOException("Cannot install wallpaper");
            }
        } finally {
            if (foreground != null && foreground != source) foreground.recycle();
            if (source != null) source.recycle();
            if (small != null) small.recycle();
            if (output != null) output.recycle();
            if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Cannot remove temporary wallpaper");
        }
    }

    private static HttpURLConnection connect(String address) throws IOException {
        URL url = new URL(address);
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol()))
            throw new IOException("Pool must use HTTP or HTTPS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "CleanYourTV-Sync/1.0");
        return connection;
    }

    private static String readManifest(String address) throws IOException {
        HttpURLConnection connection = connect(address);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Manifest HTTP status");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Manifest too large");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void download(String address, File target, long expected) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        final HttpURLConnection connection = connect(address);
        // A read timeout alone resets on every read. Disconnect at the total per-file deadline
        // as well, so a server trickling bytes cannot hold this worker indefinitely.
        Timer deadline = new Timer("WallpaperSyncTimeout", true);
        deadline.schedule(new TimerTask() {
            @Override public void run() { connection.disconnect(); }
        }, TIMEOUT);
        long started = System.nanoTime();
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Image HTTP status");
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                long received = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (System.nanoTime() - started >= TIMEOUT * 1000000L)
                        throw new IOException("Image download timed out");
                    received += count;
                    if (received > expected) throw new IOException("Image exceeds manifest size");
                    output.write(buffer, 0, count);
                }
                if (received != expected) throw new IOException("Image size mismatch");
                output.getFD().sync();
            }
            if (System.nanoTime() - started >= TIMEOUT * 1000000L)
                throw new IOException("Image download timed out");
            synchronized (WallpaperBatchManager.CACHE_LOCK) {
                if (!temporary.renameTo(target)) throw new IOException("Cannot install image");
            }
        } finally {
            deadline.cancel();
            connection.disconnect();
            if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Cannot remove temporary wallpaper");
        }
    }
}
