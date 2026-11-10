# book recon

Book Recon — сервис для поиска и скачивания книг через Telegram Bot.

[EN](README.md)

## Возможности

- Интерфейс: Telegram-бот и самостоятельное [Android-приложение](#android-приложение)
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

rutracker.org закрыт Cloudflare-челленджем, поэтому рядом с Jackett в `docker-compose.yml` запускается [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) (headless Chromium). Jackett находит его по `FlareSolverrUrl` в `jackett/ServerConfig.json` (`http://flaresolverr:8191`). Наружу порт не открыт. FlareSolverr занимает ~160 МБ в простое и до ~700 МБ во время решения челленджа (лимит 768M / 1.5 CPU).

### 1. Установить API-ключ

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

### 2. Запустить Jackett

```bash
docker compose up -d flaresolverr jackett
```

Если Jackett уже был настроен раньше без FlareSolverr, пропишите `"FlareSolverrUrl": "http://flaresolverr:8191"` в его `ServerConfig.json` при остановленном контейнере (Jackett перезаписывает файл при остановке) или в веб-интерфейсе: **Jackett Configuration → FlareSolverr API URL**.

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
| `JACKETT_TIMEOUT` | HTTP-таймаут запросов к Jackett (должен быть больше ожидания FlareSolverr, 55s) | `60s` |
| `RUTRACKER_DOWNLOAD_TIMEOUT` | Таймаут загрузки торрента | `5m` |
| `RUTRACKER_MAX_BOOKS` | Максимум книг из одного торрента | `5` |
| `RUTRACKER_MAX_TORRENT_SIZE` | Максимальный размер торрента в байтах | `52428800` (50 МБ) |
| `RUTRACKER_DOWNLOAD_DIR` | Директория для загрузки торрентов | `/tmp/book-recon-torrents` |
| `APP_API_ENABLED` | API для Android-приложения: регистрация и туннель | `false` |
| `APP_API_LISTEN` | Адрес HTTP-сервера API | `:8081` |
| `APP_TUNNEL_HOSTS` | Хосты, к которым ведёт туннель (с поддоменами), через запятую | `rutracker.org,rutracker.cc,t-ru.org,flibusta.is,challenges.cloudflare.com` |
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

## Контроль доступа

Доступ проверяется в следующем порядке: `ALLOWED_USERS` → `ADMIN_USERS` → статус одобрения в БД.

Неизвестные пользователи запускают процесс одобрения: создаётся запрос в статусе ожидания, администраторы получают уведомление с кнопками одобрения/отклонения, пользователь получает уведомление после принятия решения.

Android-приложение проходит тот же процесс (см. [Через сервер Book Recon](#через-сервер-book-recon)).

`ALLOWED_USERS` — статичный список предварительно одобренных пользователей (для обратной совместимости). Без него бот полностью полагается на динамический процесс одобрения. Идентификаторы администраторов определяются лениво из таблицы `users` — администраторы должны хотя бы раз запустить бота командой `/start`.

## Android-приложение

Android-приложение делает то же, что бот, но прямо на телефоне и без сервера: ищет и
скачивает книги с Flibusta, электронные книги и аудиокниги с RuTracker. Jackett и
FlareSolverr ему не нужны. Интерфейс только на русском.

### Установка

1. Скачайте `book-recon-<версия>.apk` со страницы
   [релизов на GitHub](https://github.com/lebe-dev/book-recon/releases). Нужен Android 14
   или новее.
2. Откройте файл на телефоне и разрешите установку приложений из этого источника, когда
   Android спросит.

Flibusta и RuTracker в России заблокированы: включите VPN на телефоне или подключите
приложение к серверу Book Recon (см. [Через сервер Book Recon](#через-сервер-book-recon)).

### Первый запуск

Пока хранилище не настроено, приложение при каждом запуске открывает «Настройки»:

1. «Доступ ко всем файлам»: нажмите кнопку, включите разрешение в системных настройках и
   вернитесь назад. Книги и аудиокниги сохраняются в обычные папки, где их видят читалки и
   плееры, и для этого нужно это разрешение.
2. «Папка книг» и «Папка аудиокниг»: выберите папки для книг и аудиокниг во внутренней
   памяти или на карте памяти. Облачные папки и системные «Загрузки» не поддерживаются.
3. «RuTracker»: введите логин и пароль и нажмите «Проверить вход». Пароль шифруется ключом
   из Android Keystore. Если RuTracker просит капчу или Cloudflare — проверку, приложение
   откроет страницу во встроенном браузере: пройдите её там.

Разрешение на уведомления Android запросит при первой загрузке: загрузки идут в фоне и
показывают прогресс в уведомлении.

Пока загрузка идёт, её файлы лежат в скрытой папке `.book-recon` внутри выбранной папки и
переносятся на место после завершения.

### Через сервер Book Recon

Если сеть телефона блокирует RuTracker и Flibusta, приложение может открывать их через
сервер Book Recon. По умолчанию это выключено:

1. **Настройки → Через сервер Book Recon**: включите переключатель и введите адрес сервера,
   например `https://books.example.com`.
2. Приложение откроет бота в Telegram: нажмите **Старт**. Так телефон привязывается к вашему
   Telegram-аккаунту.
3. Пользователь, которого бот уже знает (`ALLOWED_USERS`, `ADMIN_USERS` или одобренный
   раньше), получает доступ сразу. Иначе администраторам приходит запрос с пометкой «из
   Android-приложения» и кнопками одобрения/отклонения, а приложение показывает спиннер,
   пока они не решат.

После одобрения в настройках написано, что RuTracker и Flibusta открываются через сервер
Book Recon. Доступ общий с ботом: отклонённый пользователь больше не шлёт запросов
администраторам, в `/blocked_users` его можно одобрить позже, а отзыв доступа в
`/allowed_users` выключает и туннель приложения. В обоих списках видно, сколько телефонов
привязал пользователь. Выключение переключателя удаляет телефон с сервера.

Через туннель идут только HTTPS-запросы к хостам из списка сервера — и из приложения, и из
встроенного браузера (Cloudflare привязывает проверку к IP-адресу). Пиры и трекеры
торрентов по-прежнему идут напрямую.

На сервере:

```
APP_API_ENABLED=true
```

API слушает `APP_API_LISTEN` (`:8081`), docker-compose публикует его на `127.0.0.1:8081`.
Откройте `/api/v1/` через nginx с поддержкой WebSocket:

```nginx
server {
    listen 443 ssl;
    server_name books.example.com;
    # ssl_certificate ...;

    location /api/v1/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        # The tunnel is a WebSocket
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_buffering off;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

| Endpoint | Назначение |
|---|---|
| `GET /api/v1/ping` | Сообщает приложению, что это сервер Book Recon |
| `POST /api/v1/clients` | Регистрирует телефон, возвращает его bearer-токен |
| `GET /api/v1/clients/me` | Статус доступа и список хостов туннеля |
| `POST /api/v1/clients/me/link` | Одноразовая ссылка `t.me/<бот>?start=link_…`, действует 15 минут |
| `DELETE /api/v1/clients/me` | Удаляет телефон |
| `GET /api/v1/tunnel?host=&port=` | WebSocket-туннель к разрешённому хосту |

Все вызовы, кроме первых двух, требуют `Authorization: Bearer <токен>`. Сервер хранит только
SHA-256 токена. Туннель ведёт только к хостам из `APP_TUNNEL_HOSTS` (и их поддоменам) на
портах `APP_TUNNEL_PORTS` и только на публичные IP-адреса. Регистраций — не больше 10 в
минуту; телефоны, так и не привязавшие Telegram, удаляются через сутки.

### Отчёты об ошибках

По умолчанию отчёты выключены. Включаются в **Настройки → Отчёты об ошибках**: сбои (в том
числе нативные и ANR) и непредвиденные ошибки загрузок уходят в Sentry вместе с моделью
телефона и версией Android. Окружение (environment) в Sentry — модель устройства, например
`google-pixel-11-pro`. Логин и пароль RuTracker не отправляются.

### Обновление

Установите новый APK поверх старого: настройки, история и скачанные файлы сохранятся.
Android принимает обновление, только если оно подписано тем же ключом. Debug-сборка
подписана другим ключом, поэтому перед установкой релизной сборки её нужно удалить.

### Сборка

Нужны JDK 17+ и Android SDK, на который указывает `ANDROID_HOME`.

```bash
just android-build    # debug APK в android/app/build/outputs/apk/debug
just android-test     # unit-тесты
just android-lint     # Android Lint
just android-install  # установить debug APK через adb
just android-release  # release APK в bin/book-recon-<VERSION>.apk
```

Версия приложения берётся из корневого файла `VERSION`, общего с ботом. Release APK
подписывается keystore, заданным в `.env` (или в окружении):

```
ANDROID_KEYSTORE=/path/to/release.jks
ANDROID_KEYSTORE_PASSWORD=...
ANDROID_KEY_ALIAS=...
ANDROID_KEY_PASSWORD=...
```

Без этих переменных `just android-release` собирает неподписанный APK, который нельзя
установить. Храните keystore вне репозитория и сделайте резервную копию: без него
установленное приложение не обновить, только удалить и поставить заново.

Для отчётов об ошибках и маппингов R8 нужны переменные из `.env` или окружения:

```
SENTRY_DSN=https://...@sentry.example.com/1   # вшивается в APK; пустой — отчётов нет
SENTRY_ORG=...                                # загрузка маппингов: нужны все три
SENTRY_PROJECT=...
SENTRY_AUTH_TOKEN=...
SENTRY_URL=https://sentry.example.com         # только для своего Sentry
```

`SENTRY_DSN` — та же переменная, что читает бот. Релизная сборка загружает маппинг R8 в
Sentry, и стектрейсы приложения показывают исходные строки кода; без трёх переменных
загрузки сборка пишет предупреждение и пропускает её.

`just release` собирает подписанный APK, затем собирает и публикует Docker-образ. Если
подпись не настроена, рецепт падает до публикации образа. APK
`bin/book-recon-<VERSION>.apk` прикладывается к релизу на GitHub.
