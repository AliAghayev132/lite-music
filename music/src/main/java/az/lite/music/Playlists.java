package az.lite.music;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pleylistlər — hər biri adi .m3u faylı (UTF-8), tətbiqin öz qovluğunda:
 * Android/data/az.lite.music/files/Playlists. Heç bir icazə tələb etmir.
 */
final class Playlists {

    static final class Playlist {
        String name;
        File file;
        final List<Song> songs = new ArrayList<>();
    }

    private final File dir;
    private final Library lib;
    final List<Playlist> all = new ArrayList<>();

    Playlists(Context c, Library lib) {
        File d = c.getExternalFilesDir("Playlists");
        if (d == null) d = new File(c.getFilesDir(), "Playlists");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        this.dir = d;
        this.lib = lib;
    }

    /** Faylları oxuyur və mahnıları kitabxana ilə uyğunlaşdırır. */
    void reload() {
        all.clear();
        Map<String, Song> byPath = new HashMap<>();
        for (Song s : lib.songs) if (s.path != null) byPath.put(s.path, s);
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".m3u") || n.toLowerCase().endsWith(".m3u8"));
        if (files != null) {
            for (File f : files) {
                Playlist p = new Playlist();
                p.file = f;
                String n = f.getName();
                p.name = n.substring(0, n.lastIndexOf('.'));
                read(p, byPath);
                all.add(p);
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(all, (a, b) -> col.compare(a.name, b.name));
    }

    private void read(Playlist p, Map<String, Song> byPath) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(p.file), StandardCharsets.UTF_8))) {
            String line;
            long pendingId = -1;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#LITEID:")) {
                    try { pendingId = Long.parseLong(line.substring(8)); } catch (NumberFormatException ignored) { }
                    continue;
                }
                if (line.startsWith("#")) continue;
                Song s = byPath.get(line);
                if (s == null && pendingId >= 0) s = lib.byId.get(pendingId);
                if (s != null) p.songs.add(s);
                pendingId = -1;
            }
        } catch (Exception ignored) { }
    }

    boolean save(Playlist p) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(p.file), StandardCharsets.UTF_8)) {
            w.write("#EXTM3U\n");
            for (Song s : p.songs) {
                w.write("#EXTINF:" + (s.duration / 1000) + "," + s.artist + " - " + s.title + "\n");
                w.write("#LITEID:" + s.id + "\n");
                w.write((s.path != null ? s.path : s.uri().toString()) + "\n");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String clean(String name) {
        String n = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    boolean exists(String name) {
        String n = clean(name);
        for (Playlist p : all) if (p.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    Playlist create(String name) {
        String n = clean(name);
        if (n.isEmpty() || exists(n)) return null;
        Playlist p = new Playlist();
        p.name = n;
        p.file = new File(dir, n + ".m3u");
        if (!save(p)) return null;
        all.add(p);
        final Collator col = Collator.getInstance();
        Collections.sort(all, (a, b) -> col.compare(a.name, b.name));
        return p;
    }

    boolean rename(Playlist p, String name) {
        String n = clean(name);
        if (n.isEmpty() || (exists(n) && !n.equalsIgnoreCase(p.name))) return false;
        File nf = new File(dir, n + ".m3u");
        if (!p.file.renameTo(nf)) return false;
        p.file = nf;
        p.name = n;
        return true;
    }

    void delete(Playlist p) {
        //noinspection ResultOfMethodCallIgnored
        p.file.delete();
        all.remove(p);
    }

    /** Mahnıları əlavə edir (təkrarları atlayır). Neçəsi əlavə olunduğunu qaytarır. */
    int add(Playlist p, List<Song> songs) {
        int n = 0;
        for (Song s : songs) {
            if (s.id < 0 || p.songs.contains(s)) continue;
            p.songs.add(s);
            n++;
        }
        if (n > 0) save(p);
        return n;
    }

    void remove(Playlist p, Song s) {
        if (p.songs.remove(s)) save(p);
    }

    void move(Playlist p, Song s, int delta) {
        int i = p.songs.indexOf(s), j = i + delta;
        if (i < 0 || j < 0 || j >= p.songs.size()) return;
        Collections.swap(p.songs, i, j);
        save(p);
    }
}
