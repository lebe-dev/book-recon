# Настройка RuTracker

RuTracker работает через [Jackett](https://github.com/Jackett/Jackett) — прокси-сервер для торрент-трекеров.

rutracker.org закрыт Cloudflare-челленджем, поэтому рядом с Jackett в `docker-compose.yml` запускается [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) (headless Chromium). Jackett находит его по `FlareSolverrUrl` в `jackett/ServerConfig.json` (`http://flaresolverr:8191`). Наружу порт не открыт. FlareSolverr занимает ~160 МБ в простое и до ~700 МБ во время решения челленджа (лимит 768M / 1.5 CPU).

## 1. Установить API-ключ

Сгенерируйте случайную строку (например, `openssl rand -hex 16`) и укажите её в двух местах:

**jackett/ServerConfig.json** (шаблон уже есть в репозитории):
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

Ключи должны совпадать. Файл `jackett/ServerConfig.json` автоматически монтируется в контейнер через `docker-compose.yml`.

## 2. Запустить Jackett

```bash
docker compose up -d flaresolverr jackett
```

Если Jackett уже был настроен раньше без FlareSolverr, пропишите `"FlareSolverrUrl": "http://flaresolverr:8191"` в его `ServerConfig.json` при остановленном контейнере (Jackett перезаписывает файл при остановке) или в веб-интерфейсе: **Jackett Configuration → FlareSolverr API URL**.

## 3. Добавить индексер RuTracker

Порт Jackett привязан только к localhost. Для доступа к веб-интерфейсу используйте http://localhost:9117 или SSH-туннель:

```bash
ssh -L 9117:localhost:9117 ваш-сервер
```

Затем откройте http://localhost:9117 в браузере:

1. Нажмите **Add Indexer**
2. Найдите **RuTracker.RU**
3. Введите логин и пароль от RuTracker
4. Нажмите **Okay**

## 4. Включить провайдер

В файле `.env`:

```
RUTRACKER_ENABLED=true
```

Перезапустить бот:

```bash
docker compose up -d
```

