import dev.skillbill.runtime.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class KmpKotlinInjectConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.google.devtools.ksp")

      dependencies {
        add("commonMainImplementation", libs.findLibrary("kotlin.inject.runtime").get())
        add("commonMainImplementation", libs.findLibrary("kotlin.inject.anvil.runtime").get())
        add("commonMainImplementation", libs.findLibrary("kotlin.inject.anvil.runtime.optional").get())

        val compiler = libs.findLibrary("kotlin.inject.compiler").get()
        val anvilCompiler = libs.findLibrary("kotlin.inject.anvil.compiler").get()
        add("kspCommonMainMetadata", compiler)
        add("kspJvm", compiler)
        add("kspCommonMainMetadata", anvilCompiler)
        add("kspJvm", anvilCompiler)
      }
    }
  }
}
