import dev.skillbill.runtime.buildlogic.configureKmpComposeApplication
import dev.skillbill.runtime.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.multiplatform")
      pluginManager.apply("org.jetbrains.compose")
      pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
      pluginManager.apply("skillbill.quality")

      extensions.configure<KotlinMultiplatformExtension> {
        configureKmpComposeApplication(this)
      }

      pluginManager.apply("skillbill.kmp-kotlininject")

      dependencies {
        add("commonMainImplementation", libs.findLibrary("compose.foundation").get())
        add("commonMainImplementation", libs.findLibrary("compose.material").get())
        add("commonMainImplementation", libs.findLibrary("compose.material3").get())
        add("commonMainImplementation", libs.findLibrary("compose.runtime").get())
        add("commonMainImplementation", libs.findLibrary("compose.ui").get())
        add("commonMainImplementation", libs.findLibrary("kotlinx.coroutines.core").get())
      }
    }
  }
}
