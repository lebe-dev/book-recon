# Конфигурация

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
| `FLIBUSTA_BASE_URL` | Базовый URL Flibusta (зеркала: `https://flibusta.site`, `https://flibusta.is`) | `https://flibusta.site` |
| `FLIBUSTA_ENABLED` | Включить Flibusta | `true` |
| `ROYALLIB_BASE_URL` | Базовый URL RoyalLib | `https://royallib.com` |
| `ROYALLIB_ENABLED` | Включить RoyalLib | `false` |
| `RUTRACKER_ENABLED` | Включить RuTracker | `false` |
| `JACKETT_URL` | URL инстанции Jackett | `http://localhost:9117` |
| `JACKETT_API_KEY` | API-ключ Jackett | — |
| `JACKETT_INDEXER` | Имя индексера Jackett | `rutracker` |
| `JACKETT_CATEGORIES` | ID категорий Torznab через запятую | все |
| `JACKETT_TIMEOUT` | HTTP-таймаут запросов к Jackett (должен быть больше ожидания FlareSolverr, 55s) | `60s` |
| `RUTRACKER_DOWNLOAD_TIMEOUT` | Таймаут загрузки торрента | `5m` |
| `RUTRACKER_MAX_BOOKS` | Максимум книг из одного торрента | `5` |
| `RUTRACKER_MAX_TORRENT_SIZE` | Максимальный размер торрента в байтах | `52428800` (50 МБ) |
| `RUTRACKER_DOWNLOAD_DIR` | Директория для загрузки торрентов | `/tmp/book-recon-torrents` |
| `TORRENT_DOWNLOAD_RATE_LIMIT` | Общий лимит скорости скачивания торрентов на весь сервер в секунду (книги и аудиокниги вместе; `B`, `KB`, `MB`, `GB` или байты; `0` — без лимита) | `1MB` |
| `APP_API_ENABLED` | API для Android-приложения: регистрация и туннель | `false` |
| `APP_API_LISTEN` | Адрес HTTP-сервера API | `:8081` |
| `APP_TUNNEL_HOSTS` | Хосты, к которым ведёт туннель (с поддоменами), через запятую | `rutracker.org,rutracker.cc,t-ru.org,flibusta.is,flibusta.site,challenges.cloudflare.com` |
| `APP_TUNNEL_PORTS` | Порты туннеля через запятую | `443,80` |
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

# Контроль доступа

Доступ проверяется в следующем порядке: `ALLOWED_USERS` → `ADMIN_USERS` → статус одобрения в БД.

Неизвестные пользователи запускают процесс одобрения: создаётся запрос в статусе ожидания, администраторы получают уведомление с кнопками одобрения/отклонения, пользователь получает уведомление после принятия решения.

Android-приложение, подключённое к серверу, проходит тот же процесс (см.
[Сервер для Android-приложения](android-server.md)).

`ALLOWED_USERS` — статичный список предварительно одобренных пользователей (для обратной совместимости). Без него бот полностью полагается на динамический процесс одобрения. Идентификаторы администраторов определяются лениво из таблицы `users` — администраторы должны хотя бы раз запустить бота командой `/start`.

