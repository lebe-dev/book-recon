package ru.tinyops.android.bookrecon.ui.search

import androidx.annotation.StringRes
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.Source
import java.util.Locale

/** Unit of a formatted size; [label] is the Russian abbreviation. */
enum class SizeUnit(@param:StringRes val label: Int) {
    BYTES(R.string.search_size_bytes),
    KILOBYTES(R.string.search_size_kilobytes),
    MEGABYTES(R.string.search_size_megabytes),
    GIGABYTES(R.string.search_size_gigabytes),
}

/** A size split into a number in Russian notation ("1,2") and its unit. */
data class FormattedSize(val number: String, val unit: SizeUnit)

private val russian: Locale = Locale.forLanguageTag("ru")

/** Formats [bytes] in binary units with at most one decimal, dropping a trailing ",0". */
fun formatSize(bytes: Long): FormattedSize {
    var value = bytes.toDouble()
    var unit = SizeUnit.BYTES
    for (next in SizeUnit.entries.drop(1)) {
        if (value < 1024) break
        value /= 1024
        unit = next
    }
    if (unit == SizeUnit.BYTES) return FormattedSize(bytes.toString(), unit)
    val number = String.format(russian, "%.1f", value).removeSuffix(",0")
    return FormattedSize(number, unit)
}

/** Name of a source as shown to the user. */
@StringRes
fun Source.label(): Int = when (this) {
    Source.FLIBUSTA -> R.string.search_source_flibusta
    Source.RUTRACKER -> R.string.search_source_rutracker
}

/** True when the user can fix the error in settings: RuTracker login or password. */
fun AppError.leadsToSettings(): Boolean =
    code == ErrorCode.RUTRACKER_AUTH_REQUIRED || code == ErrorCode.RUTRACKER_LOGIN_FAILED

/** Short Russian text for a source error, shown after the source name. */
@StringRes
fun AppError.searchMessage(): Int = when (code) {
    ErrorCode.NETWORK -> R.string.search_error_network
    ErrorCode.SERVICE_DOWN -> R.string.search_error_service_down
    ErrorCode.TIMEOUT -> R.string.search_error_timeout
    ErrorCode.NOT_FOUND -> R.string.search_error_not_found
    ErrorCode.PARSE_ERROR -> R.string.search_error_parse
    ErrorCode.CLOUDFLARE_CHALLENGE -> R.string.search_error_cloudflare
    ErrorCode.RUTRACKER_AUTH_REQUIRED -> R.string.search_error_auth_required
    ErrorCode.RUTRACKER_LOGIN_FAILED -> R.string.search_error_login_failed
    ErrorCode.RUTRACKER_CAPTCHA -> R.string.search_error_captcha
    ErrorCode.VALIDATION -> R.string.search_error_validation
    else -> R.string.search_error_unknown
}
