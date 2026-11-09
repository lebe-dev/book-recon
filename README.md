# book recon

Book recon is a service to find books via Telegram Bot.

[RU](README.ru.md)

## Features

- UI: Telegram bot and a standalone [Android app](#android-app)
- Supported providers:
  - [Flibusta](http://flibusta.site)
  - RuTracker (via Jackett)
  - [RoyalLib](https://royallib.com)
- Audiobooks from RuTracker: pick files from a torrent, progress in Telegram, delivery via web
- Locales: en (default), ru — configured via `LOCALE` env variable
- Optional Sentry error reporting — set `SENTRY_DSN` and `SENTRY_ENVIRONMENT`

## Quick start

```bash
cp .env.example .env

# edit .env

# run
docker compose up -d
```

## RuTracker setup

RuTracker works through [Jackett](https://github.com/Jackett/Jackett) — a proxy server for torrent trackers.

rutracker.org is behind a Cloudflare challenge, so `docker-compose.yml` runs [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) (headless Chromium) next to Jackett. Jackett finds it via `FlareSolverrUrl` in `jackett/ServerConfig.json` (`http://flaresolverr:8191`). Its port is not exposed. FlareSolverr uses ~160 MB idle and up to ~700 MB while solving a challenge (limit 768M / 1.5 CPU).

### 1. Set the API key

Generate a random string (e.g. `openssl rand -hex 16`) and put it in two places:

**jackett/ServerConfig.json** (template already exists in the repo):
```json
{
  "APIKey": "your_key_here",
  "FlareSolverrUrl": "http://flaresolverr:8191"
}
```

**.env:**
```
JACKETT_API_KEY=your_key_here
```

The keys must match. The `jackett/ServerConfig.json` file is mounted into the container automatically via `docker-compose.yml`.

### 2. Start Jackett

```bash
docker compose up -d flaresolverr jackett
```

If Jackett was configured earlier without FlareSolverr, set `"FlareSolverrUrl": "http://flaresolverr:8191"` in its `ServerConfig.json` while the container is stopped (Jackett rewrites the file on shutdown), or in the Web UI: **Jackett Configuration → FlareSolverr API URL**.

### 3. Add the RuTracker indexer

Jackett port is bound to localhost only. To access the Web UI, use http://localhost:9117 or use SSH tunnel:

```bash
ssh -L 9117:localhost:9117 your-server
```

Then open http://localhost:9117 in your browser:

1. Click **Add Indexer**
2. Find **RuTracker.RU**
3. Enter your RuTracker username and password
4. Click **Okay**

### 4. Enable the provider

In `.env`:

```
RUTRACKER_ENABLED=true
```

Restart the bot:

```bash
docker compose up -d
```

## Audiobooks

Audiobooks are searched on RuTracker through the same Jackett instance (see above) but are enabled separately from `RUTRACKER_ENABLED`.

1. A user sends `/audio <query>`. Only the categories from `AUDIOBOOKS_CATEGORIES` are searched.
2. The user picks a torrent and gets a checklist of its audio files: single files, whole folders (CD1, CD2…) or everything. Files above `AUDIOBOOKS_MAX_FILE_SIZE` are marked 🚫.
3. Only the selected files are downloaded. The status message shows the queue position, then percent, speed, peers and ETA. A button cancels the download.
4. When the download finishes, the bot sends a separate message with a link to a web page: a player per file, single-file downloads and "Download all (zip)". The bot also sends a message when a queued download starts and when a download fails.
5. The link is valid for `AUDIOBOOKS_LINK_TTL`, after which the files are deleted.

Files are delivered over the web because the Telegram Bot API only lets a bot send files up to **50 MB**, and audiobooks are usually much larger.

Enable it in `.env`:

```
AUDIOBOOKS_ENABLED=true
AUDIOBOOKS_PUBLIC_URL=https://audio.example.com
JACKETT_API_KEY=...
```

The built-in HTTP server listens on `AUDIOBOOKS_HTTP_LISTEN` (`:8080`). docker-compose publishes it on `127.0.0.1:8080`, and nginx exposes it to the internet:

```nginx
server {
    listen 443 ssl;
    server_name audio.example.com;
    # ssl_certificate ...;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        # Large files: stream instead of buffering to disk
        proxy_buffering off;
        proxy_max_temp_file_size 0;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

Links are not protected: they rely on a random 128-bit ID. Add basic auth or an IP allowlist in nginx if you need protection.

Admins use `/audiobooks` to see the status and turn the feature on or off at runtime. `AUDIOBOOKS_ENABLED=false` always wins: it disables the feature entirely. All settings are documented in `.env.example`.

## Android app

The Android app does what the bot does, right on the phone and without a server: it searches
and downloads books from Flibusta and e-books and audiobooks from RuTracker. It needs no
Jackett or FlareSolverr. The UI is in Russian only.

### Install

1. Download `book-recon-<version>.apk` from the
   [GitHub releases](https://github.com/lebe-dev/book-recon/releases) page. Android 14 or
   newer is required.
2. Open the file on the phone and allow installing apps from this source when Android asks.

Flibusta and RuTracker are blocked in Russia: turn on a VPN on the phone before searching.
The app has no built-in proxy.

### First run

Until the storage is set up, the app opens **Настройки** (Settings) at every start:

1. **Доступ ко всем файлам** (all files access): tap the button, enable the permission in
   the system settings and go back. Books and audiobooks are saved to ordinary folders where
   readers and players see them, which needs this permission.
2. **Папка книг** and **Папка аудиокниг**: pick the folders for books and audiobooks, in
   the internal storage or on an SD card. Cloud folders and the system Downloads provider
   are not supported.
3. **RuTracker**: enter the login and password and tap **Проверить вход**. The password is
   encrypted with a key from Android Keystore. If RuTracker asks for a captcha or Cloudflare
   asks for a check, the app opens the page in a browser view: complete it there.

Android asks for the notification permission when the first download starts: downloads run
in the background and show their progress in a notification.

While a download is running, its files live in a hidden `.book-recon` folder inside the
chosen folder, and they are moved into place once the download finishes.

### Update

Install the new APK over the old one: settings, history and downloaded files are kept.
Android accepts an update only when it is signed with the same key. A debug build is
signed with a different key, so remove it before installing a release build.

### Build

Requirements: JDK 17+ and the Android SDK with `ANDROID_HOME` pointing to it.

```bash
just android-build    # debug APK in android/app/build/outputs/apk/debug
just android-test     # unit tests
just android-lint     # Android Lint
just android-install  # install the debug APK with adb
just android-release  # release APK in bin/book-recon-<VERSION>.apk
```

The app version comes from the root `VERSION` file, shared with the bot. The release APK
is signed with the keystore set in `.env` (or in the environment):

```
ANDROID_KEYSTORE=/path/to/release.jks
ANDROID_KEYSTORE_PASSWORD=...
ANDROID_KEY_ALIAS=...
ANDROID_KEY_PASSWORD=...
```

Without these variables `just android-release` builds an unsigned APK that cannot be
installed. Keep the keystore outside the repository and back it up: without it an
installed app cannot be updated, only removed and installed again.

`just release` builds the signed APK and then builds and pushes the Docker image. It fails
before pushing the image if signing is not configured. Attach
`bin/book-recon-<VERSION>.apk` to the GitHub release.
