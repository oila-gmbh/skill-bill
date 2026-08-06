package dev.skillbill.intellij.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure identity and click-surface checks that do not require an IDE fixture.
 * Lifecycle, ticker, scheduling, disposal, and multi-consumer coverage live in
 * [SkillBillStatusBarWidgetFixtureTest].
 */
class SkillBillStatusBarWidgetTest {
    @Test
    fun `factory and widget ids stay identical`() {
        assertEquals(SkillBillStatusBarIds.ID, SkillBillStatusBarWidgetFactory().getId())
        assertEquals("SkillBillStatusBarWidget", SkillBillStatusBarIds.ID)
        assertEquals(SkillBillStatusBarIds.DISPLAY_NAME, SkillBillStatusBarWidgetFactory().getDisplayName())
    }

    @Test
    fun `click kind surface is read-only refresh and details only`() {
        val kinds = SkillBillStatusBarWidget.ClickKind.entries.map { it.name }.toSet()
        assertEquals(setOf("REFRESH_AND_DETAILS"), kinds)
        assertTrue(kinds.none { it.contains("START") || it.contains("RESUME") || it.contains("CANCEL") })
    }
}
