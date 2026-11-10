package ru.tinyops.android.bookrecon.report

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import kotlin.math.roundToInt

/**
 * [ReportingSdk] on the Sentry Android SDK: uncaught exceptions, ANRs and native crashes,
 * plus errors passed to [capture]. Automatic init is off in the manifest, so the SDK runs
 * only between [start] and [stop]. The environment is the device model
 * ([deviceEnvironment]); its hardware goes to tags and the `hardware` context.
 */
class SentryReportingSdk(
    context: Context,
    private val dsn: String,
) : ReportingSdk {
    private val context = context.applicationContext

    override fun start() {
        val hardware = hardware()
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.environment = deviceEnvironment(Build.MANUFACTURER, Build.MODEL)
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            hardware.forEach { (key, value) -> options.setTag(key, value) }
        }
        Sentry.configureScope { scope -> scope.setContexts("hardware", hardware) }
    }

    override fun stop() {
        Sentry.close()
    }

    override fun capture(error: Throwable) {
        Sentry.captureException(error)
    }

    /** Model characteristics: searchable tags in Sentry, next to its own device context. */
    private fun hardware(): Map<String, String> = buildMap {
        put("device.manufacturer", Build.MANUFACTURER)
        put("device.brand", Build.BRAND)
        put("device.model", Build.MODEL)
        put("device.code", Build.DEVICE)
        put("device.product", Build.PRODUCT)
        put("device.hardware", Build.HARDWARE)
        put("device.soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim())
        put("device.abi", Build.SUPPORTED_ABIS.joinToString(","))
        put("device.ram_gb", totalRamGb())
        put("android.release", Build.VERSION.RELEASE)
        put("android.sdk", Build.VERSION.SDK_INT.toString())
        put("android.security_patch", Build.VERSION.SECURITY_PATCH)
    }.filterValues { it.isNotBlank() && it != Build.UNKNOWN }

    private fun totalRamGb(): String {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return ""
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return (info.totalMem / BYTES_IN_GB).roundToInt().toString()
    }

    private companion object {
        const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0
    }
}
