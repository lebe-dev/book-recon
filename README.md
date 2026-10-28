# book recon

Book recon is a service to find books via Telegram Bot.

[RU](README.ru.md)

## Features

- UI: Telegram bot
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
