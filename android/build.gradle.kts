plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.sentry) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// Static analysis that stands in for SonarQube: detekt (complexity, code smells, Compose
// rules), PMD CPD (copy-paste) and Kover (coverage). Reports go to build/reports.

val kotlinSources = listOf(
    "core/src/main/kotlin",
    "core/src/test/kotlin",
    "app/src/main/kotlin",
    "app/src/test/kotlin",
)

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(kotlinSources)
    parallel = true
}

val cpd: Configuration = configurations.create("cpd")

dependencies {
    detektPlugins(libs.compose.rules.detekt)
    cpd(libs.pmd.cli)
    cpd(libs.pmd.kotlin)
    kover(project(":core"))
    kover(project(":app"))
}

// Coverage of :core and :app unit tests in one report; generated code, fakes and previews are not ours to test.
kover {
    // Merges the "unit" variants of :core (jvm) and :app (debug).
    currentProject {
        createVariant("unit") {}
    }
    reports {
        filters {
            excludes {
                classes("*.BuildConfig", "*_Impl", "*_Impl\$*", "*ComposableSingletons*")
                packages("ru.tinyops.android.bookrecon.core.fake")
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
    }
}

// Minimum number of duplicated tokens that counts as a copy; 100 tokens is about 10-15 lines.
val cpdMinimumTokens = providers.gradleProperty("cpdMinimumTokens").getOrElse("100")

val cpdReportDir = layout.buildDirectory.dir("reports/cpd")

fun cpdTask(name: String, format: String, reportFile: String?, failOnViolation: Boolean) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "Finds duplicated Kotlin code with PMD CPD ($format report)."
        classpath = cpd
        mainClass.set("net.sourceforge.pmd.cli.PmdCli")
        // Tests repeat setup on purpose, so only production code is checked, as Sonar does.
        val sources = kotlinSources.filter { "/main/" in it && file(it).isDirectory }
        sources.forEach { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
        args(
            "cpd",
            "--language", "kotlin",
            "--minimum-tokens", cpdMinimumTokens,
            "--format", format,
            if (failOnViolation) "--fail-on-violation" else "--no-fail-on-violation",
            "--dir", sources.joinToString(",") { file(it).absolutePath },
        )
        if (reportFile != null) {
            // Plain File values: the configuration cache cannot keep references to the script.
            val out: File = cpdReportDir.get().file(reportFile).asFile
            outputs.file(out)
            args("--report-file", out.absolutePath)
            doFirst { out.parentFile.mkdirs() }
            doLast { logger.lifecycle("Duplication report: $out") }
        }
    }

// Prints duplicates to the console and fails when there are any.
cpdTask("cpdCheck", "text", null, failOnViolation = true)
// Writes the same list to build/reports/cpd/cpd.txt and never fails.
cpdTask("cpdReport", "text", "cpd.txt", failOnViolation = false)
