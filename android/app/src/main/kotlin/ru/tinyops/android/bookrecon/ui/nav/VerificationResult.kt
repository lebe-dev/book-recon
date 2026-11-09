package ru.tinyops.android.bookrecon.ui.nav

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach

internal const val VERIFICATION_RESULT_KEY = "web_verification_result"

/**
 * Results of the web verification screen delivered back to the screen that opened it:
 * true when the check passed, false when the user gave up. Each result is emitted once.
 */
internal fun SavedStateHandle.verificationResults(): Flow<Boolean> =
    getStateFlow<Boolean?>(VERIFICATION_RESULT_KEY, null)
        .filterNotNull()
        .onEach { set(VERIFICATION_RESULT_KEY, null) }
