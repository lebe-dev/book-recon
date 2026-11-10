package ru.tinyops.android.bookrecon.ui.nav

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/** What a RuTracker topic contains. */
@Keep
enum class TopicKind { BOOK, AUDIO }

/** Why the web verification screen was opened. */
@Keep
enum class VerificationPurpose { CLOUDFLARE, RUTRACKER_LOGIN }

@Serializable
data object SearchRoute

@Serializable
data class FlibustaBookRoute(val bookId: String)

@Serializable
data class RuTrackerTopicRoute(val topicId: String, val kind: TopicKind, val title: String)

@Serializable
data object DownloadsRoute

@Serializable
data object LibraryRoute

@Serializable
data object SettingsRoute

@Serializable
data object LogRoute

@Serializable
data class WebVerificationRoute(val url: String, val purpose: VerificationPurpose)
