package skillbill.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUiScaleTest {
  @Test
  fun `uses KDE output scale when Linux AWT density is unscaled`() {
    val configHome = kdeConfigHome(scale = 1.75)

    val scale = resolveDesktopUiScale(
      environment = kdeEnvironment,
      systemProperties = Properties(),
      configHome = configHome,
      osName = "Linux",
      awtDensity = 1f,
    )

    assertEquals(1.75f, scale.windowScale)
    assertEquals(1.75f, scale.contentDensity)
  }

  @Test
  fun `uses KDE Xwayland scale before output scale`() {
    val configHome = kdeConfigHome(scale = 1.25)
    configHome.resolve("kwinrc").writeText(
      """
      [Desktops]
      Number=1

      [Xwayland]
      Scale=1.75
      """.trimIndent(),
    )

    val scale = resolveDesktopUiScale(
      environment = kdeEnvironment,
      systemProperties = Properties(),
      configHome = configHome,
      osName = "Linux",
      awtDensity = 1f,
    )

    assertEquals(1.75f, scale.windowScale)
    assertEquals(1.75f, scale.contentDensity)
  }

  @Test
  fun `ignores unscaled generic environment value and falls back to KDE`() {
    val scale = resolveDesktopUiScale(
      environment = kdeEnvironment + ("QT_SCALE_FACTOR" to "1"),
      systemProperties = Properties(),
      configHome = kdeConfigHome(scale = 1.75),
      osName = "Linux",
      awtDensity = 1f,
    )

    assertEquals(1.75f, scale.windowScale)
    assertEquals(1.75f, scale.contentDensity)
  }

  @Test
  fun `KDE Xwayland scale ignores other sections`() {
    val configHome = Files.createTempDirectory("skillbill-desktop-ui-scale")
    configHome.resolve("kwinrc").writeText(
      """
      [Other]
      Scale=1.75
      """.trimIndent(),
    )

    assertNull(readKwinXwaylandScale(configHome.resolve("kwinrc")))
  }

  @Test
  fun `does not correct when AWT already reports scaled density`() {
    val configHome = kdeConfigHome(scale = 1.75)

    val scale = resolveDesktopUiScale(
      environment = kdeEnvironment,
      systemProperties = Properties(),
      configHome = configHome,
      osName = "Linux",
      awtDensity = 2f,
    )

    assertEquals(1f, scale.windowScale)
    assertNull(scale.contentDensity)
  }

  @Test
  fun `explicit property overrides desktop detection`() {
    val properties = Properties().apply {
      setProperty(SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY, "150%")
    }

    val scale = resolveDesktopUiScale(
      environment = kdeEnvironment,
      systemProperties = properties,
      configHome = kdeConfigHome(scale = 1.75),
      osName = "Linux",
      awtDensity = 1f,
    )

    assertEquals(1.5f, scale.windowScale)
    assertEquals(1.5f, scale.contentDensity)
  }

  @Test
  fun `KDE output scale prefers primary setup output`() {
    val configHome = Files.createTempDirectory("skillbill-desktop-ui-scale")
    configHome.resolve("kwinoutputconfig.json").writeText(
      """
      [
        {
          "name": "outputs",
          "data": [
            { "connectorName": "DP-1", "scale": 1.25 },
            { "connectorName": "DP-2", "scale": 1.75 }
          ]
        },
        {
          "name": "setups",
          "data": [
            {
              "outputs": [
                { "enabled": true, "outputIndex": 0, "priority": 2 },
                { "enabled": true, "outputIndex": 1, "priority": 1 }
              ]
            }
          ]
        }
      ]
      """.trimIndent(),
    )

    assertEquals(1.75f, readKdeOutputScale(configHome.resolve("kwinoutputconfig.json")))
  }

  @Test
  fun `invalid KDE output config is ignored`() {
    val configHome = Files.createTempDirectory("skillbill-desktop-ui-scale")
    configHome.resolve("kwinoutputconfig.json").writeText("not json")

    assertNull(readKdeOutputScale(configHome.resolve("kwinoutputconfig.json")))
  }

  @Test
  fun `non Linux platforms keep platform density`() {
    val scale = resolveDesktopUiScale(
      environment = kdeEnvironment,
      systemProperties = Properties(),
      configHome = kdeConfigHome(scale = 1.75),
      osName = "Mac OS X",
      awtDensity = 1f,
    )

    assertEquals(1f, scale.windowScale)
    assertNull(scale.contentDensity)
  }

  @Test
  fun `window correction accounts for existing AWT scale with explicit override`() {
    val properties = Properties().apply {
      setProperty(SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY, "1.75")
    }

    val scale = resolveDesktopUiScale(
      environment = emptyMap(),
      systemProperties = properties,
      configHome = Files.createTempDirectory("skillbill-desktop-ui-scale"),
      osName = "Linux",
      awtDensity = 2f,
    )

    assertTrue(scale.windowScale in 0.874f..0.876f)
    assertEquals(1.75f, scale.contentDensity)
  }

  private fun kdeConfigHome(scale: Double): Path {
    val configHome = Files.createTempDirectory("skillbill-desktop-ui-scale")
    configHome.resolve("kwinoutputconfig.json").writeText(
      """
      [
        {
          "name": "outputs",
          "data": [
            {
              "connectorName": "DP-2",
              "scale": $scale
            }
          ]
        },
        {
          "name": "setups",
          "data": [
            {
              "outputs": [
                {
                  "enabled": true,
                  "outputIndex": 0,
                  "priority": 1
                }
              ]
            }
          ]
        }
      ]
      """.trimIndent(),
    )
    return configHome
  }

  private companion object {
    val kdeEnvironment = mapOf(
      "XDG_CURRENT_DESKTOP" to "KDE",
      "XDG_SESSION_TYPE" to "wayland",
    )
  }
}
