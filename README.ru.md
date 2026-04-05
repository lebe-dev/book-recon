# book recon

Book Recon — сервис для поиска и скачивания книг через Telegram Bot.

[EN](README.md)

## Возможности

- Интерфейс: Telegram-бот
- Поддерживаемые источники:
  - [Flibusta](http://flibusta.site)
  - RuTracker (через Jackett)
  - [RoyalLib](https://royallib.com)
- Поддержка языков: en, ru

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

## Конфигурация

Переменные окружения загружаются из `.env` (см. `.env.example`):

| Переменная | Описание | По умолчанию |
|---|---|---|
| `TELEGRAM_TOKEN` | Токен бота (обязательно) | — |
| `ALLOWED_USERS` | Список разрешённых пользователей через запятую (без `@`) | — |
| `ADMIN_USERS` | Список администраторов через запятую | — |
| `DB_PATH` | Путь к файлу SQLite | `book-recon.db` |
| `LOG_LEVEL` | Уровень логирования | `info` |
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

## Контроль доступа

Доступ проверяется в следующем порядке: `ALLOWED_USERS` → `ADMIN_USERS` → статус одобрения в БД.

Неизвестные пользователи запускают процесс одобрения: создаётся запрос в статусе ожидания, администраторы получают уведомление с кнопками одобрения/отклонения, пользователь получает уведомление после принятия решения.

`ALLOWED_USERS` — статичный список предварительно одобренных пользователей (для обратной совместимости). Без него бот полностью полагается на динамический процесс одобрения. Идентификаторы администраторов определяются лениво из таблицы `users` — администраторы должны хотя бы раз запустить бота командой `/start`.

## Roadmap

- Поддержка аудио-книг
