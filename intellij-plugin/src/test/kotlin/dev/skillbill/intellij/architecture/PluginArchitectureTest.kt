package dev.skillbill.intellij.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Pure JVM architecture checks — no IDE fixture. Rejects presentation→infrastructure
 * shortcuts and forbidden Skill Bill runtime / JDBC / SQLite imports.
 */
class PluginArchitectureTest {
    private val mainRoot: Path = locateMainSources()

    @Test
    fun `presentation must not import infrastructure`() {
        val violations = scanPackage("presentation") { text, file ->
            if (text.contains("dev.skillbill.intellij.infrastructure")) {
                file.toString()
            } else {
                null
            }
        }
        assertTrue(
            "presentation must not import infrastructure: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `presentation must not import process JSON filesystem or status-bar APIs`() {
        val forbidden = listOf(
            "ProcessBuilder",
            "java.nio.file",
            "com.google.gson",
            "JsonParser",
            "StatusBarWidget",
            "StatusBarWidgetFactory",
        )
        val violations = scanPackage("presentation") { text, file ->
            forbidden.firstOrNull { token ->
                text.contains(token) && !file.fileName.toString().endsWith("Test.kt")
            }?.let { "$file contains $it" }
        }
        assertTrue("presentation forbidden APIs: $violations", violations.isEmpty())
    }

    @Test
    fun `domain and application must not import IntelliJ or process APIs`() {
        val forbidden = listOf(
            "com.intellij",
            "ProcessBuilder",
            "java.lang.Process",
        )
        val packages = listOf("domain", "application")
        val violations = packages.flatMap { pkg ->
            scanPackage(pkg) { text, file ->
                forbidden.firstOrNull { token -> text.contains(token) }
                    ?.let { "$file contains $it" }
            }
        }
        assertTrue("domain/application forbidden imports: $violations", violations.isEmpty())
    }

    @Test
    fun `plugin sources must not import Skill Bill runtime persistence or JDBC SQLite`() {
        val forbidden = listOf(
            "skillbill.ports.persistence",
            "skillbill.infrastructure.sqlite",
            "skillbill.workflow.WorkflowEngine",
            "skillbill.infrastructure.fs",
            "java.sql",
            "javax.sql",
            "org.sqlite",
            "jdbc:",
            "SQLiteDataSource",
            "JdbcTemplate",
        )
        val violations = scanPackage("") { text, file ->
            forbidden.firstOrNull { token -> text.contains(token) }
                ?.let { "$file contains $it" }
        }
        assertTrue("forbidden runtime/JDBC imports: $violations", violations.isEmpty())
    }

    @Test
    fun `plugin declares only the platform dependency`() {
        val descriptor = listOf(
            Path.of("src/main/resources/META-INF/plugin.xml"),
            Path.of("intellij-plugin/src/main/resources/META-INF/plugin.xml"),
        ).firstOrNull { it.isRegularFile() } ?: error("Cannot locate plugin.xml")
        val depends = Regex("<depends[^>]*>([^<]*)</depends>")
            .findAll(descriptor.readText())
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals(listOf("com.intellij.modules.platform"), depends)
    }

    private fun scanPackage(relative: String, check: (String, Path) -> String?): List<String> {
        val dir = if (relative.isEmpty()) mainRoot else mainRoot.resolve(relative)
        if (!Files.isDirectory(dir)) {
            fail("missing source package: $dir")
        }
        return Files.walk(dir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension == "kt" }
                .mapNotNull { file -> check(file.readText(), file) }
                .toList()
        }
    }

    private fun locateMainSources(): Path {
        val candidates = listOf(
            Path.of("src/main/kotlin/dev/skillbill/intellij"),
            Path.of("intellij-plugin/src/main/kotlin/dev/skillbill/intellij"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("Cannot locate plugin main sources from ${Path.of("").toAbsolutePath()}")
    }
}
