# RuTracker fixtures

Pages saved from rutracker.org on 2026-11-09, kept byte for byte in windows-1251 as served.

| File | Source |
|------|--------|
| `search-audio.html` | `tracker.php` with the audiobook forums, `nm=Лукьяненко Дозор`, sorted by seeders; 26 rows, one without seeders |
| `search-books.html` | `tracker.php` with the e-book forums, `nm=Лукьяненко`, sorted by seeders; first page of 57 |
| `search-empty.html` | `tracker.php` with a query that matches nothing |
| `login-form.html` | `login.php` without a session |
| `login-required.html` | `tracker.php` without a session, after the redirect to `login.php?redirect=…` |
| `dl-not-found.html` | `dl.php?t=1`, a topic without a torrent |
| `login-failed.html` | **Synthetic**: `login-form.html` with the "неверный пароль" heading |
| `login-captcha.html` | **Synthetic**: `login-failed.html` with the captcha fields `cap_sid` and `cap_code_<hash>` |

The logged-in pages were cleaned before committing: the username is replaced with `reader`,
the user ID with `1000000` and `form_token` with zeros. No cookies, passkeys or real
`.torrent` files are stored; tests generate their own torrent bytes.

The two synthetic pages follow the markup the site used for these cases at the time of
writing. Replace them with real pages when a failed login and a captcha can be captured.
