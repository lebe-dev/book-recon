import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.sentry)
}

val repoRoot = rootProject.layout.projectDirectory.dir("..")

// One VERSION file at the repository root drives both the bot and the app.
val appVersionName: String = providers.fileContents(repoRoot.file("VERSION")).asText.get().trim()

fun versionCodeOf(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it in 0..99 }) {
        "VERSION must look like MAJOR.MINOR.PATCH with each part in 0..99, got '$version'"
    }
    val (major, minor, patch) = parts.map { it!! }
    return major * 10000 + minor * 100 + patch
}

// Release signing and Sentry come from the environment first, then from the root .env file.
val dotEnv: Properties = Properties().apply {
    val file = repoRoot.file(".env").asFile
    if (file.isFile) file.reader().use { load(it) }
}

fun envValue(name: String): String? =
    (providers.environmentVariable(name).orNull ?: dotEnv.getProperty(name))?.trim()?.removeSurrounding("\"")?.ifBlank { null }

val releaseStoreFile = envValue("ANDROID_KEYSTORE")
val releaseStorePassword = envValue("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = envValue("ANDROID_KEY_ALIAS")
val releaseKeyPassword = envValue("ANDROID_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

// The DSN is baked into the APK; without it the app never sends error reports.
val sentryDsn = envValue("SENTRY_DSN").orEmpty()

// R8 mappings are uploaded to Sentry by release builds when all three are set.
val sentryOrg = envValue("SENTRY_ORG")
val sentryProject = envValue("SENTRY_PROJECT")
val sentryAuthToken = envValue("SENTRY_AUTH_TOKEN")
val sentryUrl = envValue("SENTRY_URL")
val canUploadMappings = listOf(sentryOrg, sentryProject, sentryAuthToken).all { it != null }

android {
    namespace = "ru.tinyops.android.bookrecon"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "ru.tinyops.android.bookrecon"
        minSdk = 34
        targetSdk = 37
        versionName = appVersionName
        versionCode = versionCodeOf(appVersionName)
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            // Shrinking drops code nothing reaches, the fakes used by previews among it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.useJUnitPlatform() }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // ChromeOS is not a target; release ships ARM ABIs only.
        disable += "ChromeOsAbiSupport"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

sentry {
    // Every minified build gets a mapping UUID in its assets, so Sentry can match its
    // stack traces; the upload itself needs the credentials.
    includeProguardMapping = true
    autoUploadProguardMapping = canUploadMappings
    sentryOrg?.let { org = it }
    sentryProject?.let { projectName = it }
    sentryAuthToken?.let { authToken = it }
    sentryUrl?.let { url = it }
    uploadNativeSymbols = false
    includeSourceContext = false
    telemetry = false
    // The SDK is declared below; no bytecode rewriting of OkHttp, Room, files or logcat.
    autoInstallation { enabled = false }
    tracingInstrumentation { enabled = false }
}

val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested && sentryDsn.isEmpty()) {
    logger.warn("WARNING: SENTRY_DSN is not set. The release APK will not send error reports.")
}
if (releaseRequested && !canUploadMappings) {
    logger.warn(
        "WARNING: R8 mappings will not be uploaded to Sentry. Set SENTRY_ORG, SENTRY_PROJECT " +
            "and SENTRY_AUTH_TOKEN (and SENTRY_URL for self-hosted Sentry) in the environment " +
            "or in the root .env file.",
    )
}
if (!hasReleaseSigning && releaseRequested) {
    logger.warn(
        "WARNING: release signing is not configured. Set ANDROID_KEYSTORE, " +
            "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD in the " +
            "environment or in the root .env file. Building an unsigned release APK.",
    )
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.jsoup)

    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)

    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.libtorrent4j.android.x64)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    // Host native libraries so torrent engine tests run real libtorrent on the JVM.
    testImplementation(libs.libtorrent4j.macos)
    testImplementation(libs.libtorrent4j.linux)
    testRuntimeOnly(libs.junit.platform.launcher)
}
