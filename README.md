# book recon

Book recon is a service to find books via Telegram Bot.

[RU](README.ru.md)

## Features

- UI: Telegram bot
- Supported providers:
  - [Flibusta](http://flibusta.site)
  - RuTracker (via Jackett)
  - [RoyalLib](https://royallib.com)
- Locales: en (default), ru — configured via `LOCALE` env variable

## Quick start

```bash
cp .env.example .env

# edit .env

# run
docker compose up -d
```

## RuTracker setup

RuTracker works through [Jackett](https://github.com/Jackett/Jackett) — a proxy server for torrent trackers.

### 1. Set the API key

Generate a random string (e.g. `openssl rand -hex 16`) and put it in two places:

**jackett/ServerConfig.json** (template already exists in the repo):
```json
{
  "APIKey": "your_key_here"
}
```

**.env:**
```
JACKETT_API_KEY=your_key_here
```

The keys must match. The `jackett/ServerConfig.json` file is mounted into the container automatically via `docker-compose.yml`.

### 2. Start Jackett

```bash
docker compose up -d jackett
```

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

## Roadmap

- Support audio books
