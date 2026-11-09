package ru.tinyops.android.bookrecon.ui.nav

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VerificationResultTest {
    @Test
    fun `result is delivered once and then cleared`() = runTest {
        val handle = SavedStateHandle()

        handle.verificationResults().test {
            handle[VERIFICATION_RESULT_KEY] = true
            assertEquals(true, awaitItem())
            assertNull(handle.get<Boolean>(VERIFICATION_RESULT_KEY))

            handle[VERIFICATION_RESULT_KEY] = false
            assertEquals(false, awaitItem())
            expectNoEvents()
        }
    }
}
