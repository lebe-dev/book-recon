package ru.tinyops.android.bookrecon.report

import java.util.Locale

/** Sentry rejects longer environment names. */
internal const val MAX_ENVIRONMENT_LENGTH = 64

private val NOT_SLUG = Regex("[^a-z0-9]+")

/**
 * Sentry environment for a device: manufacturer and model as a lowercase slug, such as
 * `google-pixel-11-pro`. The manufacturer is not repeated when the model already starts with
 * it ("Xiaomi 14T Pro"). Names with no Latin letters or digits give `unknown-device`.
 */
fun deviceEnvironment(manufacturer: String, model: String): String {
    val vendor = manufacturer.trim()
    val device = model.trim()
    val full = if (device.startsWith(vendor, ignoreCase = true)) device else "$vendor $device"
    return full.lowercase(Locale.ROOT)
        .replace(NOT_SLUG, "-")
        .trim('-')
        .take(MAX_ENVIRONMENT_LENGTH)
        .trimEnd('-')
        .ifEmpty { "unknown-device" }
}
