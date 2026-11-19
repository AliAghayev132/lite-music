package az.lite.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Arxa planda dayanmadan çalan pleyer: foreground service + MediaSession (kilid ekranı, qulaqlıq düymələri,
 * bluetooth), bildiriş idarəsi, audio fokus, qulaqlıq çıxanda pauza, növbə/qarışıq/təkrar, ekvalayzer, yuxu taymeri.
 * Vəziyyət (növbə, mövqe) yadda saxlanır.
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener,
        Library.Listener {

    static final String ACT_TOGGLE = "az.lite.music.TOGGLE";
    static final String ACT_NEXT = "az.lite.music.NEXT";
    static final String ACT_PREV = "az.lite.music.PREV";
    static final String ACT_STOP = "az.lite.music.STOP";
    static final String ACT_PLAY_URI = "az.lite.music.PLAY_URI";

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 7;

    interface Listener {
        void onPlayerState();
    }

    final class LocalBinder extends Binder {
        MusicService get() { return MusicService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private NotificationManager nm;
    private SharedPreferences prefs;
    private Library library;
    private Equalizer eq;

    private List<Song> queue = new ArrayList<>();
    private List<Song> original = new ArrayList<>();
    private int index = -1;
    private boolean shuffle;
    private int repeat = REPEAT_OFF;
    private boolean prepared, preparing, playing, playWhenReady, resumeOnFocus, noisyRegistered;
    private int pendingSeek;
    private int eqPreset = -1;
    private long sleepAt;
    private boolean sleepAfterSong;
    private boolean restored;
    private Bitmap art;
    private long artAlbum = Long.MIN_VALUE;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause();
        }
    };

    private final Runnable sleepRun = () -> {
        sleepAt = 0;
        pause();
    };

    private final Runnable saveRun = this::saveState;

    // ================================================================ lifecycle

    @Override
    public void onCreate() {
        L.init(this);
        super.onCreate();
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);

        session = new MediaSession(this, "LiteMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onStop() { stopAll(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, piFlags()));

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, L.t("Oynatma"), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", REPEAT_OFF);
        eqPreset = prefs.getInt("eq", -1);

        library = Library.get(this);
        library.addListener(this);
        if (library.loaded) restoreState(); else library.load();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) {
        if (!playing) stopSelf();  // heç kim bağlı deyil və çalmırsa — yaddaşı boşalt
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a != null) {
            // getForegroundService ilə başladılıbsa 5 saniyə ərzində startForeground lazımdır
            goForeground();
            switch (a) {
                case ACT_TOGGLE: toggle(); break;
                case ACT_NEXT: next(); break;
                case ACT_PREV: prev(); break;
                case ACT_STOP: stopAll(); return START_NOT_STICKY;
                case ACT_PLAY_URI:
                    if (intent.getData() != null) {
                        String name = intent.getStringExtra("title");
                        List<Song> one = new ArrayList<>();
                        one.add(new Song(intent.getData(), name != null ? name : L.t("Fayl")));
                        playList(one, 0, false);
                    }
                    break;
            }
            if (!playing && !playWhenReady) leaveForeground();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!playing) stopAll();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) Art.trim();
    }

    @Override
    public void onDestroy() {
        saveState();
        library.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        unregisterNoisy();
        abandonFocus();
        try { if (eq != null) eq.release(); } catch (Exception ignored) { }
        mp.release();
        session.release();
        super.onDestroy();
    }

    @Override
    public void onLibraryChanged() {
        if (!restored) restoreState();
    }

    // ================================================================ dinləyicilər

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void notifyState() {
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerState();
        updateSession();
        updateNotification();
        LiteMusicWidget.update(this, current(), playing, art);
    }

    // ================================================================ oxumaq üçün

    Song current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    List<Song> queue() { return queue; }
    int index() { return index; }
    boolean isPlaying() { return playing; }
    boolean isShuffle() { return shuffle; }
    int repeatMode() { return repeat; }
    int position() {
        if (prepared) try { return mp.getCurrentPosition(); } catch (Exception ignored) { }
        return pendingSeek;
    }
    int duration() {
        if (prepared) try { return mp.getDuration(); } catch (Exception ignored) { }
        Song s = current();
        return s != null ? (int) s.duration : 0;
    }
    long sleepRemaining() { return sleepAt > 0 ? sleepAt - SystemClock.elapsedRealtime() : 0; }
    boolean sleepAfterSong() { return sleepAfterSong; }
    int eqPreset() { return eqPreset; }

    // ================================================================ idarə

    void playList(List<Song> list, int start, boolean shuffleIt) {
        if (list.isEmpty()) return;
        original = new ArrayList<>(list);
        queue = new ArrayList<>(list);
        shuffle = shuffleIt;
        if (shuffleIt) {
            if (start < 0) start = new Random().nextInt(queue.size());
            Song first = queue.remove(start);
            Collections.shuffle(queue);
            queue.add(0, first);
            index = 0;
        } else {
            index = Math.max(0, start);
        }
        prefs.edit().putBoolean("shuffle", shuffle).apply();
        load(index, true, 0);
    }

    void jumpTo(int i) {
        if (i >= 0 && i < queue.size()) load(i, true, 0);
    }

    void playNext(Song s) {
        if (queue.isEmpty()) {
            List<Song> one = new ArrayList<>();
            one.add(s);
            playList(one, 0, false);
            return;
        }
        queue.add(index + 1, s);
        original.add(s);
        saveSoon();
        notifyState();
    }

    void enqueue(Song s) {
        if (queue.isEmpty()) {
            playNext(s);
            return;
        }
        queue.add(s);
        original.add(s);
        saveSoon();
        notifyState();
    }

    void removeAt(int i) {
        if (i < 0 || i >= queue.size() || i == index) return;
        Song s = queue.remove(i);
        original.remove(s);
        if (i < index) index--;
        saveSoon();
        notifyState();
    }

    void toggle() {
        if (playing) pause(); else play();
    }

    void play() {
        if (queue.isEmpty()) {
            if (!library.songs.isEmpty()) playList(library.songs, 0, shuffle);
            return;
        }
        if (prepared) {
            startPlayback();
        } else if (!preparing) {
            load(index < 0 ? 0 : index, true, pendingSeek);
        } else {
            playWhenReady = true;
        }
    }

    void pause() {
        playWhenReady = false;
        if (prepared && mp.isPlaying()) mp.pause();
        if (!playing) {
            notifyState();
            return;
        }
        playing = false;
        unregisterNoisy();
        leaveForeground();
        saveState();
        notifyState();
    }

    void next() {
        if (queue.isEmpty()) return;
        int n = index + 1;
        if (n >= queue.size()) n = 0;
        load(n, playing || playWhenReady, 0);
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (position() > 3000) {
            seekTo(0);
            return;
        }
        int n = index - 1;
        if (n < 0) n = queue.size() - 1;
        load(n, playing || playWhenReady, 0);
    }

    void seekTo(int ms) {
        if (prepared) {
            mp.seekTo(ms);
        } else {
            pendingSeek = ms;
        }
        updateSession();
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerState();
    }

    void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        Song cur = current();
        if (on) {
            original = new ArrayList<>(queue);
            List<Song> rest = new ArrayList<>(queue);
            if (cur != null) rest.remove(index);
            Collections.shuffle(rest);
            queue = new ArrayList<>();
            if (cur != null) queue.add(cur);
            queue.addAll(rest);
            index = cur != null ? 0 : -1;
        } else if (!original.isEmpty()) {
            queue = new ArrayList<>(original);
            index = cur != null ? Math.max(0, queue.indexOf(cur)) : -1;
        }
        prefs.edit().putBoolean("shuffle", shuffle).apply();
        saveSoon();
        notifyState();
    }

    void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        prefs.edit().putInt("repeat", repeat).apply();
        notifyState();
    }

    /** minutes > 0 → N dəqiqə sonra; -1 → mahnı bitəndə; 0 → ləğv. */
    void setSleep(int minutes) {
        handler.removeCallbacks(sleepRun);
        sleepAt = 0;
        sleepAfterSong = false;
        if (minutes > 0) {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60000L;
            handler.postDelayed(sleepRun, minutes * 60000L);
        } else if (minutes == -1) {
            sleepAfterSong = true;
        }
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerState();
    }

    void stopAll() {
        pause();
        abandonFocus();
        session.setActive(false);
        stopForegroundCompat(true);
        nm.cancel(NOTIF_ID);
        stopSelf();
    }

    // ================================================================ ekvalayzer

    String[] eqPresets() {
        if (!ensureEq()) return new String[0];
        try {
            short n = eq.getNumberOfPresets();
            String[] out = new String[n];
            for (short i = 0; i < n; i++) out[i] = eq.getPresetName(i);
            return out;
        } catch (Exception e) {
            return new String[0];
        }
    }

    void setEqPreset(int p) {
        eqPreset = p;
        prefs.edit().putInt("eq", p).apply();
        applyEq();
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerState();
    }

    private boolean ensureEq() {
        if (eq != null) return true;
        try {
            eq = new Equalizer(0, mp.getAudioSessionId());
            return true;
        } catch (Throwable t) {
            eq = null;
            return false;
        }
    }

    private void applyEq() {
        try {
            if (eqPreset >= 0) {
                if (ensureEq()) {
                    eq.usePreset((short) eqPreset);
                    eq.setEnabled(true);
                }
            } else if (eq != null) {
                eq.setEnabled(false);
            }
        } catch (Throwable ignored) { }
    }

    // ================================================================ MediaPlayer

    private void load(int i, boolean autoplay, int seek) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        prepared = false;
        preparing = true;
        playWhenReady = autoplay;
        pendingSeek = seek;
        Song s = queue.get(i);
        try {
            mp.reset();
            mp.setDataSource(this, s.uri());
            mp.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            handler.postDelayed(this::skipBroken, 300);
        }
        loadArt(s);
        updateMetadata();
        saveSoon();
        notifyState();
    }

    @Override
    public void onPrepared(MediaPlayer p) {
        prepared = true;
        preparing = false;
        if (pendingSeek > 0) {
            mp.seekTo(pendingSeek);
            pendingSeek = 0;
        }
        applyEq();
        updateMetadata();
        if (playWhenReady) startPlayback(); else notifyState();
    }

    private void startPlayback() {
        if (!requestFocus()) return;
        mp.setVolume(1f, 1f);
        mp.start();
        playing = true;
        playWhenReady = false;
        registerNoisy();
        session.setActive(true);
        goForeground();
        notifyState();
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        if (!prepared) return;
        if (sleepAfterSong) {
            sleepAfterSong = false;
            pause();
            return;
        }
        if (repeat == REPEAT_ONE) {
            mp.seekTo(0);
            mp.start();
            return;
        }
        if (index < queue.size() - 1) {
            load(index + 1, true, 0);
        } else if (repeat == REPEAT_ALL) {
            load(0, true, 0);
        } else {
            playing = false;
            playWhenReady = false;
            prepared = false;
            pendingSeek = 0;
            unregisterNoisy();
            leaveForeground();
            notifyState();
        }
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        prepared = false;
        preparing = false;
        handler.postDelayed(this::skipBroken, 300);
        return true;
    }

    /** Oxunmayan faylı keç (hamısı xarabdırsa dayan). */
    private int broken;
    private void skipBroken() {
        broken++;
        if (broken > queue.size() || queue.size() <= 1) {
            broken = 0;
            playing = false;
            leaveForeground();
            notifyState();
            return;
        }
        int n = index + 1 >= queue.size() ? 0 : index + 1;
        load(n, true, 0);
        handler.postDelayed(() -> broken = 0, 5000);
    }

    // ================================================================ audio fokus

    private boolean requestFocus() {
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(this, handler)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            r = am.requestAudioFocus(focusRequest);
        } else {
            r = am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
        } else {
            am.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocus = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) {
                    resumeOnFocus = true;
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (prepared) mp.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (prepared) mp.setVolume(1f, 1f);
                if (resumeOnFocus) {
                    resumeOnFocus = false;
                    play();
                }
                break;
        }
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        noisyRegistered = false;
    }

    // ================================================================ session / bildiriş

    private void updateSession() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position(), 1f)
                .build());
    }

    private void updateMetadata() {
        Song s = current();
        if (s == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        if (art != null && artAlbum == s.albumId) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
    }

    private void loadArt(final Song s) {
        if (s.albumId == artAlbum) return;
        art = null;
        artAlbum = s.albumId;
        final long want = s.albumId;
        Art.execute(() -> {
            final Bitmap b = Art.get(this, s, 384);
            handler.post(() -> {
                if (artAlbum != want) return;
                art = b;
                updateMetadata();
                updateNotification();
                LiteMusicWidget.update(this, current(), playing, art);
            });
        });
    }

    private int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    PendingIntent action(String a, int code) {
        return actionIntent(this, a, code);
    }

    static PendingIntent actionIntent(Context c, String a, int code) {
        Intent i = new Intent(c, MusicService.class).setAction(a);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(c, code, i, flags);
        return PendingIntent.getService(c, code, i, flags);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Song s = current();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(s != null ? s.title : "Lite Music")
                .setContentText(s != null ? s.artist : "")
                .setLargeIcon(art)
                .setContentIntent(PendingIntent.getActivity(this, 1, open, piFlags()))
                .setDeleteIntent(action(ACT_STOP, 14))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action(R.drawable.i_prev, L.t("Əvvəlki"), action(ACT_PREV, 11)))
                .addAction(new Notification.Action(playing ? R.drawable.i_pause : R.drawable.i_play,
                        playing ? L.t("Pauza") : L.t("Oynat"), action(ACT_TOGGLE, 12)))
                .addAction(new Notification.Action(R.drawable.i_next, L.t("Növbəti"), action(ACT_NEXT, 13)))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private boolean foreground;

    private void goForeground() {
        // xidmət "started" vəziyyətdə olsun ki, Activity bağlananda ölməsin
        try { startService(new Intent(this, MusicService.class)); } catch (Exception ignored) { }
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
        } catch (Exception e) {
            nm.notify(NOTIF_ID, n);
        }
    }

    private void leaveForeground() {
        if (foreground) {
            stopForegroundCompat(false);
            foreground = false;
        }
        if (current() != null) nm.notify(NOTIF_ID, buildNotification()); // pauzada bildiriş qalır, sürüşdürüb silmək olur
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat(boolean remove) {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(remove);
        }
    }

    private void updateNotification() {
        if (current() == null) return;
        if (foreground || playing) nm.notify(NOTIF_ID, buildNotification());
    }

    // ================================================================ vəziyyətin saxlanması

    private void saveSoon() {
        handler.removeCallbacks(saveRun);
        handler.postDelayed(saveRun, 1500);
    }

    private void saveState() {
        handler.removeCallbacks(saveRun);
        StringBuilder q = new StringBuilder(), o = new StringBuilder();
        for (Song s : queue) if (s.id >= 0) q.append(s.id).append(',');
        for (Song s : original) if (s.id >= 0) o.append(s.id).append(',');
        Song cur = current();
        prefs.edit()
                .putString("queue", q.toString())
                .putString("original", o.toString())
                .putLong("current", cur != null ? cur.id : -1)
                .putInt("pos", cur != null ? position() : 0)
                .apply();
    }

    private void restoreState() {
        if (restored || !library.loaded) return;
        restored = true;
        if (!queue.isEmpty()) return; // artıq nəsə çalınır
        queue = ids(prefs.getString("queue", ""));
        original = ids(prefs.getString("original", ""));
        if (original.isEmpty()) original = new ArrayList<>(queue);
        long cur = prefs.getLong("current", -1);
        index = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).id == cur) { index = i; break; }
        if (index < 0 && !queue.isEmpty()) index = 0;
        pendingSeek = prefs.getInt("pos", 0);
        Song s = current();
        if (s != null) {
            loadArt(s);
            updateMetadata();
        }
        notifyState();
    }

    private List<Song> ids(String csv) {
        List<Song> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            if (p.isEmpty()) continue;
            try {
                Song s = library.byId.get(Long.parseLong(p));
                if (s != null) out.add(s);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
