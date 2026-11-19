package az.lite.music;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Telefondakı musiqilər (MediaStore). Proses daxilində tək nüsxə, Activity və Service paylaşır. */
final class Library {

    interface Listener {
        void onLibraryChanged();
    }

    /** Albom / ifaçı / qovluq qrupu. */
    static final class Group {
        final String name;
        final List<Song> songs = new ArrayList<>();
        Group(String name) { this.name = name; }
    }

    private static Library instance;

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    final List<Song> songs = new ArrayList<>();
    final Map<Long, Song> byId = new HashMap<>();
    boolean loaded;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private boolean observing;
    private final Runnable reload = this::load;

    private Library(Context app) {
        this.app = app;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    void load() {
        worker.execute(() -> {
            final List<Song> list = query(app.getContentResolver());
            ui.post(() -> {
                songs.clear();
                songs.addAll(list);
                byId.clear();
                for (Song s : list) byId.put(s.id, s);
                loaded = true;
                for (Listener l : new ArrayList<>(listeners)) l.onLibraryChanged();
            });
        });
        if (!observing) {
            observing = true;
            try {
                app.getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
                        new ContentObserver(ui) {
                            @Override
                            public void onChange(boolean selfChange) {
                                ui.removeCallbacks(reload);
                                ui.postDelayed(reload, 2000); // çoxlu dəyişikliyi bir yükləməyə yığ
                            }
                        });
            } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private static List<Song> query(ContentResolver cr) {
        List<Song> out = new ArrayList<>();
        String[] proj = {
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
        };
        // səs mesajları (WhatsApp, Telegram) və diktofon yazıları musiqi siyahısına düşməsin
        String d = MediaStore.Audio.Media.DATA;
        String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0 AND " + MediaStore.Audio.Media.DURATION + ">=10000"
                + " AND (" + d + " IS NULL OR (" + d + " NOT LIKE '%/WhatsApp%' AND " + d + " NOT LIKE '%/Telegram/%'"
                + " AND " + d + " NOT LIKE '%/Recordings/%' AND " + d + " NOT LIKE '%/sound_recorder/%'))";
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String artist = c.getString(3);
                if (artist == null || artist.equals("<unknown>")) artist = L.t("Naməlum ifaçı");
                String album = c.getString(4);
                if (album == null) album = L.t("Naməlum albom");
                String data = c.getString(6);
                String folder = L.t("Naməlum");
                if (data != null) {
                    int end = data.lastIndexOf('/');
                    if (end > 0) {
                        int start = data.lastIndexOf('/', end - 1);
                        folder = data.substring(start + 1, end);
                    }
                }
                String title = c.getString(2);
                if (title == null) title = L.t("Adsız");
                out.add(new Song(c.getLong(0), c.getLong(1), title, artist, album, folder, c.getLong(5), data));
            }
        } catch (Exception ignored) {
            // icazə yoxdursa boş siyahı
        }
        return out;
    }

    List<Group> albums() {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Song s : songs) add(m, s.album + "\u0000" + s.albumId, s.album, s);
        return sorted(m);
    }

    List<Group> artists() {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Song s : songs) add(m, s.artist, s.artist, s);
        return sorted(m);
    }

    List<Group> folders() {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Song s : songs) add(m, s.folder, s.folder, s);
        return sorted(m);
    }

    private static void add(Map<String, Group> m, String key, String name, Song s) {
        Group g = m.get(key);
        if (g == null) {
            g = new Group(name);
            m.put(key, g);
        }
        g.songs.add(s);
    }

    private static List<Group> sorted(Map<String, Group> m) {
        List<Group> l = new ArrayList<>(m.values());
        final java.text.Collator col = java.text.Collator.getInstance();
        java.util.Collections.sort(l, (a, b) -> col.compare(a.name, b.name));
        return l;
    }
}
