package az.lite.music;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

/** Bir mahnı (MediaStore-dan və ya kənardan açılmış fayl). */
final class Song {
    final long id;
    final long albumId;
    final String title, artist, album, folder;
    final String path; // fayl yolu (pleylist .m3u üçün), ola bilər null
    final long duration;
    final Uri external; // kənardan açılan fayl üçün (id < 0)

    Song(long id, long albumId, String title, String artist, String album, String folder, long duration, String path) {
        this.id = id;
        this.path = path;
        this.albumId = albumId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.folder = folder;
        this.duration = duration;
        this.external = null;
    }

    Song(Uri uri, String title) {
        this.id = -1;
        this.albumId = -1;
        this.title = title;
        this.artist = "";
        this.album = "";
        this.folder = "";
        this.duration = 0;
        this.path = null;
        this.external = uri;
    }

    Uri uri() {
        if (external != null) return external;
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
        return m + ":" + (sec < 10 ? "0" : "") + sec;
    }
}
