package az.lite.music;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lite Music — siyahılar, axtarış, mini pleyer və tam ekran pleyer. Oynatma MusicService-dədir. */
public class MainActivity extends Activity implements Library.Listener, MusicService.Listener {

    private static final int TAB_SONGS = 0, TAB_ALBUMS = 1, TAB_ARTISTS = 2, TAB_FOLDERS = 3, TAB_FAVS = 4, TAB_LISTS = 5, TAB_QUEUE = 6;
    private static final String[] TABS = {"Mahnılar", "Albomlar", "İfaçılar", "Qovluqlar", "Sevimlilər", "Pleylistlər", "Növbə"};
    private static final Object NEW_LIST_ROW = new Object();
    private static final Object SHUFFLE_ROW = new Object();

    private Library lib;
    private MusicService svc;
    private SharedPreferences prefs;
    private final Set<Long> favs = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ListView list;
    private EditText search;
    private TextView empty, crumb;
    private LinearLayout tabsRow;
    private View mini, miniProgress, player;
    private ImageView miniArt, miniPlay, pArt, pPlay, pShuffle, pRepeat;
    private TextView miniTitle, miniArtist, pTitle, pArtist, pPos, pDur, pFav, pSleep, pEq, pFrom;
    private SeekBar pSeek;
    private Drawable notePh, personPh, folderPh, listPh;

    private int tab = TAB_SONGS;
    private Library.Group openGroup;
    private Playlists playlists;
    private Playlists.Playlist openList;
    private final List<Object> rows = new ArrayList<>();
    private List<Library.Group> albums, artists, folders; // keş — kitabxana dəyişəndə sıfırlanır
    private BaseAdapter adapter;
    private boolean seeking;
    private long shownSongId = Long.MIN_VALUE;
    private Uri pendingUri;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            svc = ((MusicService.LocalBinder) b).get();
            svc.addListener(MainActivity.this);
            if (pendingUri != null) {
                playUri(pendingUri);
                pendingUri = null;
            }
            onPlayerState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            ui.postDelayed(this, 500);
        }
    };

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        L.init(this);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        for (String s : prefs.getString("favs", "").split(",")) {
            try { if (!s.isEmpty()) favs.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
        }

        root = findViewById(R.id.root);
        list = findViewById(R.id.list);
        search = findViewById(R.id.search);
        empty = findViewById(R.id.empty);
        crumb = findViewById(R.id.crumb);
        tabsRow = findViewById(R.id.tabs);
        mini = findViewById(R.id.mini);
        miniProgress = findViewById(R.id.mini_progress);
        miniArt = findViewById(R.id.mini_art);
        miniPlay = findViewById(R.id.mini_play);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        player = findViewById(R.id.player);
        pArt = findViewById(R.id.p_art);
        pPlay = findViewById(R.id.p_play);
        pShuffle = findViewById(R.id.p_shuffle);
        pRepeat = findViewById(R.id.p_repeat);
        pTitle = findViewById(R.id.p_title);
        pArtist = findViewById(R.id.p_artist);
        pPos = findViewById(R.id.p_pos);
        pDur = findViewById(R.id.p_dur);
        pFav = findViewById(R.id.p_fav);
        pSleep = findViewById(R.id.p_sleep);
        pEq = findViewById(R.id.p_eq);
        pEq.setText(L.t("EKVALAYZER"));
        ((EditText) findViewById(R.id.search)).setHint(L.t("Mahnı, ifaçı, albom axtar…"));
        TextView langBtn = findViewById(R.id.btn_lang);
        langBtn.setText(L.lang().toUpperCase(Locale.ROOT));
        langBtn.setBackground(Ui.pressable(0, Ui.SURFACE3));
        langBtn.setOnClickListener(v -> {
            String[] names = new String[L.CODES.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = (L.CODES[i].equals(L.lang()) ? "●  " : "") + L.NAMES[i];
            }
            Sheet.menu(root, L.t("Dil") + " / Language", names, i -> {
                if (L.CODES[i].equals(L.lang())) return;
                L.set(this, L.CODES[i]);
                recreate();
            });
        });
        pFrom = findViewById(R.id.p_from);
        pFrom.setText(L.t("İNDİ OXUNUR"));
        pSeek = findViewById(R.id.p_seek);

        notePh = placeholder(R.drawable.i_note);
        personPh = placeholder(R.drawable.i_person);
        listPh = placeholder(R.drawable.i_queue);
        folderPh = placeholder(R.drawable.i_folder);
        miniPlay.setColorFilter(Ui.ON_ACCENT);
        pPlay.setColorFilter(Ui.ON_ACCENT);

        setupTabs();
        setupList();
        setupPlayer();

        lib = Library.get(this);
        playlists = new Playlists(this, lib);
        lib.addListener(this);
        if (hasPermission()) {
            if (lib.loaded) onLibraryChanged(); else lib.load();
        } else {
            requestPerms();
        }
        handleIntent(getIntent());
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MusicService.class), conn, BIND_AUTO_CREATE);
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        if (svc != null) svc.removeListener(this);
        try { unbindService(conn); } catch (Exception ignored) { }
        svc = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lib.removeListener(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) Art.trim();
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        if (Sheet.closeTop()) return;
        if (player.getVisibility() == View.VISIBLE) {
            showPlayer(false);
            return;
        }
        if (openGroup != null || openList != null) {
            openGroup = null;
            openList = null;
            refresh();
            return;
        }
        if (search.length() > 0) {
            search.setText("");
            return;
        }
        moveTaskToBack(true); // bağlamırıq — geri qayıdanda dərhal açılır
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            if (svc != null) playUri(i.getData()); else pendingUri = i.getData();
        }
    }

    private void playUri(Uri u) {
        String name = u.getLastPathSegment();
        if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        List<Song> one = new ArrayList<>();
        one.add(new Song(u, name != null ? name : L.t("Fayl")));
        svc.playList(one, 0, false);
        showPlayer(true);
    }

    // ================================================================ icazələr

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{audioPermission(), Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            requestPermissions(new String[]{audioPermission()}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (hasPermission()) lib.load();
        refresh();
    }

    // ================================================================ Library / Service

    @Override
    public void onLibraryChanged() {
        albums = artists = folders = null;
        playlists.reload();
        openGroup = null;
        openList = null;
        refresh();
    }

    @Override
    public void onPlayerState() {
        if (svc == null) return;
        Song s = svc.current();
        mini.setVisibility(s != null ? View.VISIBLE : View.GONE);
        if (s != null) {
            miniTitle.setText(s.title);
            miniArtist.setText(s.artist);
            pTitle.setText(s.title);
            pArtist.setText(s.artist.isEmpty() ? s.album : s.artist + "  ·  " + s.album);
            if (s.id != shownSongId) {
                shownSongId = s.id;
                Art.load(this, miniArt, s, 128, notePh);
                Art.load(this, pArt, s, 720, placeholder(R.drawable.i_note));
                adapter.notifyDataSetChanged(); // cari mahnını siyahıda vurğula
            }
            boolean fav = favs.contains(s.id);
            pFav.setText((fav ? "♥  " : "♡  ") + L.t("SEVİMLİ"));
            pFav.setTextColor(fav ? Ui.ON_ACCENT : Ui.TEXT);
            pFav.setBackgroundColor(fav ? Ui.ACCENT : Ui.SURFACE2);
        }
        int playIcon = svc.isPlaying() ? R.drawable.i_pause : R.drawable.i_play;
        miniPlay.setImageResource(playIcon);
        pPlay.setImageResource(playIcon);
        pShuffle.setColorFilter(svc.isShuffle() ? Ui.ACCENT : Ui.TEXT2);
        int rep = svc.repeatMode();
        pRepeat.setImageResource(rep == MusicService.REPEAT_ONE ? R.drawable.i_repeat_one : R.drawable.i_repeat);
        pRepeat.setColorFilter(rep == MusicService.REPEAT_OFF ? Ui.TEXT2 : Ui.ACCENT);
        boolean sleep = svc.sleepRemaining() > 0 || svc.sleepAfterSong();
        pSleep.setTextColor(sleep ? Ui.ON_ACCENT : Ui.TEXT);
        pSleep.setBackgroundColor(sleep ? Ui.ACCENT : Ui.SURFACE2);
        boolean eq = svc.eqPreset() >= 0;
        pEq.setTextColor(eq ? Ui.ON_ACCENT : Ui.TEXT);
        pEq.setBackgroundColor(eq ? Ui.ACCENT : Ui.SURFACE2);
        if (tab == TAB_QUEUE) refresh();
        updateProgress();
    }

    private void updateProgress() {
        if (svc == null || svc.current() == null) return;
        int pos = svc.position(), dur = Math.max(1, svc.duration());
        miniProgress.setPivotX(0);
        miniProgress.setScaleX(Math.min(1f, pos / (float) dur));
        if (player.getVisibility() == View.VISIBLE && !seeking) {
            pSeek.setMax(dur);
            pSeek.setProgress(pos);
            pPos.setText(Song.time(pos));
            pDur.setText(Song.time(dur));
        }
        long rem = svc.sleepRemaining();
        pSleep.setText(rem > 0 ? L.t("TAYMER") + " " + Song.time(rem) : svc.sleepAfterSong() ? L.t("TAYMER") + " ⏹" : L.t("TAYMER"));
    }

    // ================================================================ tablar və siyahı

    private void setupTabs() {
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(L.t(TABS[i]).toUpperCase(L.locale()));
            t.setTypeface(Ui.BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setLetterSpacing(0.05f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), 0, dp(14), 0);
            t.setMinHeight(dp(36));
            t.setOnClickListener(v -> {
                tab = idx;
                openGroup = null;
            openList = null;
                paintTabs();
                refresh();
                list.setSelection(0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.rightMargin = dp(6);
            tabsRow.addView(t, lp);
        }
        paintTabs();
    }

    private void paintTabs() {
        for (int i = 0; i < tabsRow.getChildCount(); i++) {
            TextView t = (TextView) tabsRow.getChildAt(i);
            boolean sel = i == tab;
            t.setTextColor(sel ? Ui.ON_ACCENT : Ui.TEXT2);
            t.setBackgroundColor(sel ? Ui.ACCENT : Ui.SURFACE2);
        }
    }

    private static final class Holder {
        ImageView art;
        TextView title, sub, right;
    }

    private void setupList() {
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int p) { return rows.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                View v = cv != null ? cv : makeRow();
                bindRow((Holder) v.getTag(), rows.get(p));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> onRowClick(pos));
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            onRowLongClick(pos);
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { refresh(); }
        });

        crumb.setCompoundDrawablesWithIntrinsicBounds(R.drawable.i_back, 0, 0, 0);
        crumb.setOnClickListener(v -> {
            openGroup = null;
            openList = null;
            refresh();
        });
        empty.setOnClickListener(v -> { if (!hasPermission()) requestPerms(); });

        mini.setOnClickListener(v -> showPlayer(true));
        miniPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        miniNext().setOnClickListener(v -> { if (svc != null) svc.next(); });
        findViewById(R.id.btn_sleep).setOnClickListener(v -> sleepMenu());
        findViewById(R.id.btn_eq).setOnClickListener(v -> eqMenu());
        findViewById(R.id.btn_sleep).setBackground(Ui.pressable(0, Ui.SURFACE3));
        findViewById(R.id.btn_eq).setBackground(Ui.pressable(0, Ui.SURFACE3));
    }

    private View miniNext() { return findViewById(R.id.mini_next); }

    private View makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setBackground(Ui.pressable(0, Ui.SURFACE2));
        row.setMinimumHeight(dp(64));

        Holder h = new Holder();
        h.art = new ImageView(this);
        h.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.art.setBackgroundColor(Ui.SURFACE2);
        row.addView(h.art, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(10), 0);
        h.title = new TextView(this);
        h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        h.title.setTypeface(Ui.MEDIUM);
        h.title.setSingleLine(true);
        h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.sub = new TextView(this);
        h.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.sub.setTextColor(Ui.TEXT2);
        h.sub.setSingleLine(true);
        h.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(h.title);
        col.addView(h.sub);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        h.right = new TextView(this);
        h.right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.right.setTextColor(Ui.TEXT2);
        row.addView(h.right);

        row.setTag(h);
        return row;
    }

    private void bindRow(Holder h, Object o) {
        h.art.setTag(R.id.art_key, null);
        if (o == SHUFFLE_ROW) {
            int n = countSongs();
            h.art.setImageResource(R.drawable.i_shuffle);
            h.art.setColorFilter(Ui.ON_ACCENT);
            h.art.setPadding(dp(12), dp(12), dp(12), dp(12));
            h.art.setBackgroundColor(Ui.ACCENT);
            h.title.setText(L.t("Qarışıq oynat"));
            h.title.setTextColor(Ui.ACCENT);
            h.sub.setText(n + L.t(" mahnı"));
            h.right.setText("");
            return;
        }
        if (o == NEW_LIST_ROW) {
            h.art.setImageResource(R.drawable.i_queue);
            h.art.setColorFilter(Ui.ACCENT);
            h.art.setPadding(dp(12), dp(12), dp(12), dp(12));
            h.art.setBackgroundColor(Ui.SURFACE2);
            h.title.setText(L.t("+ Yeni pleylist"));
            h.title.setTextColor(Ui.ACCENT);
            h.sub.setText(playlists.all.size() + " pleylist");
            h.right.setText("");
            return;
        }
        h.art.setColorFilter(null);
        h.art.setPadding(0, 0, 0, 0);
        h.art.setBackgroundColor(Ui.SURFACE2);
        if (o instanceof Song) {
            Song s = (Song) o;
            Song cur = svc != null ? svc.current() : null;
            boolean playingNow = cur != null && cur.id == s.id;
            h.title.setText(s.title);
            h.title.setTextColor(playingNow ? Ui.ACCENT : Ui.TEXT);
            h.sub.setText(favs.contains(s.id) ? "♥ " + s.artist : s.artist);
            h.right.setText(Song.time(s.duration));
            Art.load(this, h.art, s, 128, notePh);
        } else if (o instanceof Playlists.Playlist) {
            Playlists.Playlist p = (Playlists.Playlist) o;
            h.title.setText(p.name);
            h.title.setTextColor(Ui.TEXT);
            h.sub.setText(p.songs.size() + L.t(" mahnı"));
            h.right.setText("");
            if (p.songs.isEmpty()) h.art.setImageDrawable(listPh);
            else Art.load(this, h.art, p.songs.get(0), 128, listPh);
        } else {
            Library.Group g = (Library.Group) o;
            h.title.setText(g.name);
            h.title.setTextColor(Ui.TEXT);
            h.sub.setText(g.songs.size() + L.t(" mahnı"));
            h.right.setText("");
            if (tab == TAB_ALBUMS) Art.load(this, h.art, g.songs.get(0), 128, notePh);
            else h.art.setImageDrawable(tab == TAB_ARTISTS ? personPh : folderPh);
        }
    }

    private int countSongs() {
        int n = 0;
        for (Object o : rows) if (o instanceof Song) n++;
        return n;
    }

    private void refresh() {
        rows.clear();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        crumb.setVisibility(openGroup != null || openList != null ? View.VISIBLE : View.GONE);
        if (openGroup != null) crumb.setText(openGroup.name);
        else if (openList != null) crumb.setText(openList.name);

        if (!hasPermission()) {
            showEmpty(L.t("Musiqilərə baxmaq üçün icazə lazımdır.\n\nİcazə vermək üçün toxun"));
            adapter.notifyDataSetChanged();
            return;
        }

        if (tab == TAB_QUEUE) {
            if (svc != null) for (Song s : svc.queue()) if (matches(s, q)) rows.add(s);
        } else if (openGroup != null) {
            for (Song s : openGroup.songs) if (matches(s, q)) rows.add(s);
        } else if (openList != null) {
            for (Song s : openList.songs) if (matches(s, q)) rows.add(s);
        } else if (tab == TAB_LISTS) {
            if (q.isEmpty()) rows.add(NEW_LIST_ROW);
            for (Playlists.Playlist p : playlists.all) {
                if (q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q)) rows.add(p);
            }
        } else if (tab == TAB_SONGS) {
            for (Song s : lib.songs) if (matches(s, q)) rows.add(s);
        } else if (tab == TAB_FAVS) {
            for (Song s : lib.songs) if (favs.contains(s.id) && matches(s, q)) rows.add(s);
        } else {
            List<Library.Group> groups = groups();
            for (Library.Group g : groups) {
                if (q.isEmpty() || g.name.toLowerCase(Locale.ROOT).contains(q)) rows.add(g);
            }
        }
        if (tab != TAB_QUEUE && countSongs() > 1) rows.add(0, SHUFFLE_ROW);

        if (rows.isEmpty()) {
            if (!lib.loaded) showEmpty(L.t("Yüklənir…"));
            else if (!q.isEmpty()) showEmpty(L.t("Heç nə tapılmadı"));
            else if (tab == TAB_FAVS) showEmpty(L.t("Sevimli mahnı yoxdur.\nMahnıya uzun bas → Sevimlilərə əlavə et"));
            else if (tab == TAB_QUEUE) showEmpty(L.t("Növbə boşdur"));
            else if (openList != null) showEmpty(L.t("Pleylist boşdur.\nMahnıya uzun bas → Pleylistə əlavə et"));
            else showEmpty(L.t("Telefonda musiqi tapılmadı"));
        } else {
            empty.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }

    private void showEmpty(String t) {
        empty.setText(t);
        empty.setVisibility(View.VISIBLE);
    }

    private List<Library.Group> groups() {
        if (tab == TAB_ALBUMS) {
            if (albums == null) albums = lib.albums();
            return albums;
        } else if (tab == TAB_ARTISTS) {
            if (artists == null) artists = lib.artists();
            return artists;
        } else {
            if (folders == null) folders = lib.folders();
            return folders;
        }
    }

    private static boolean matches(Song s, String q) {
        if (q.isEmpty()) return true;
        return s.title.toLowerCase(Locale.ROOT).contains(q)
                || s.artist.toLowerCase(Locale.ROOT).contains(q)
                || s.album.toLowerCase(Locale.ROOT).contains(q);
    }

    private List<Song> songsInRows() {
        List<Song> out = new ArrayList<>();
        for (Object o : rows) if (o instanceof Song) out.add((Song) o);
        return out;
    }

    private void onRowClick(int pos) {
        if (svc == null) return;
        Object o = rows.get(pos);
        hideKeyboard();
        if (o == SHUFFLE_ROW) {
            svc.playList(songsInRows(), -1, true);
        } else if (o instanceof Song) {
            if (tab == TAB_QUEUE) {
                svc.jumpTo(svc.queue().indexOf(o));
            } else {
                List<Song> all = songsInRows();
                svc.playList(all, all.indexOf(o), false);
            }
        } else if (o == NEW_LIST_ROW) {
            askName(L.t("Yeni pleylist"), "", name -> {
                if (playlists.create(name) == null) Ui.toast(root, L.t("Bu adla pleylist var"));
                refresh();
            });
        } else if (o instanceof Playlists.Playlist) {
            openList = (Playlists.Playlist) o;
            refresh();
            list.setSelection(0);
        } else {
            openGroup = (Library.Group) o;
            refresh();
            list.setSelection(0);
        }
    }

    private void onRowLongClick(int pos) {
        if (svc == null) return;
        Object o = rows.get(pos);
        if (o == SHUFFLE_ROW || o == NEW_LIST_ROW) return;
        if (o instanceof Playlists.Playlist) {
            listMenu((Playlists.Playlist) o);
            return;
        }
        if (o instanceof Song) {
            final Song s = (Song) o;
            final boolean fav = favs.contains(s.id);
            List<String> items = new ArrayList<>();
            items.add(L.t("Növbəti oynat"));
            items.add(L.t("Növbəyə əlavə et"));
            items.add(fav ? L.t("Sevimlilərdən çıxar") : L.t("Sevimlilərə əlavə et"));
            items.add(L.t("Pleylistə əlavə et"));
            final Playlists.Playlist inList = openList;
            if (inList != null) {
                items.add(L.t("Yuxarı"));
                items.add(L.t("Aşağı"));
                items.add(L.t("!Pleylistdən çıxar"));
            } else if (tab == TAB_QUEUE) {
                items.add(L.t("!Növbədən çıxar"));
            }
            Sheet.menu(root, s.title, items.toArray(new String[0]), i -> {
                if (i == 0) { svc.playNext(s); Ui.toast(root, L.t("Növbəti oynanacaq")); }
                else if (i == 1) { svc.enqueue(s); Ui.toast(root, L.t("Növbəyə əlavə olundu")); }
                else if (i == 2) toggleFav(s);
                else if (i == 3) addToList(Collections.singletonList(s));
                else if (inList != null) {
                    if (i == 4) playlists.move(inList, s, -1);
                    else if (i == 5) playlists.move(inList, s, 1);
                    else playlists.remove(inList, s);
                    refresh();
                } else svc.removeAt(svc.queue().indexOf(s));
            });
        } else {
            final Library.Group g = (Library.Group) o;
            Sheet.menu(root, g.name, new String[]{L.t("Oynat"), L.t("Qarışıq oynat"), L.t("Növbəyə əlavə et"), L.t("Pleylistə əlavə et")}, i -> {
                if (i == 0) svc.playList(g.songs, 0, false);
                else if (i == 1) svc.playList(g.songs, -1, true);
                else if (i == 3) addToList(g.songs);
                else {
                    for (Song s : g.songs) svc.enqueue(s);
                    Ui.toast(root, g.songs.size() + L.t(" mahnı növbəyə əlavə olundu"));
                }
            });
        }
    }

    // ================================================================ pleylistlər

    interface NameCb { void on(String name); }

    private void askName(String title, String initial, final NameCb cb) {
        final EditText in = Ui.input(this, L.t("Pleylistin adı"));
        in.setText(initial);
        in.setSelection(in.length());
        new Sheet(root, title).add(in)
                .button(L.t("Ləğv et"), false, null)
                .button(L.t("Saxla"), true, () -> {
                    String n = in.getText().toString().trim();
                    if (!n.isEmpty()) cb.on(n);
                })
                .show();
        in.requestFocus();
        in.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(in, 0);
        }, 150);
    }

    private void addToList(final List<Song> songs) {
        final List<Playlists.Playlist> all = playlists.all;
        String[] items = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) items[i] = all.get(i).name + "  ·  " + all.get(i).songs.size();
        items[all.size()] = L.t("+ Yeni pleylist");
        Sheet.menu(root, L.t("Pleylistə əlavə et"), items, i -> {
            if (i == all.size()) {
                askName(L.t("Yeni pleylist"), "", name -> {
                    Playlists.Playlist p = playlists.create(name);
                    if (p == null) { Ui.toast(root, L.t("Bu adla pleylist var")); return; }
                    playlists.add(p, songs);
                    Ui.toast(root, "\"" + p.name + L.t("\" yaradıldı"));
                    if (tab == TAB_LISTS) refresh();
                });
            } else {
                Playlists.Playlist p = all.get(i);
                int n = playlists.add(p, songs);
                Ui.toast(root, n == 0 ? L.t("Artıq pleylistdədir") : n + L.t(" mahnı əlavə olundu"));
                if (tab == TAB_LISTS) refresh();
            }
        });
    }

    private void listMenu(final Playlists.Playlist p) {
        Sheet.menu(root, p.name, new String[]{L.t("Oynat"), L.t("Qarışıq oynat"), L.t("Növbəyə əlavə et"), L.t("Adını dəyiş"), L.t("!Sil")}, i -> {
            if (i < 3 && p.songs.isEmpty()) { Ui.toast(root, L.t("Pleylist boşdur")); return; }
            if (i == 0) svc.playList(new ArrayList<>(p.songs), 0, false);
            else if (i == 1) svc.playList(new ArrayList<>(p.songs), -1, true);
            else if (i == 2) {
                for (Song s : p.songs) svc.enqueue(s);
                Ui.toast(root, p.songs.size() + L.t(" mahnı növbəyə əlavə olundu"));
            } else if (i == 3) {
                askName(L.t("Adını dəyiş"), p.name, name -> {
                    if (!playlists.rename(p, name)) Ui.toast(root, L.t("Ad dəyişmədi"));
                    refresh();
                });
            } else {
                Sheet.confirm(root, L.t("Pleylisti sil"), "\"" + p.name + L.t("\" silinsin? Mahnılar telefonda qalır."), L.t("Sil"), () -> {
                    playlists.delete(p);
                    refresh();
                });
            }
        });
    }

    private void toggleFav(Song s) {
        if (!favs.remove(s.id)) favs.add(s.id);
        StringBuilder b = new StringBuilder();
        for (long id : favs) b.append(id).append(',');
        prefs.edit().putString("favs", b.toString()).apply();
        if (tab == TAB_FAVS) refresh(); else adapter.notifyDataSetChanged();
        onPlayerState();
    }

    // ================================================================ tam ekran pleyer

    private void setupPlayer() {
        findViewById(R.id.p_close).setOnClickListener(v -> showPlayer(false));
        findViewById(R.id.p_queue).setOnClickListener(v -> {
            showPlayer(false);
            tab = TAB_QUEUE;
            openGroup = null;
            openList = null;
            paintTabs();
            refresh();
            if (svc != null) list.setSelection(Math.max(0, svc.index() - 2));
        });
        pPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        findViewById(R.id.p_next).setOnClickListener(v -> { if (svc != null) svc.next(); });
        findViewById(R.id.p_prev).setOnClickListener(v -> { if (svc != null) svc.prev(); });
        pShuffle.setOnClickListener(v -> { if (svc != null) svc.setShuffle(!svc.isShuffle()); });
        pRepeat.setOnClickListener(v -> {
            if (svc == null) return;
            svc.cycleRepeat();
            int r = svc.repeatMode();
            Ui.toast(root, r == MusicService.REPEAT_ONE ? L.t("Bir mahnını təkrarla")
                    : r == MusicService.REPEAT_ALL ? L.t("Hamısını təkrarla") : L.t("Təkrar söndürüldü"));
        });
        pFav.setOnClickListener(v -> {
            if (svc != null && svc.current() != null && svc.current().id >= 0) toggleFav(svc.current());
        });
        pSleep.setOnClickListener(v -> sleepMenu());
        pEq.setOnClickListener(v -> eqMenu());
        for (int id : new int[]{R.id.p_close, R.id.p_queue, R.id.p_next, R.id.p_prev, R.id.p_shuffle, R.id.p_repeat}) {
            findViewById(id).setBackground(Ui.pressable(0, Ui.SURFACE3));
        }
        ((ImageView) findViewById(R.id.p_close)).setColorFilter(Ui.TEXT);
        ((ImageView) findViewById(R.id.p_queue)).setColorFilter(Ui.TEXT);

        pSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) pPos.setText(Song.time(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { seeking = true; }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (svc != null) svc.seekTo(sb.getProgress());
            }
        });

        // albom şəkli kvadrat olsun
        root.post(() -> {
            int w = root.getWidth() - dp(44);
            int h = root.getHeight();
            int side = Math.min(w, (int) (h * 0.46f));
            ViewGroup.LayoutParams lp = pArt.getLayoutParams();
            lp.width = side;
            lp.height = side;
            ((LinearLayout.LayoutParams) lp).gravity = Gravity.CENTER_HORIZONTAL;
            pArt.setLayoutParams(lp);
        });
    }

    private void showPlayer(boolean show) {
        if (show == (player.getVisibility() == View.VISIBLE)) return;
        hideKeyboard();
        if (show) {
            player.setVisibility(View.VISIBLE);
            player.setTranslationY(dp(60));
            player.setAlpha(0f);
            player.animate().translationY(0).alpha(1f).setDuration(160).start();
            updateProgress();
        } else {
            player.animate().translationY(dp(60)).alpha(0f).setDuration(130)
                    .withEndAction(() -> player.setVisibility(View.GONE)).start();
        }
    }

    private void sleepMenu() {
        if (svc == null) return;
        long rem = svc.sleepRemaining();
        String title = rem > 0 ? L.t("Yuxu taymeri — ") + Song.time(rem) + L.t(" qalıb")
                : svc.sleepAfterSong() ? L.t("Yuxu taymeri — mahnı bitəndə") : L.t("Yuxu taymeri");
        final int[] mins = {15, 30, 45, 60, 90, -1, 0};
        Sheet.menu(root, title, new String[]{L.t("15 dəqiqə"), L.t("30 dəqiqə"), L.t("45 dəqiqə"), L.t("1 saat"), L.t("1.5 saat"),
                L.t("Bu mahnı bitəndə"), L.t("!Taymeri söndür")}, i -> {
            svc.setSleep(mins[i]);
            if (mins[i] != 0) Ui.toast(root, L.t("Taymer quruldu"));
        });
    }

    private void eqMenu() {
        if (svc == null) return;
        final String[] presets = svc.eqPresets();
        if (presets.length == 0) {
            Ui.toast(root, L.t("Bu telefonda ekvalayzer dəstəklənmir"));
            return;
        }
        String[] items = new String[presets.length + 1];
        int cur = svc.eqPreset();
        for (int i = 0; i < presets.length; i++) items[i] = (i == cur ? "●  " : "") + presets[i];
        items[presets.length] = L.t("!Ekvalayzeri söndür");
        Sheet.menu(root, L.t("Ekvalayzer"), items, i -> svc.setEqPreset(i == presets.length ? -1 : i));
    }

    // ================================================================ köməkçilər

    private Drawable placeholder(int res) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(Ui.TEXT2);
        return new InsetDrawable(d, dp(12));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
