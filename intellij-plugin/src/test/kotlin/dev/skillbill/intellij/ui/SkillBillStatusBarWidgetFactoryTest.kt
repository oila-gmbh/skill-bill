package dev.skillbill.intellij.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillBillStatusBarWidgetFactoryTest {
    @Test
    fun `factory id matches widget id constant and extension registration id`() {
        val factory = SkillBillStatusBarWidgetFactory()
        assertEquals(SkillBillStatusBarIds.ID, factory.getId())
        assertEquals("SkillBillStatusBarWidget", factory.getId())
        assertEquals(SkillBillStatusBarIds.DISPLAY_NAME, factory.getDisplayName())
    }
}
