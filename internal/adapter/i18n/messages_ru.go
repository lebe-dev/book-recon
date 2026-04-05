package i18n

import "fmt"

func loadRU() *Messages {
	return &Messages{
		// --- Access Control ---
		AccessPending:           "⏳ Ваш запрос на доступ ожидает рассмотрения.",
		AccessRequestSent:       "📨 Запрос на доступ отправлен администраторам. Ожидайте.",
		AccessApproved:          "Ваш запрос на доступ одобрен. Напишите название книги для поиска.",
		AccessDenied:            "Ваш запрос на доступ отклонён.",
		AccessRestored:          "Ваш доступ восстановлен. Напишите название книги для поиска.",
		AccessNoPermission:      "Нет прав",
		AccessError:             "Ошибка",
		AccessUpdateError:       "Ошибка при обновлении",
		AccessUnblockError:      "Ошибка при разблокировке",
		AccessRevokeError:       "Ошибка при удалении",
		AccessListError:         "Ошибка при получении списка.",
		AccessNoDenied:          "Заблокированных пользователей нет.",
		AccessNoApproved:        "Одобренных пользователей нет.",
		AccessFallbackName:      "пользователь",
		AccessBtnApprove:        "✅ Одобрить",
		AccessBtnDeny:           "❌ Отклонить",
		AccessBtnUnblock:        "Разблокировать",
		AccessBtnRevoke:         "Удалить доступ",
		AccessUnblocked:         "\n\n✅ Разблокирован",
		AccessRequestNamePrefix: "Имя: ",

		// --- Search ---
		SearchEmpty:        "Введите название книги или имя автора для поиска.",
		SearchCacheMiss:    "Книга не найдена в кэше. Повторите поиск.",
		SearchChooseFormat: "\nВыберите формат:",
		SearchSelectBook:   ". Выберите книгу для скачивания:",

		// --- Download ---
		DownloadSendError:   "Ошибка при отправке файла.",
		DownloadTorrentWait: "⏳ Скачиваю торрент, это может занять несколько минут...",
		DownloadRTChooseFmt: "\nВыберите формат (скачивание из торрента может занять несколько минут):",
		DownloadRTNotFound:  "⚠️ Провайдер RuTracker не найден.",
		DownloadRTError:     "⚠️ Ошибка провайдера RuTracker.",
		DownloadTorrentErr:  "⚠️ Ошибка при скачивании торрента.",
		DownloadSourceLabel: "\n\nИсточник: ",

		// --- Health ---
		HealthNoProviders: "Нет провайдеров с поддержкой health check.",
		HealthTitle:       "🏥 *Состояние сервисов*\n\n",

		// --- Broadcast ---
		BroadcastUsage:       "Использование:\n/whats\\_new _текст сообщения_",
		BroadcastUserListErr: "Ошибка при получении списка пользователей.",

		// --- Navigation ---
		NavBack: "← Назад",
		NavNext: "Далее →",

		// --- Error Messages ---
		ErrNotFound:        "📭 Книги не найдены. Попробуйте другой запрос или проверьте написание.",
		ErrFormatNA:        "📄 Этот формат недоступен для книги. Измените формат в /settings.",
		ErrFileTooLarge:    "📦 Файл слишком большой (>50 МБ) — Telegram не принимает такие файлы.",
		ErrTimeout:         "⏱ Источник не отвечает. Попробуйте через несколько минут.",
		ErrProvider:        "⚠️ Ошибка при обращении к источнику. Попробуйте позже.",
		ErrBookUnavailable: "🚫 Книга недоступна для скачивания (удалена по жалобе правообладателя).",
		ErrNoSeeders:       "🌱 Нет раздающих — скачивание невозможно. Попробуйте другую раздачу.",
		ErrTorrentTooLarge: "📦 Торрент слишком большой. Максимум: 2 ГБ.",
		ErrServiceDown:     "⚠️ Сервис поиска недоступен. Попробуйте позже.",
		ErrUnexpected:      "⚠️ Непредвиденная ошибка. Попробуйте позже.",

		// --- RuTracker errors ---
		ErrRTNoSeeders:       "🌱 Нет раздающих — скачивание невозможно. Попробуйте другую раздачу.",
		ErrRTTorrentTooLarge: "📦 Торрент слишком большой. Максимум: 2 ГБ.",
		ErrRTTimeout:         "⏱ Торрент не скачался вовремя. Попробуйте раздачу с большим количеством сидов.",
		ErrRTFormatNA:        "📄 В торренте не найдены файлы нужного формата. Попробуйте другой формат.",
		ErrRTServiceDown:     "⚠️ Сервис поиска недоступен. Попробуйте позже.",
		ErrRTDownload:        "⚠️ Ошибка при скачивании торрента. Попробуйте позже.",

		// --- Parameterized messages ---
		StartAdmin: func(version string) string {
			return fmt.Sprintf("📚 *Book Recon* `%s`", version)
		},
		StartUser: func(name string) string {
			return fmt.Sprintf("📚 *Book Recon*\n\nПривет, %s! Напишите название книги или имя автора — я найду и скачаю книгу.\n\nКоманды:\n/settings — настройки формата\n/help — справка", name)
		},
		HelpText: func(isAdmin bool) string {
			text := "📖 *Справка*\n\n" +
				"Напишите название книги или имя автора — бот найдёт и предложит скачать.\n\n" +
				"*Советы:*\n" +
				"• Поиск идёт одновременно по нескольким источникам\n" +
				"• Результаты выводятся по 5, листайте кнопками\n" +
				"• На кнопках видно, какие форматы доступны\n\n" +
				"Форматы: EPUB, FB2\n\n" +
				"/settings — выбрать предпочитаемый формат"
			if isAdmin {
				text += "\n\n*Администрирование:*\n" +
					"/allowed\\_users — одобренные пользователи\n" +
					"/blocked\\_users — заблокированные пользователи\n" +
					"/whats\\_new — рассылка «Что нового» всем пользователям\n" +
					"/health — состояние сервисов"
			}
			return text
		},
		FoundBooks: func(n int) string {
			switch {
			case n == 1:
				return "Найдена 1 книга"
			case n >= 2 && n <= 4:
				return fmt.Sprintf("Найдено %d книги", n)
			default:
				return fmt.Sprintf("Найдено %d книг", n)
			}
		},
		FormatFileSize: func(bytes int64) string {
			const (
				kb = 1024
				mb = 1024 * kb
			)
			switch {
			case bytes >= mb:
				return fmt.Sprintf("%.1f МБ", float64(bytes)/float64(mb))
			case bytes >= kb:
				return fmt.Sprintf("%.0f КБ", float64(bytes)/float64(kb))
			default:
				return fmt.Sprintf("%d Б", bytes)
			}
		},
		FormatTorrentSize: func(bytes int64) string {
			const (
				kb = 1024
				mb = 1024 * kb
				gb = 1024 * mb
			)
			switch {
			case bytes >= gb:
				return fmt.Sprintf("%.1f ГБ", float64(bytes)/float64(gb))
			case bytes >= mb:
				return fmt.Sprintf("%.1f МБ", float64(bytes)/float64(mb))
			case bytes >= kb:
				return fmt.Sprintf("%.0f КБ", float64(bytes)/float64(kb))
			default:
				return fmt.Sprintf("%d Б", bytes)
			}
		},
		SeedsLabel: func(seeds string) string {
			return fmt.Sprintf("🌱 %s сида", seeds)
		},
		TorrentPicked: func(count int, format string) string {
			return fmt.Sprintf("📚 Из торрента выбрано %d файла (%s):", count, format)
		},
		FileSendError: func(name string) string {
			return fmt.Sprintf("⚠️ Не удалось отправить файл %s.", name)
		},
		AccessApprovedFor: func(name string) string {
			return fmt.Sprintf("✅ Доступ одобрен для %s", name)
		},
		AccessDeniedFor: func(name string) string {
			return fmt.Sprintf("❌ Доступ отклонён для %s", name)
		},
		AccessRevokedFor: func(name string) string {
			return "🚫 Отозван доступ для пользователя " + name
		},
		AccessRequestNotify: func(id int64, name, username string) string {
			text := fmt.Sprintf("🔔 *Запрос на доступ*\n\nID: `%d`\nИмя: %s", id, name)
			if username != "" {
				text += fmt.Sprintf("\nUsername: @%s", username)
			}
			return text
		},
		ProviderError: func(provider, errMsg string) string {
			return fmt.Sprintf("⚠️ *Ошибка провайдера %s*\n\n`%s`", provider, errMsg)
		},
		BroadcastTitle: func(text string) string {
			return fmt.Sprintf("📢 *Что нового*\n\n%s", text)
		},
		BroadcastComplete: func(sent, failed int) string {
			return fmt.Sprintf("Рассылка завершена: отправлено %d, ошибок %d.", sent, failed)
		},
		SettingsText: func(format string) string {
			return fmt.Sprintf("⚙️ Настройки\n\nПредпочитаемый формат: *%s*", format)
		},
	}
}
