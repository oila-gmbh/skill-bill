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
  ?: "0.3.0-SNAPSHOT"
