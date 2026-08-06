package dev.skillbill.intellij.infrastructure.prefs

import dev.skillbill.intellij.domain.CachedDisplaySnapshot
import dev.skillbill.intellij.domain.LastKnownDisplayCache
import dev.skillbill.intellij.domain.toStaleOutcome
import dev.skillbill.intellij.fakes.FakePreferenceCache
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSanitizerAndCacheTest {
    @Test
    fun `cache fallback produces stale UI outcome only`() {
        val cache = LastKnownDisplayCache(
            display = CachedDisplaySnapshot(
                summary = "last known implement",
                issueKey = "SKILL-148",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                startedAt = Instant.parse("2026-08-06T08:00:00Z"),
            ),
            observedAt = Instant.parse("2026-08-06T09:00:00Z"),
        )
        val outcome = cache.toStaleOutcome()
        assertTrue(outcome.fromCache)
        assertTrue(outcome is dev.skillbill.intellij.domain.SkillBillStatusOutcome.Stale)
    }

    @Test
    fun `preference sanitizer rejects tokens prompts stderr and unbounded blobs`() {
        assertNull(PreferenceSanitizer.sanitizeExecutablePath("token=abc123"))
        assertNull(PreferenceSanitizer.sanitizeExecutablePath("Bearer secret-value"))
        assertTrue(PreferenceSanitizer.looksLikeSecret("api_key=xyz"))
        assertTrue(PreferenceSanitizer.looksLikeSecret("stderr dump"))
        assertTrue(PreferenceSanitizer.looksLikeUnboundedBlob("x".repeat(5_000)))
        assertNull(
            PreferenceSanitizer.sanitizeCache(
                LastKnownDisplayCache(
                    display = CachedDisplaySnapshot(summary = "token=leak"),
                    observedAt = Instant.parse("2026-08-06T09:00:00Z"),
                ),
            ),
        )
        assertNull(
            PreferenceSanitizer.sanitizeCache(
                LastKnownDisplayCache(
                    display = CachedDisplaySnapshot(summary = "phase artifact\n".repeat(40)),
                    observedAt = Instant.parse("2026-08-06T09:00:00Z"),
                ),
            ),
        )
    }

    @Test
    fun `fake preference port rejects forbidden preference writes`() {
        val prefs = FakePreferenceCache()
        prefs.setCliExecutableOverride("token=abc")
        prefs.setLastKnownDisplayCache(
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(summary = "stderr dump"),
                observedAt = Instant.parse("2026-08-06T09:00:00Z"),
            ),
        )
        assertNull(prefs.getCliExecutableOverride())
        assertNull(prefs.getLastKnownDisplayCache())
        assertTrue(prefs.rejectedWrites.isNotEmpty())
    }
}
