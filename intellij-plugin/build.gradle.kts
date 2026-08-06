import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
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
            // Declared range ends: IDEA 2025.2 and 2026.1.
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.5")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2026.1")
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
