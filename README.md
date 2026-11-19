# Lite Music

A light, offline, ad-free MP3 player. Pure Android Java — no libraries, no ads, no trackers, **no internet permission**.

## Features
- Songs, albums, artists, folders, favorites, playlists, queue
- Shuffle, repeat, sleep timer, equalizer presets
- Notification and lock-screen controls, home-screen widget
- Opens audio files from file managers
- English, Azerbaijani and Russian (follows phone language)

## Permissions
- `READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE` — to find and play the music files on your device
- `FOREGROUND_SERVICE, POST_NOTIFICATIONS, WAKE_LOCK` — to keep playing in the background and show playback controls

## Build
Open the folder in Android Studio (AGP 9.2.1, Gradle 9.4.1, compileSdk 35) and press **Run**.
Release builds are minified with R8. To sign a release, put a `keystore.properties` file
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project root — it is git-ignored.

## Privacy
Lite Music collects no data. See [PRIVACY.md](PRIVACY.md) or the
[online privacy policy](https://aliaghayev132.github.io/lite-music/).

---
**AZ:** Lite Music — reklamsız, internetsiz, yüngül Android tətbiqi. Heç bir məlumat toplamır.
**RU:** Lite Music — лёгкое приложение для Android без рекламы и без доступа к интернету. Не собирает данные.
