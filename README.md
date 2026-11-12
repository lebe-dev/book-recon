<p align="center">
  <img src="logo.jpg" alt="Book Recon" width="200">
</p>

# Book Recon

Book Recon ищет и скачивает книги из онлайн-библиотек. Работает как Telegram-бот и как
самостоятельное Android-приложение.

## Возможности

- Telegram-бот и [Android-приложение](docs/android-app.md) без собственного сервера
- Источники: [Flibusta](http://flibusta.site), RuTracker (через Jackett), [RoyalLib](https://royallib.com)
- Электронные книги в нужном формате, отправка файла прямо в Telegram
- [Аудиокниги](docs/audiobooks.md) с RuTracker: выбор файлов из раздачи, прогресс в Telegram,
  скачивание через веб
- Приложение открывает заблокированные сайты
  [через сервер Book Recon](docs/android-server.md)
- Приложение само проверяет и ставит обновления с GitHub Releases
- Контроль доступа: список разрешённых пользователей и одобрение запросов администраторами
- Интерфейс бота на русском и английском (`LOCALE`)
- Необязательные отчёты об ошибках в Sentry

## Быстрый старт

```bash
cp .env.example .env

# отредактировать .env под свои нужды

docker compose up -d
```

## Документация

- [Конфигурация и контроль доступа](docs/configuration.md)
- [Настройка RuTracker (Jackett)](docs/rutracker.md)
- [Аудиокниги](docs/audiobooks.md)
- [Android-приложение](docs/android-app.md)
- [Сервер для Android-приложения](docs/android-server.md)
- [Для разработчиков](DEV.md)
