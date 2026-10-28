package i18n

import (
	"fmt"
	"time"
)

func audioRU() AudioMessages {
	return AudioMessages{
		Help:             "/audio _запрос_ — поиск аудиокниг на RuTracker",
		AdminHelp:        "/audiobooks — включить/выключить аудиокниги",
		Usage:            "🎧 Использование: <code>/audio название или автор</code>",
		DisabledEnv:      "🎧 Аудиокниги недоступны на этом сервере.",
		Disabled:         "🎧 Аудиокниги временно отключены администратором.",
		NotFound:         "📭 Аудиокниги не найдены. Попробуйте другой запрос.",
		SessionExpired:   "⌛ Сессия устарела. Повторите поиск через /audio.",
		NoAudioFiles:     "🔇 В раздаче нет аудиофайлов. Выберите другую.",
		LoadingTorrent:   "⏳ Загружаю список файлов раздачи...",
		SelectionEmpty:   "Не выбрано ни одного файла",
		SelectionClosed:  "Выбор файлов отменён.",
		FileTooLargeMark: "🚫",
		RootFolder:       "(корень раздачи)",
		NoDownloads:      "У вас нет активных загрузок и готовых ссылок.",
		ActiveHeader:     "<b>Загрузки:</b>",
		ReadyHeader:      "<b>Готово:</b>",
		AdminEnvOff:      "🎧 Аудиокниги выключены переменной окружения <code>AUDIOBOOKS_ENABLED=false</code>. Из бота их не включить.",
		ToggleError:      "Не удалось сохранить настройку",

		BtnAll:            "✅ Все",
		BtnNone:           "⬜ Сбросить",
		BtnFolders:        "📁 Папки",
		BtnFiles:          "📄 Файлы",
		BtnCancel:         "✖ Отмена",
		BtnCancelDownload: "✖ Отменить скачивание",
		BtnEnable:         "✅ Включить",
		BtnDisable:        "⛔ Выключить",

		ErrServiceDown: "⚠️ Сервис поиска недоступен. Попробуйте позже.",
		ErrStalled:     "раздача перестала качаться (нет данных от пиров)",
		ErrQuota:       "💾 На сервере закончилось место под аудиокниги. Попробуйте позже, когда истекут старые ссылки.",
		ErrUserLimit:   "⏳ У вас уже есть активная загрузка. Дождитесь её окончания или отмените.",
		ErrAlready:     "⏳ Эта раздача уже скачивается. Попробуйте чуть позже.",
		ErrTimeout:     "не успела скачаться за отведённое время",
		ErrNoSeeders:   "нет раздающих",
		ErrInterrupted: "бот был перезапущен во время скачивания, запустите заново",
		ErrDownload:    "ошибка при скачивании",
		ErrSelectionBig: func(limit string) string {
			return fmt.Sprintf("Выбрано больше лимита (%s)", limit)
		},

		WebFiles:       "Файлов",
		WebDownloadAll: "Скачать всё (zip)",
		WebDownload:    "скачать",
		WebExpires:     "доступно до",

		Found: func(query string, n int) string {
			return fmt.Sprintf("🎧 <b>%s</b>\n\nНайдено раздач: %d. Выберите:", query, n)
		},
		ResultLine: func(num int, title, size, seeds string) string {
			return fmt.Sprintf("<b>%d.</b> %s\n      📦 %s · 🌱 %s", num, title, size, seeds)
		},
		Selection: func(title string, files int, total string) string {
			return fmt.Sprintf("🎧 <b>%s</b>\nАудиофайлов: %d · %s", title, files, total)
		},
		SelectionSum: func(count int, size, limit string) string {
			s := fmt.Sprintf("Выбрано: <b>%d</b> · <b>%s</b>", count, size)
			if limit != "" {
				s += fmt.Sprintf(" (лимит %s)", limit)
			}
			return s
		},
		FolderLine: func(num int, mark, name string, selected, files int, size string) string {
			return fmt.Sprintf("%d. %s 📁 %s — %d/%d · %s", num, mark, name, selected, files, size)
		},
		DownloadBtn: func(count int) string {
			return fmt.Sprintf("⬇️ Скачать (%d)", count)
		},
		Queued: func(title string, pos, files int, size string) string {
			return fmt.Sprintf("⏳ <b>%s</b>\nВ очереди: %d-я. Файлов: %d · %s\nСообщу, когда начнётся скачивание.", title, pos, files, size)
		},
		Progress: func(title string, percent int, done, total, speed string, peers int, eta string) string {
			s := fmt.Sprintf("⬇️ <b>%s</b>\n%s %d%%\n%s из %s · %s/с · пиров: %d", title, progressBar(percent), percent, done, total, speed, peers)
			if eta != "" {
				s += "\nОсталось ≈ " + eta
			}
			return s
		},
		Started: func(title string) string {
			return fmt.Sprintf("▶️ Началось скачивание: <b>%s</b>", title)
		},
		Ready: func(title, link, ttl string, files int, size string) string {
			return fmt.Sprintf("✅ Аудиокнига готова: <b>%s</b>\nФайлов: %d · %s\n\n🔗 %s\n\nСсылка действует %s.", title, files, size, link, ttl)
		},
		ReadyStatus: func(title string) string {
			return fmt.Sprintf("✅ <b>%s</b> — скачано.", title)
		},
		FailedStatus: func(title string) string {
			return fmt.Sprintf("❌ <b>%s</b> — не скачано.", title)
		},
		Failed: func(title, reason string) string {
			return fmt.Sprintf("❌ Не удалось скачать <b>%s</b>: %s.", title, reason)
		},
		Cancelled: func(title string) string {
			return fmt.Sprintf("✖ Скачивание отменено: <b>%s</b>", title)
		},
		ActiveLine: func(title, state string) string {
			return fmt.Sprintf("• %s — %s", title, state)
		},
		ReadyLine: func(title, link, ttl string) string {
			return fmt.Sprintf("• %s\n  🔗 %s (ещё %s)", title, link, ttl)
		},
		QueuedState: func(pos int) string {
			return fmt.Sprintf("в очереди (%d-я)", pos)
		},
		AdminStatus: func(enabled bool, active, queued, ready int, used, max string) string {
			state := "✅ включены"
			if !enabled {
				state = "⛔ выключены"
			}
			return fmt.Sprintf("🎧 <b>Аудиокниги</b>: %s\n\nСкачивается: %d · в очереди: %d\nГотовых ссылок: %d\nДиск: %s из %s", state, active, queued, ready, used, max)
		},
		Duration: func(d time.Duration) string {
			return formatDuration(d, "ч", "мин", "сек")
		},
	}
}
