import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Version comes from settings pluginManagement / platform.settings; do not restate it.
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        // IntelliJ IDEA Community/Ultimate family; compile against 2025.2 baseline.
        intellijIdea(providers.gradleProperty("platformVersion"))
        // No Java, Kotlin, Android, or other language-plugin dependencies.
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("version")
        description = """
            Shows Skill Bill feature-work status in IntelliJ IDEA. Consumes the
            versioned read-only <code>skill-bill work status</code> contract;
            does not mutate workflows or read Skill Bill databases.
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        vendor {
            name = providers.gradleProperty("pluginVendor")
            url = providers.gradleProperty("pluginVendorUrl")
        }
    }

    pluginVerification {
        ides {
            // Declared range ends: IDEA 2025.2 (IC still published) and 2026.1 (unified IDEA).
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
        }
        failureLevel.set(
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            ),
        )
    }
}

tasks {
    wrapper {
        gradleVersion = "9.3.0"
    }

    withType<Test> {
        // Pure JVM unit/architecture tests; do not launch an IDE fixture here.
        useJUnit()
    }
}

tasks.register("printOwnedTasks") {
    group = "help"
    description = "Lists packaging and verification entry points for contributors."
    doLast {
        println("check, buildPlugin, runIde, verifyPlugin")
    }
}
