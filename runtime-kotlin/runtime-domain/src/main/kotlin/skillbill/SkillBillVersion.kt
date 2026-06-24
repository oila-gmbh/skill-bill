package skillbill

import java.util.Properties

object SkillBillVersion {
  val VALUE: String = resourceVersion()
}

private fun resourceVersion(): String = SkillBillVersion::class.java.classLoader
  .getResourceAsStream("skillbill/version.properties")
  ?.use { stream ->
    Properties()
      .apply { load(stream) }
      .getProperty("version")
      ?.ifBlank { null }
  }
  // The version.properties resource is generated from the Gradle project version
  // at build time, so this fallback only fires when the resource is missing or
  // blank — a packaging defect. Use a clearly non-real sentinel so that case is
  // distinguishable from a genuine dev/release build rather than masquerading as
  // a real version.
  ?: "0.0.0-unknown"
