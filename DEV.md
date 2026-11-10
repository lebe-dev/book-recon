# Для разработчиков

Минимум, чтобы собрать, проверить и выпустить проект. Подробная архитектура и ограничения
описаны в [AGENTS.md](AGENTS.md).

## Требования

- Go (версия из `go.mod`), [just](https://github.com/casey/just), `golangci-lint`
- Для Android: JDK 17+ и Android SDK, переменная `ANDROID_HOME`
- Docker — для образа и запуска вместе с Jackett и FlareSolverr

## Бот (Go)

```bash
cp .env.example .env   # задать TELEGRAM_TOKEN и остальное, см. docs/configuration.md
just run               # собрать и запустить
just test              # тесты (`just test -run TestName` — один тест)
just lint              # golangci-lint
just coverage          # отчёт о покрытии
just build-image       # Docker-образ
```

Структура (чистая архитектура):

- `internal/domain/` — типы и интерфейсы без зависимостей
- `internal/usecase/` — бизнес-логика
- `internal/adapter/` — Telegram, SQLite, провайдеры книг, конфиг, API приложения
- `cmd/book-recon/main.go` — сборка всего вместе

Новый источник книг: реализовать `domain.BookProvider` и добавить в срез `providers`
в `cmd/book-recon/main.go`.

## Android

Приложение лежит в `android/`: модуль `core` — чистый Kotlin, модуль `app` — само приложение.
Go-код служит эталоном поведения, приложение его не вызывает.

```bash
just android-build    # debug APK в android/app/build/outputs/apk/debug
just android-test     # тесты :core и unit-тесты :app
just android-lint     # Android Lint
just android-install  # установить debug APK через adb
just deploy-android   # debug APK на телефон по USB и запуск
just android-release  # release APK в bin/book-recon-<VERSION>.apk
```

Версия приложения берётся из корневого файла `VERSION`, общего с ботом. Android не ставит
APK с меньшим `versionCode`, поэтому `VERSION` повышается при каждом релизе.

### Подпись и Sentry

Переменные читаются из окружения или корневого `.env`:

```
ANDROID_KEYSTORE=/path/to/release.jks
ANDROID_KEYSTORE_PASSWORD=...
ANDROID_KEY_ALIAS=...
ANDROID_KEY_PASSWORD=...

SENTRY_DSN=https://...@sentry.example.com/1   # вшивается в APK; пустой — отчётов нет
SENTRY_ORG=...                                # загрузка маппингов R8: нужны все три
SENTRY_PROJECT=...
SENTRY_AUTH_TOKEN=...
SENTRY_URL=https://sentry.example.com         # только для своего Sentry
```

Без переменных подписи `just android-release` собирает неподписанный APK, который нельзя
установить. Храните keystore вне репозитория и сделайте резервную копию: без него
установленное приложение не обновить.

## Релиз

1. Поднять версию в `VERSION`, смержить изменения в `main`, поставить тег `vX.Y.Z`.
2. `just release` — подписанный APK, затем сборка и публикация Docker-образа
   (падает до публикации, если подпись не настроена).
3. Создать релиз на GitHub (`gh release create`, заголовок `vX.Y.Z`) и приложить
   `bin/book-recon-<VERSION>.apk`.
4. `just deploy` — выкатить версию на сервер.

## Соглашения

- Коммиты: Conventional Commits на английском, только строка заголовка.
- Тесты пишутся раньше кода для нетривиальных изменений.
- Документация проекта — на русском языке; при изменении поведения обновляйте её.
