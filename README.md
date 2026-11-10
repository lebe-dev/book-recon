# book recon

Book recon is a service to find books via Telegram Bot.

[RU](README.ru.md)

## Features

- UI: Telegram bot and a standalone [Android app](#android-app); the app can reach blocked
  sites [through the Book Recon server](#server-for-the-android-app)
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

## Server for the Android app

The Android app works without a server. A Book Recon server is needed only for phones whose
network blocks RuTracker and Flibusta: the app then sends its requests to those sites
through the server. The feature lives in the bot process and uses the bot's access control,
so nothing else has to be installed.

### How it works

1. The user turns on **Через сервер Book Recon** in the app and enters the server address.
   The app registers on the server and gets a bearer token.
2. The app opens `https://t.me/<bot>?start=link_<code>`. When the user taps **Start**, the bot
   links the phone to the Telegram account.
3. Access is decided exactly as for the bot: users in `ALLOWED_USERS`, `ADMIN_USERS` or
   approved earlier are in at once; for unknown users admins get a request marked
   "📱 from the Android app" with approve/deny buttons; denied users stay denied and admins
   get no new requests from them.
4. An approved app opens a WebSocket to `/api/v1/tunnel` for every HTTPS connection to
   RuTracker or Flibusta, and the server connects to the site. TLS stays end to end between
   the phone and the site: the server only copies bytes. The app's built-in browser view
   (Cloudflare checks, RuTracker captcha) goes the same way, because Cloudflare binds its
   check to the IP address.
5. RuTracker search and `.torrent` files come from the server's Jackett, with the RuTracker
   account configured there: the app does not use the login and password from its own
   settings while the server is connected. A `.torrent` can be fetched only for a topic the
   server found in a search during the last 24 hours.
6. Torrent trackers are announced through the tunnel; peers, DHT and incoming connections
   stay direct, because most RuTracker peers sit behind NAT and cannot be reached from the
   server. The app setting "Пиры через сервер" sends peers through the tunnel as well (DHT,
   LSD and uTP are off then, nothing listens for incoming peers); for peers the server
   accepts public IP addresses on ports 1024 and up (`APP_TUNNEL_PEERS`).

### Requirements

- The bot is running and reachable from the internet over HTTPS, with a certificate the
  phone trusts (Let's Encrypt works; self-signed does not).
- The server resolves and reaches `rutracker.org` and `flibusta.is`: a server in a network
  that blocks them is useless for this. Check from the container:
  `docker compose exec book-recon getent hosts rutracker.org`.
- Admins (`ADMIN_USERS`) have sent `/start` to the bot at least once: the bot learns their
  Telegram IDs that way and cannot notify them about requests before.

### 1. Enable the API

Add to `.env`:

```
APP_API_ENABLED=true
```

| Variable | Description | Default |
|---|---|---|
| `APP_API_ENABLED` | Serve the API for the Android app | `false` |
| `APP_API_LISTEN` | Address of the API server inside the container | `:8081` |
| `APP_TUNNEL_HOSTS` | Hosts the tunnel may reach, subdomains included, comma-separated | `rutracker.org,rutracker.cc,t-ru.org,flibusta.is,challenges.cloudflare.com` |
| `APP_TUNNEL_PORTS` | Ports the tunnel may reach | `443,80` |
| `APP_TUNNEL_PEERS` | Let the tunnel reach BitTorrent peers: public IPs, ports 1024+ | `true` |
| `APP_RUTRACKER_BOOK_CATEGORIES` | Torznab categories of e-book searches from the app | `7000` |
| `APP_RUTRACKER_AUDIO_CATEGORIES` | Torznab categories of audiobook searches from the app | `3030` |

RuTracker search for the app is on when `JACKETT_API_KEY` is set (see `.env.example` for
setting up Jackett and the RuTracker indexer). It uses `JACKETT_URL`, `JACKETT_INDEXER` and
`JACKETT_TIMEOUT` of the bot. Without Jackett the app shows RuTracker as unavailable while
connected to the server.

The app uses the host list it gets from the server: only HTTPS requests to these hosts go
through the tunnel, everything else goes direct. If users set another Flibusta mirror in
the app, add it to `APP_TUNNEL_HOSTS`.

### 2. Publish the port

`docker-compose.yml` publishes the API on the loopback interface only; nginx exposes it:

```yaml
services:
  book-recon:
    ports:
      - "127.0.0.1:8080:8080"   # audiobook pages
      - "127.0.0.1:8081:8081"   # Android app API
```

Do not publish `8081` on a public interface: the API speaks plain HTTP.

### 3. Publish the API through nginx

The app talks to `<server address>/api/v1/...`. The tunnel is a WebSocket, so nginx must
pass the `Upgrade` and `Connection` headers and must not cut long connections. A separate
host:

```nginx
server {
    listen 443 ssl;
    server_name books.example.com;
    ssl_certificate     /etc/letsencrypt/live/books.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/books.example.com/privkey.pem;

    location /api/v1/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # The tunnel is a WebSocket
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        # Pages stream through the tunnel; idle tunnels are kept alive by pings
        proxy_buffering off;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

The app address is then `https://books.example.com`.

The same `location` can go into a host that already serves something else, such as the
audiobook pages: add it next to `location /audio/`. The address stays the host root, because
the app appends `/api/v1/` itself.

To serve the API under a path prefix, keep the prefix in the app address and strip it in
nginx. For the address `https://example.com/books`:

```nginx
location /books/api/v1/ {
    proxy_pass http://127.0.0.1:8081/api/v1/;
    # ...the same headers and timeouts as above
}
```

`http2 on` in the server block does not get in the way: the app opens WebSockets over
HTTP/1.1. An nginx behind a TLS-passthrough router (`ssl_preread` on port 443) works too, as
long as TLS ends in the `server` block above.

Apply and check:

```bash
nginx -t && systemctl reload nginx
docker compose up -d book-recon
docker compose logs book-recon | grep "app api listening"
```

### 4. Check from outside

```bash
# A Book Recon server with the API on: 200 and the version
curl https://books.example.com/api/v1/ping

# Calls without a token: 401
curl -i https://books.example.com/api/v1/clients/me

# The WebSocket headers reach the bot through nginx: 401 (no token).
# 426 means nginx drops Upgrade/Connection, 404 means the location is missing.
curl -i --http1.1 -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  "https://books.example.com/api/v1/tunnel?host=rutracker.org&port=443"
```

Then give users the address and the steps from
[Through the Book Recon server](#through-the-book-recon-server).

### Managing users

There is one user list for the bot and the app:

- `/allowed_users` lists approved users; **Удалить доступ** (revoke) stops new tunnels of the
  user's phones at once, and the app shows "Не подключено" on its next check.
- `/blocked_users` lists denied users; **Разблокировать** approves them, and their phones get
  access without a new request.
- Both lists show how many phones each user has linked ("📱 Devices with the app: N").

### API

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/ping` | Tells the app this is a Book Recon server |
| `POST /api/v1/clients` | Registers a phone (`{"deviceName": "..."}`), returns its bearer token |
| `GET /api/v1/clients/me` | Access status (`unlinked`, `pending`, `approved`, `denied`) and the tunnel host list |
| `POST /api/v1/clients/me/link` | One-time `t.me/<bot>?start=link_…` link, valid 15 minutes |
| `DELETE /api/v1/clients/me` | Removes the phone |
| `GET /api/v1/tunnel?host=&port=` | WebSocket tunnel to an allowed host; binary messages carry the TCP bytes |

Every call except the first two needs `Authorization: Bearer <token>`.

Security:

- The server keeps only a SHA-256 hash of each token.
- The tunnel reaches only `APP_TUNNEL_HOSTS` and their subdomains on `APP_TUNNEL_PORTS`, and
  only public IP addresses, so an allowed domain cannot point it into the server's network.
- Only approved phones open tunnels, at most 32 at a time per phone.
- Registrations are limited to 10 per minute; phones that never link a Telegram account are
  removed after a day.

### Troubleshooting

| What the app says | Cause and fix |
|---|---|
| Сервер недоступен (server unreachable) | DNS, certificate or firewall: open `<address>/api/v1/ping` in the phone's browser |
| По этому адресу нет сервера Book Recon… (no Book Recon server at this address) | `ping` answers 404 or not with JSON: `APP_API_ENABLED` is off, the nginx `location` is missing, or the address includes `/api/v1` |
| Сервер ответил ошибкой (server error) | 5xx, usually 502 from nginx while the container is down: `docker compose logs book-recon` |
| The spinner never ends | The user did not tap **Start** in the bot, or no admin has sent `/start` to the bot; the log says `no admin IDs resolved` |
| Approved, but RuTracker does not open | The server cannot reach the site (`failed to reach tunnel target` in the log): check DNS in the container; `tunnel to a host outside the allowlist refused`: add the host to `APP_TUNNEL_HOSTS` |
| Cloudflare check fails with `ERR_TUNNEL_CONNECTION_FAILED` | nginx does not pass the WebSocket headers: the `curl` WebSocket test answers 426; check step 3 |

A Cloudflare check right after approval is expected: for Cloudflare the phone now comes
from the server's IP address.

To turn the feature off, set `APP_API_ENABLED=false` and restart the container. Ask users to
turn the switch off in the app as well: an app that was approved keeps sending RuTracker and
Flibusta to the server until then.

## Android app

The Android app does what the bot does, right on the phone and without a server: it searches
and downloads books from Flibusta and e-books and audiobooks from RuTracker. It needs no
Jackett or FlareSolverr. The UI is in Russian only.

### Install

1. Download `book-recon-<version>.apk` from the
   [GitHub releases](https://github.com/lebe-dev/book-recon/releases) page. Android 14 or
   newer is required.
2. Open the file on the phone and allow installing apps from this source when Android asks.

Flibusta and RuTracker are blocked in Russia: turn on a VPN on the phone, or connect the
app to a Book Recon server (see [Through the Book Recon server](#through-the-book-recon-server)).

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

### Through the Book Recon server

When the phone's network blocks RuTracker and Flibusta, the app can reach them through a
Book Recon server (see [Server for the Android app](#server-for-the-android-app) for the
server side). It is off by default:

1. **Настройки → Через сервер Book Recon**: turn the switch on and enter the server address
   the admin gave you, such as `https://books.example.com`, then tap **Сохранить**.
2. The app opens the bot in Telegram: tap **Start**. This links the phone to your Telegram
   account.
3. Go back to the app. If the bot already knows you, access is granted at once; otherwise
   the app shows a spinner with "Ждите, пока администратор одобрит доступ" until an admin
   decides.

Once approved, the settings say that RuTracker, Flibusta and torrents go through the Book
Recon server. RuTracker search then uses the server's account: the login and password in
**Настройки → RuTracker** are not needed and are used again only when the server is off. If the admin denies the request, the settings say so. Turning the switch off removes
the phone from the server. The Telegram link is valid for 15 minutes: if it expires, tap
**Подключить** again.

### Appearance

**Настройки → Оформление** picks one of six pastel color themes: Черника (blue, the default),
Фисташка (green), Яблоко (red), Голубика (light blue), Зефир (pink) and Какао (brown). Every
theme has a light and a dark variant: by default the app follows the system setting, and
**Тёмная тема** can fix it to light or dark.

### Error reports

Error reports are off by default. **Настройки → Отчёты об ошибках** turns them on: crashes
(including native ones and ANRs) and unexpected download errors go to Sentry with the phone
model and Android version. The Sentry environment is the device model, such as
`google-pixel-11-pro`. The RuTracker login and password are never sent.

### App log

**Настройки → Журнал приложения** shows what the app does: queued, started and finished
downloads, errors, server connection. Two switches choose the detail: **INFO** (on by default)
records events, warnings and errors; **DEBUG** (off by default) adds requests (method, host
and path only, never query strings, cookies or passwords), download progress in 10% steps and
file placement. **Копировать** puts the lines on screen in the clipboard, **В файл** saves the
whole log (up to about 1 MB) through the system file picker, **Очистить** deletes it. The log
stays on the phone; it is never sent anywhere. When you report a problem, switch DEBUG on,
repeat the steps and attach the file.

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
just deploy-android   # build, install on the USB-connected phone and start the app
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

Error reports and R8 mappings use these variables from `.env` or the environment:

```
SENTRY_DSN=https://...@sentry.example.com/1   # baked into the APK; empty disables reports
SENTRY_ORG=...                                # mapping upload: all three are needed
SENTRY_PROJECT=...
SENTRY_AUTH_TOKEN=...
SENTRY_URL=https://sentry.example.com         # only for self-hosted Sentry
```

`SENTRY_DSN` is the same variable the bot reads. A release build uploads its R8 mapping to
Sentry, so stack traces from the app show the original code lines; without the three upload
variables the build prints a warning and skips the upload.

`just release` builds the signed APK and then builds and pushes the Docker image. It fails
before pushing the image if signing is not configured. Attach
`bin/book-recon-<VERSION>.apk` to the GitHub release.
