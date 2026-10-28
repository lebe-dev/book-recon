# book recon

Book Recon — сервис для поиска и скачивания книг через Telegram Bot.

[EN](README.md)

## Возможности

- Интерфейс: Telegram-бот
- Поддерживаемые источники:
  - [Flibusta](http://flibusta.site)
  - RuTracker (через Jackett)
  - [RoyalLib](https://royallib.com)
- Аудиокниги с RuTracker: выбор файлов из раздачи, прогресс в Telegram, скачивание через веб
- Поддержка языков: en (по умолчанию), ru — переключается через переменную `LOCALE`

## Быстрый старт

```bash
cp .env.example .env

# отредактировать .env под свои нужды

# запустить
docker compose up -d
```

## Настройка RuTracker

RuTracker работает через [Jackett](https://github.com/Jackett/Jackett) — прокси-сервер для торрент-трекеров.

### 1. Установить API-ключ

Сгенерируйте случайную строку (например, `openssl rand -hex 16`) и укажите её в двух местах:

**jackett/ServerConfig.json** (шаблон уже есть в репозитории):
```json
{
  "APIKey": "your_key_here"
}
```

**.env:**
```
JACKETT_API_KEY=your_key_here
```

Ключи должны совпадать. Файл `jackett/ServerConfig.json` автоматически монтируется в контейнер через `docker-compose.yml`.

### 2. Запустить Jackett

```bash
docker compose up -d jackett
```

### 3. Добавить индексер RuTracker

Порт Jackett привязан только к localhost. Для доступа к веб-интерфейсу используйте http://localhost:9117 или SSH-туннель:

```bash
ssh -L 9117:localhost:9117 ваш-сервер
```

Затем откройте http://localhost:9117 в браузере:

1. Нажмите **Add Indexer**
2. Найдите **RuTracker.RU**
3. Введите логин и пароль от RuTracker
4. Нажмите **Okay**

### 4. Включить провайдер

В файле `.env`:

```
RUTRACKER_ENABLED=true
```

Перезапустить бот:

```bash
docker compose up -d
```

## Аудиокниги

Аудиокниги ищутся на RuTracker через тот же Jackett (см. настройку выше), но включаются отдельно от `RUTRACKER_ENABLED`.

Как это работает:

1. Пользователь пишет `/audio война и мир`. Поиск идёт только по разделам из `AUDIOBOOKS_CATEGORIES`.
2. Бот показывает список раздач, пользователь выбирает одну и получает список аудиофайлов с галочками: можно отметить отдельные файлы, целые папки (CD1, CD2…) или всё сразу. Файлы больше `AUDIOBOOKS_MAX_FILE_SIZE` помечены 🚫.
3. После нажатия «Скачать» качаются **только выбранные файлы**. Сообщение со статусом обновляется: очередь, процент, скорость, пиры, оставшееся время. Скачивание можно отменить кнопкой.
4. Когда всё скачано, бот присылает отдельное сообщение со ссылкой на веб-страницу: там плеер для каждого файла, скачивание по одному и «Скачать всё (zip)». Если загрузка встала в очередь, бот пишет и о её старте. Об ошибках тоже приходит отдельное сообщение.
5. Ссылка живёт `AUDIOBOOKS_LINK_TTL`, потом файлы удаляются с диска.

`/audio` без запроса показывает активные загрузки и готовые ссылки пользователя.

### Почему веб, а не файлы в Telegram

Telegram Bot API принимает от бота файлы только до **50 МБ** (скачивать бот может до 20 МБ). Аудиокниги часто весят сотни мегабайт, а `.m4b` бывает одним файлом на 1 ГБ. Обойти лимит можно только своим [telegram-bot-api](https://github.com/tdlib/telegram-bot-api) сервером, поэтому бот отдаёт файлы по HTTP.

### Включение

В `.env`:

```
AUDIOBOOKS_ENABLED=true
AUDIOBOOKS_PUBLIC_URL=https://audio.example.com
JACKETT_API_KEY=...
```

Встроенный HTTP-сервер слушает `AUDIOBOOKS_HTTP_LISTEN` (по умолчанию `:8080`), в `docker-compose.yml` порт опубликован только на `127.0.0.1:8080`. Наружу его выставляет nginx:

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

Ссылки не защищены: в URL случайный 128-битный идентификатор, а страницы отдают `X-Robots-Tag: noindex`. Если нужна защита, добавьте basic auth или allowlist IP в nginx.

Файлы хранятся в `AUDIOBOOKS_DOWNLOAD_DIR` (в docker-compose `/app/data/audiobooks`, на volume), поэтому ссылки переживают перезапуск. Загрузки, прерванные перезапуском, отменяются, и пользователь получает об этом сообщение.

### Управление из бота

Администраторы командой `/audiobooks` видят статус (активные загрузки, очередь, занятое место) и включают или выключают аудиокниги. Состояние хранится в SQLite. При выключении новые поиски и загрузки запрещены, текущие загрузки докачиваются, готовые ссылки продолжают работать.

`AUDIOBOOKS_ENABLED` главнее: при `false` фича выключена полностью, HTTP-сервер не запускается, и из бота её не включить.

### Рейтинг на RuTracker

В `.torrent` вшит ваш passkey, поэтому трекер учитывает скачанное. С `AUDIOBOOKS_ALLOW_UPLOAD=true` (по умолчанию) бот раздаёт во время скачивания, после завершения раздача не продолжается.

## Конфигурация

Переменные окружения загружаются из `.env` (см. `.env.example`):

| Переменная | Описание | По умолчанию |
|---|---|---|
| `TELEGRAM_TOKEN` | Токен бота (обязательно) | — |
| `ALLOWED_USERS` | Список разрешённых пользователей через запятую (без `@`) | — |
| `ADMIN_USERS` | Список администраторов через запятую | — |
| `LOCALE` | Язык интерфейса бота: `en`, `ru` | `en` |
| `DB_PATH` | Путь к файлу SQLite | `book-recon.db` |
| `LOG_LEVEL` | Уровень логирования | `info` |
| `PROVIDER_TIMEOUT` | Таймаут поиска у одного провайдера | `30s` |
| `FLIBUSTA_BASE_URL` | Базовый URL Flibusta | `https://flibusta.is` |
| `FLIBUSTA_ENABLED` | Включить Flibusta | `true` |
| `ROYALLIB_BASE_URL` | Базовый URL RoyalLib | `https://royallib.com` |
| `ROYALLIB_ENABLED` | Включить RoyalLib | `false` |
| `RUTRACKER_ENABLED` | Включить RuTracker | `false` |
| `JACKETT_URL` | URL инстанции Jackett | `http://localhost:9117` |
| `JACKETT_API_KEY` | API-ключ Jackett | — |
| `JACKETT_INDEXER` | Имя индексера Jackett | `rutracker` |
| `JACKETT_CATEGORIES` | ID категорий Torznab через запятую | все |
| `RUTRACKER_DOWNLOAD_TIMEOUT` | Таймаут загрузки торрента | `5m` |
| `RUTRACKER_MAX_BOOKS` | Максимум книг из одного торрента | `5` |
| `RUTRACKER_MAX_TORRENT_SIZE` | Максимальный размер торрента в байтах | `52428800` (50 МБ) |
| `RUTRACKER_DOWNLOAD_DIR` | Директория для загрузки торрентов | `/tmp/book-recon-torrents` |
| `AUDIOBOOKS_ENABLED` | Включить аудиокниги (главный выключатель) | `false` |
| `AUDIOBOOKS_PUBLIC_URL` | Публичный URL страниц скачивания (обязательно при включении) | — |
| `AUDIOBOOKS_HTTP_LISTEN` | Адрес встроенного HTTP-сервера | `:8080` |
| `AUDIOBOOKS_CATEGORIES` | ID категорий Torznab для поиска через запятую | `3030` |
| `AUDIOBOOKS_SEARCH_LIMIT` | Максимум результатов поиска | `30` |
| `AUDIOBOOKS_SEARCH_TIMEOUT` | Таймаут поиска и загрузки .torrent | `30s` |
| `AUDIOBOOKS_FILE_EXTENSIONS` | Расширения файлов, доступных для выбора (`*` — все) | `mp3,m4a,m4b,aac,ogg,opus,flac,wav,wma` |
| `AUDIOBOOKS_DOWNLOAD_DIR` | Директория для файлов (нужен постоянный volume) | `audiobooks` |
| `AUDIOBOOKS_MAX_DOWNLOAD_SIZE` | Максимальный суммарный размер выбранных файлов | `4GB` |
| `AUDIOBOOKS_MAX_FILE_SIZE` | Максимальный размер одного файла | `2GB` |
| `AUDIOBOOKS_STORAGE_MAX_SIZE` | Квота на все аудиокниги на диске | `20GB` |
| `AUDIOBOOKS_LINK_TTL` | Время жизни ссылки | `24h` |
| `AUDIOBOOKS_DOWNLOAD_TIMEOUT` | Максимальное время одной загрузки | `3h` |
| `AUDIOBOOKS_STALL_TIMEOUT` | Прервать загрузку, если нет данных столько времени | `10m` |
| `AUDIOBOOKS_MAX_CONCURRENT` | Одновременных загрузок (остальные в очереди) | `2` |
| `AUDIOBOOKS_MAX_PER_USER` | Активных загрузок на пользователя | `1` |
| `AUDIOBOOKS_PROGRESS_INTERVAL` | Как часто обновлять прогресс в Telegram | `30s` |
| `AUDIOBOOKS_ALLOW_UPLOAD` | Отдавать данные пирам во время скачивания | `true` |

Размеры принимают `B`, `KB`, `MB`, `GB`, `TB` (двоичные, 1 GB = 1024 MB) или число байт.

## Контроль доступа

Доступ проверяется в следующем порядке: `ALLOWED_USERS` → `ADMIN_USERS` → статус одобрения в БД.

Неизвестные пользователи запускают процесс одобрения: создаётся запрос в статусе ожидания, администраторы получают уведомление с кнопками одобрения/отклонения, пользователь получает уведомление после принятия решения.

`ALLOWED_USERS` — статичный список предварительно одобренных пользователей (для обратной совместимости). Без него бот полностью полагается на динамический процесс одобрения. Идентификаторы администраторов определяются лениво из таблицы `users` — администраторы должны хотя бы раз запустить бота командой `/start`.
