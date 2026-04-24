import dev.skillbill.runtime.buildlogic.configureQuality
import org.gradle.api.Plugin
import org.gradle.api.Project

class QualityConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      with(pluginManager) {
        apply("com.diffplug.spotless")
        apply("io.gitlab.arturbosch.detekt")
      }

      configureQuality()
    }
  }
}
