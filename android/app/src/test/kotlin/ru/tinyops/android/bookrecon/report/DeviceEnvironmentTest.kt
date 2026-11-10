package ru.tinyops.android.bookrecon.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DeviceEnvironmentTest {
    @ParameterizedTest
    @CsvSource(
        "Google, Pixel 11 Pro, google-pixel-11-pro",
        "samsung, SM-S928B, samsung-sm-s928b",
        "Xiaomi, Xiaomi 14T Pro, xiaomi-14t-pro",
        "OnePlus, ONEPLUS A6013, oneplus-a6013",
        "' HUAWEI ', ' P30_Pro (2019) ', huawei-p30-pro-2019",
        "Google, sdk_gphone64_x86_64, google-sdk-gphone64-x86-64",
        "'', Pixel 9, pixel-9",
        "Google, '', google",
        "'', '', unknown-device",
        "Яндекс, Телефон, unknown-device",
    )
    fun slugOfManufacturerAndModel(manufacturer: String, model: String, expected: String) {
        assertEquals(expected, deviceEnvironment(manufacturer, model))
    }

    @org.junit.jupiter.api.Test
    fun longNamesAreCutWithoutTrailingDash() {
        val name = deviceEnvironment("Vendor", "a".repeat(57) + " bbbbbbbb")

        assertTrue(name.length <= MAX_ENVIRONMENT_LENGTH)
        assertEquals("vendor-" + "a".repeat(57), name)
    }
}
