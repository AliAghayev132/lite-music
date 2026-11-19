package az.lite.music;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Albom şəkilləri: kiçik ölçüdə yüklənir, yaddaşın 1/16-i qədər keşlənir. */
final class Art {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 16, 24L * 1024 * 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
            };
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    static void load(Context c, final ImageView iv, Song s, final int sizePx, Drawable placeholder) {
        final String key = s.albumId + ":" + sizePx;
        iv.setTag(R.id.art_key, key);
        Bitmap b = CACHE.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(placeholder);
        if (s.albumId < 0 || MISSING.contains(key)) return;
        final Context app = c.getApplicationContext();
        final long albumId = s.albumId;
        WORKER.execute(() -> {
            final Bitmap bm = decode(app, albumId, sizePx);
            UI.post(() -> {
                if (bm != null) CACHE.put(key, bm); else MISSING.add(key);
                if (bm != null && key.equals(iv.getTag(R.id.art_key))) iv.setImageBitmap(bm);
            });
        });
    }

    /** Sinxron (arxa plan thread-ində çağırılmalıdır). */
    static Bitmap get(Context c, Song s, int sizePx) {
        if (s == null || s.albumId < 0) return null;
        String key = s.albumId + ":" + sizePx;
        Bitmap b = CACHE.get(key);
        if (b != null || MISSING.contains(key)) return b;
        b = decode(c.getApplicationContext(), s.albumId, sizePx);
        if (b != null) CACHE.put(key, b); else MISSING.add(key);
        return b;
    }

    static void execute(Runnable r) { WORKER.execute(r); }

    private static Bitmap decode(Context c, long albumId, int size) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId);
                return c.getContentResolver().loadThumbnail(u, new Size(size, size), null);
            }
            Uri u = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    static void trim() { CACHE.evictAll(); }

    private Art() { }
}
