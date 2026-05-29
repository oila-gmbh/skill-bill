package dev.skillbill.runtime.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopVersionsTest {
  @Test
  fun `strips -SNAPSHOT qualifier`() {
    // The live project.version case: 0.1.0-SNAPSHOT must become a jpackage-legal 0.1.0.
    assertEquals("0.1.0", toJpackageVersion("0.1.0-SNAPSHOT"))
  }

  @Test
  fun `strips pre-release and build metadata qualifiers`() {
    assertEquals("1.2.3", toJpackageVersion("1.2.3-rc1+build5"))
  }

  @Test
  fun `strips bare build metadata`() {
    assertEquals("1.2.3", toJpackageVersion("1.2.3+build5"))
  }

  @Test
  fun `pads missing patch component`() {
    assertEquals("0.1.0", toJpackageVersion("0.1"))
  }

  @Test
  fun `pads missing minor and patch components`() {
    assertEquals("1.0.0", toJpackageVersion("1"))
  }

  @Test
  fun `reduces a component with a trailing non-numeric run to its leading digits`() {
    assertEquals("2.0.0", toJpackageVersion("2beta"))
  }

  @Test
  fun `maps a fully non-numeric component to zero`() {
    // Edge case per documented rule: a component with no leading digit becomes 0,
    // and the result still has exactly three numeric components.
    assertEquals("0.0.0", toJpackageVersion("beta"))
  }

  @Test
  fun `maps blank input to zeroed three-component version`() {
    assertEquals("0.0.0", toJpackageVersion(""))
  }

  @Test
  fun `trims surrounding whitespace before normalizing`() {
    assertEquals("3.4.5", toJpackageVersion("  3.4.5  "))
  }

  @Test
  fun `truncates extra version components beyond three`() {
    assertEquals("1.2.3", toJpackageVersion("1.2.3.4"))
  }

  @Test
  fun `preserves multi-digit components without truncation`() {
    // F-006 regression guard: a per-character truncation bug would turn 10.20.30 into
    // 1.2.3; assert the full multi-digit components survive.
    assertEquals("10.20.30", toJpackageVersion("10.20.30"))
  }

  @Test
  fun `maps an empty leading component to zero`() {
    // F-007: a leading dot yields an empty first component, which the documented rule
    // (no leading digits -> 0) maps to 0.
    assertEquals("0.1.2", toJpackageVersion(".1.2"))
  }

  @Test
  fun `maps an empty interior component to zero`() {
    // F-007: `1..3` has an empty middle component, mapped to 0 per the documented rule.
    assertEquals("1.0.3", toJpackageVersion("1..3"))
  }

  @Test
  fun `mac app version bumps a zero major to one`() {
    // Live case: project.version 0.1.0-SNAPSHOT -> 0.1.0 has a 0 major, illegal for .dmg;
    // toMacAppVersion bumps only the major, leaving minor and patch untouched.
    assertEquals("1.1.0", toMacAppVersion("0.1.0-SNAPSHOT"))
  }

  @Test
  fun `mac app version leaves a positive major unchanged`() {
    assertEquals("2.3.4", toMacAppVersion("2.3.4-rc1"))
  }

  @Test
  fun `mac app version bumps zero-only input`() {
    assertEquals("1.0.0", toMacAppVersion("0"))
  }

  @Test
  fun `mac app version preserves multi-digit major and components`() {
    // F-006 regression guard: a positive multi-digit major (and minor/patch) must survive
    // unchanged through the macOS bump.
    assertEquals("10.20.30", toMacAppVersion("10.20.30"))
  }

  @Test
  fun `mac app version clamps an oversized major to the minimum`() {
    // F-003: toJpackageVersion applies no width cap, so an oversized major overflows Int.
    // toMacAppVersion must NOT throw — it deterministically clamps to the minimum (1).
    assertEquals("1.0.0", toMacAppVersion("9999999999.0.0"))
  }
}
