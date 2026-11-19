package az.lite.music;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

/** Ana ekran / Lite Launcher vidcet səhifəsi üçün kiçik pleyer vidceti. */
public class LiteMusicWidget extends AppWidgetProvider {

    private static String lastTitle = "Lite Music", lastArtist = null;
    private static boolean lastPlaying;
    private static Bitmap lastArt;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        m.updateAppWidget(ids, build(c));
    }

    static void update(Context c, Song s, boolean playing, Bitmap art) {
        if (s != null) {
            lastTitle = s.title;
            lastArtist = s.artist;
        }
        lastPlaying = playing;
        lastArt = art;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, LiteMusicWidget.class));
            if (ids != null && ids.length > 0) m.updateAppWidget(ids, build(c));
        } catch (Exception ignored) { }
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_music);
        v.setTextViewText(R.id.w_title, lastTitle);
        L.init(c);
        v.setTextViewText(R.id.w_artist, lastArtist != null ? lastArtist : L.t("Oynatmaq üçün toxun"));
        v.setImageViewResource(R.id.w_play, lastPlaying ? R.drawable.i_pause : R.drawable.i_play);
        if (lastArt != null) v.setImageViewBitmap(R.id.w_art, lastArt);
        else v.setImageViewResource(R.id.w_art, R.drawable.i_note);
        v.setOnClickPendingIntent(R.id.w_play, MusicService.actionIntent(c, MusicService.ACT_TOGGLE, 21));
        v.setOnClickPendingIntent(R.id.w_next, MusicService.actionIntent(c, MusicService.ACT_NEXT, 22));
        v.setOnClickPendingIntent(R.id.w_prev, MusicService.actionIntent(c, MusicService.ACT_PREV, 23));
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(c, 24, new Intent(c, MainActivity.class), f);
        v.setOnClickPendingIntent(R.id.w_info, open);
        v.setOnClickPendingIntent(R.id.w_art, open);
        return v;
    }
}
