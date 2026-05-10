import dev.skillbill.runtime.buildlogic.configureKmpComposeApplication
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpComposeApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      with(pluginManager) {
        apply("org.jetbrains.kotlin.multiplatform")
        apply("org.jetbrains.compose")
        apply("org.jetbrains.kotlin.plugin.compose")
        apply("skillbill.quality")
      }

      extensions.configure<KotlinMultiplatformExtension> {
        configureKmpComposeApplication(this)
      }
    }
  }
}
