package az.lite.music;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tərcümə (az / en / ru). Mənbə mətn azərbaycancadır: L.t("Bağla") → seçilmiş dildə.
 * Standart dil telefonun dilinə görədir: az → az, ru → ru, qalan hamısı → en.
 */
final class L {

    static final String[] CODES = {"en", "az", "ru"};
    static final String[] NAMES = {"English", "Azərbaycanca", "Русский"};

    private static String lang = "en";
    private static boolean ready;
    private static final Map<String, String[]> T = new HashMap<>();

    static void p(String az, String en, String ru) { T.put(az, new String[]{en, ru}); }

    static {
        p("Ləğv et", "Cancel", "Отмена");
        p("Saxla", "Save", "Сохранить");
        p("Sil", "Delete", "Удалить");
        p("Mahnılar", "Songs", "Песни");
        p("Albomlar", "Albums", "Альбомы");
        p("İfaçılar", "Artists", "Исполнители");
        p("Qovluqlar", "Folders", "Папки");
        p("Sevimlilər", "Favorites", "Избранное");
        p("Pleylistlər", "Playlists", "Плейлисты");
        p("Növbə", "Queue", "Очередь");
        p("Fayl", "File", "Файл");
        p("SEVİMLİ", "FAVORITE", "ИЗБРАННОЕ");
        p("TAYMER", "TIMER", "ТАЙМЕР");
        p("EKVALAYZER", "EQUALIZER", "ЭКВАЛАЙЗЕР");
        p("İNDİ OXUNUR", "NOW PLAYING", "СЕЙЧАС ИГРАЕТ");
        p("Mahnı, ifaçı, albom axtar…", "Search songs, artists, albums…", "Поиск песен, исполнителей, альбомов…");
        p("Qarışıq oynat", "Shuffle play", "Перемешать");
        p(" mahnı", " songs", " песен");
        p("Musiqilərə baxmaq üçün icazə lazımdır.\n\nİcazə vermək üçün toxun", "Permission is needed to see your music.\n\nTap to grant it", "Нужно разрешение для доступа к музыке.\n\nНажмите, чтобы разрешить");
        p("Yüklənir…", "Loading…", "Загрузка…");
        p("Heç nə tapılmadı", "Nothing found", "Ничего не найдено");
        p("Sevimli mahnı yoxdur.\nMahnıya uzun bas → Sevimlilərə əlavə et", "No favorite songs.\nLong-press a song → Add to favorites", "Нет избранных песен.\nДолгое нажатие на песню → В избранное");
        p("Növbə boşdur", "Queue is empty", "Очередь пуста");
        p("Pleylist boşdur.\nMahnıya uzun bas → Pleylistə əlavə et", "Playlist is empty.\nLong-press a song → Add to playlist", "Плейлист пуст.\nДолгое нажатие на песню → В плейлист");
        p("Telefonda musiqi tapılmadı", "No music found on this phone", "Музыка на телефоне не найдена");
        p("Növbəti oynat", "Play next", "Играть следующей");
        p("Növbəyə əlavə et", "Add to queue", "В очередь");
        p("Sevimlilərdən çıxar", "Remove from favorites", "Убрать из избранного");
        p("Sevimlilərə əlavə et", "Add to favorites", "В избранное");
        p("Pleylistə əlavə et", "Add to playlist", "В плейлист");
        p("Yuxarı", "Move up", "Вверх");
        p("Aşağı", "Move down", "Вниз");
        p("Pleylistdən çıxar", "Remove from playlist", "Убрать из плейлиста");
        p("Növbədən çıxar", "Remove from queue", "Убрать из очереди");
        p("Növbəti oynanacaq", "Will play next", "Будет следующей");
        p("Növbəyə əlavə olundu", "Added to queue", "Добавлено в очередь");
        p("Oynat", "Play", "Играть");
        p(" mahnı növbəyə əlavə olundu", " songs added to queue", " песен добавлено в очередь");
        p("Pleylistin adı", "Playlist name", "Название плейлиста");
        p("Yeni pleylist", "New playlist", "Новый плейлист");
        p("+ Yeni pleylist", "+ New playlist", "+ Новый плейлист");
        p("\" yaradıldı", "\" created", "» создан");
        p("Artıq pleylistdədir", "Already in the playlist", "Уже в плейлисте");
        p(" mahnı əlavə olundu", " songs added", " песен добавлено");
        p("Adını dəyiş", "Rename", "Переименовать");
        p("Pleylist boşdur", "Playlist is empty", "Плейлист пуст");
        p("Ad dəyişmədi", "Name not changed", "Имя не изменено");
        p("Bu adla pleylist var", "A playlist with this name exists", "Плейлист с таким именем уже есть");
        p("Pleylisti sil", "Delete playlist", "Удалить плейлист");
        p("\" silinsin? Mahnılar telefonda qalır.", "\" — delete? Songs stay on the phone.", "» — удалить? Песни останутся на телефоне.");
        p("Bir mahnını təkrarla", "Repeat one", "Повтор одной");
        p("Hamısını təkrarla", "Repeat all", "Повтор всех");
        p("Təkrar söndürüldü", "Repeat off", "Повтор выключен");
        p("Yuxu taymeri — ", "Sleep timer — ", "Таймер сна — ");
        p(" qalıb", " left", " осталось");
        p("Yuxu taymeri — mahnı bitəndə", "Sleep timer — end of song", "Таймер сна — после песни");
        p("Yuxu taymeri", "Sleep timer", "Таймер сна");
        p("15 dəqiqə", "15 minutes", "15 минут");
        p("30 dəqiqə", "30 minutes", "30 минут");
        p("45 dəqiqə", "45 minutes", "45 минут");
        p("1 saat", "1 hour", "1 час");
        p("1.5 saat", "1.5 hours", "1,5 часа");
        p("Bu mahnı bitəndə", "When this song ends", "Когда закончится песня");
        p("Taymeri söndür", "Turn timer off", "Выключить таймер");
        p("Taymer quruldu", "Timer set", "Таймер установлен");
        p("Bu telefonda ekvalayzer dəstəklənmir", "Equalizer isn't supported on this phone", "Эквалайзер не поддерживается");
        p("Ekvalayzeri söndür", "Turn equalizer off", "Выключить эквалайзер");
        p("Ekvalayzer", "Equalizer", "Эквалайзер");
        p("Naməlum ifaçı", "Unknown artist", "Неизвестный исполнитель");
        p("Naməlum albom", "Unknown album", "Неизвестный альбом");
        p("Naməlum", "Unknown", "Неизвестно");
        p("Adsız", "Untitled", "Без названия");
        p("Oynatmaq üçün toxun", "Tap to play", "Нажмите, чтобы играть");
        p("Əvvəlki", "Previous", "Назад");
        p("Pauza", "Pause", "Пауза");
        p("Növbəti", "Next", "Далее");
        p("Oynatma", "Playback", "Воспроизведение");
        p("Dil", "Language", "Язык");
    }

    static String systemDefault() {
        String sys = Locale.getDefault().getLanguage();
        if ("az".equals(sys)) return "az";
        if ("ru".equals(sys) || "uk".equals(sys) || "be".equals(sys)) return "ru";
        return "en";
    }

    static void init(Context c) {
        lang = c.getSharedPreferences("lang", Context.MODE_PRIVATE).getString("lang", systemDefault());
        ready = true;
    }

    static void set(Context c, String code) {
        lang = code;
        c.getSharedPreferences("lang", Context.MODE_PRIVATE).edit().putString("lang", code).commit();
    }

    static String lang() { return lang; }

    static Locale locale() {
        switch (lang) {
            case "az": return new Locale("az");
            case "ru": return new Locale("ru");
            default: return Locale.ENGLISH;
        }
    }

    static String t(String az) {
        if (!ready || "az".equals(lang) || az == null) return az;
        boolean bang = false;
        String[] v = T.get(az);
        if (v == null && az.startsWith("!")) {
            v = T.get(az.substring(1));
            bang = true;
        }
        if (v == null) return az;
        String r = "en".equals(lang) ? v[0] : v[1];
        return bang && !r.startsWith("!") ? "!" + r : r;
    }

    static String[] arr(String... az) {
        String[] out = new String[az.length];
        for (int i = 0; i < az.length; i++) out[i] = t(az[i]);
        return out;
    }

    private L() { }
}
