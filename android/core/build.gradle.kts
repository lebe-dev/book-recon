import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// HTML fixtures shared with the Go bot live in the repository root.
val testdataDir = rootProject.layout.projectDirectory.dir("../testdata")

tasks.test {
    useJUnitPlatform()
    systemProperty("testdata.dir", testdataDir.asFile.absolutePath)
    inputs.dir(testdataDir).withPathSensitivity(PathSensitivity.RELATIVE).optional()
}

kover {
    currentProject {
        createVariant("unit") { add("jvm") }
    }
}
