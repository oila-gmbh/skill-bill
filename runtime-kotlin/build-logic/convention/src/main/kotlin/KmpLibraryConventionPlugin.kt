import dev.skillbill.runtime.buildlogic.configureKmpDesktop
import dev.skillbill.runtime.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.multiplatform")
      pluginManager.apply("skillbill.quality")

      extensions.configure<KotlinMultiplatformExtension> {
        configureKmpDesktop(this)
      }

      pluginManager.apply("skillbill.kmp-kotlininject")

      dependencies {
        add("commonMainImplementation", libs.findLibrary("kotlinx.coroutines.core").get())
      }
    }
  }
}
