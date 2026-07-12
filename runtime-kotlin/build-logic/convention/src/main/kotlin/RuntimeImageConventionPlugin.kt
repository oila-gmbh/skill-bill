import dev.skillbill.runtime.buildlogic.RuntimeImageExtension
import dev.skillbill.runtime.buildlogic.RuntimeImageLicense
import dev.skillbill.runtime.buildlogic.writeSha256Sidecar
import org.beryx.runtime.BaseTask
import org.beryx.runtime.data.RuntimePluginExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.nio.file.Path

class RuntimeImageConventionPlugin : Plugin<Project> {
  private companion object {
    const val LINK_JDK_VERSION = 17

    // F-002: explicit additive module set. Non-modular jlink cannot derive the module
    // set via jdeps reliably for the kotlin-inject / kotlinx.serialization automatic
    // modules, so pin an explicit, additive set. `java.net.http` is REQUIRED: the
    // telemetry HTTP client (runtime-infra-http `JdkHttpRequester` /
    // `HttpTelemetryClient`, resolved at runtime via RuntimeComponent/kotlin-inject)
    // uses `java.net.http.HttpClient` in production (MCP telemetry_remote_stats /
    // telemetry_proxy_capabilities, telemetry outbox sendBatch). HTTPS endpoints rely
    // on `jdk.crypto.ec` (present) plus TLS in java.base; `java.security.sasl` /
    // `jdk.crypto.cryptoki` are NOT needed for standard HTTPS, so they are deliberately
    // omitted to keep the set minimal but complete.
    val IMAGE_MODULES =
      listOf(
        "java.base",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.sql",
        "java.xml",
        "java.desktop",
        "jdk.crypto.ec",
        "jdk.unsupported",
      )

    const val CC_OPT_OUT_REASON =
      "Badass Runtime is not configuration-cache compatible (serializes Gradle model objects)."

    const val UNSUPPORTED_HOST_SEGMENT = "unsupported-host"
  }

  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.beryx.runtime")

      val extension = extensions.create("runtimeImage", RuntimeImageExtension::class.java)
      val hostRuntimeToken = extension.hostRuntimeToken

      logUnsupportedHost(hostRuntimeToken)
      configureStaticRuntimeWiring(hostRuntimeToken)

      // imageBaseName is the only consumer-supplied input and is set inside the
      // `runtimeImage {}` block AFTER this plugin's apply() runs. Defer everything that
      // reads it to afterEvaluate so the base name is resolved exactly once, lazily.
      afterEvaluate {
        val baseName = extension.imageBaseName.get()
        val zipName = imageZipName(baseName, project.version.toString(), hostRuntimeToken)
        configureRuntimeImageZip(zipName)
        val licenseStageTask = registerRuntimeLicenseStaging(baseName)
        val licenseVerificationTask = registerRuntimeLicenseVerification(baseName, licenseStageTask)
        val sha256Task = registerSha256Task(baseName, zipName)
        configureRuntimeZipTask(baseName, hostRuntimeToken, licenseVerificationTask, sha256Task)
      }
    }
  }

  private fun Project.logUnsupportedHost(hostRuntimeToken: String?) {
    if (hostRuntimeToken != null) return
    // F-001: unsupported host (e.g. arm64 Linux) is an OPTIONAL known-gap target. Do NOT
    // hard-fail at configuration time — `check` / installDist and IDE sync must succeed
    // on any arch. Log a clear known-gap message; image tasks are still registered but
    // fail loudly only when actually invoked.
    logger.lifecycle(
      "SKILL-55: host os.name='${System.getProperty("os.name")}' " +
        "os.arch='${System.getProperty("os.arch")}' is not a supported runtime-image " +
        "target (known gap). Image tasks (runtimeZip/runtimeZipSha256) are unavailable " +
        "on this host; build them on a matching CI runner.",
    )
  }

  private fun Project.configureStaticRuntimeWiring(hostRuntimeToken: String?) {
    // F-006: keep the link toolchain LAZY. Map the launcher provider to a path String
    // instead of `.get()`-ing it at config time, so unrelated builds (`check`,
    // installDist) never provision the JDK17 toolchain on a config-cache miss.
    val toolchains = extensions.getByType<JavaToolchainService>()
    val linkJavaHomeProvider: Provider<String> =
      toolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(LINK_JDK_VERSION)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

    extensions.configure<RuntimePluginExtension>("runtime") {
      // F-006: pass the LAZY provider; Badass resolves javaHome at execution time.
      javaHome.set(linkJavaHomeProvider)
      // Trim the linked runtime: drop debug symbols, headers, man pages, compress.
      addOptions("--no-header-files", "--no-man-pages", "--strip-debug", "--compress", "2")
      // `additive` keeps the explicit modules on top of any the plugin already detects.
      additive.set(true)
      modules.set(IMAGE_MODULES)
    }

    // The project enables the configuration cache globally (gradle.properties); every
    // Badass runtime task opts out via the supported API. Only image-building tasks
    // degrade to no-cache; check / installDist keep a warm configuration cache.
    tasks.withType<BaseTask>().configureEach {
      notCompatibleWithConfigurationCache(CC_OPT_OUT_REASON)
      if (hostRuntimeToken == null) {
        // F-001: fail loudly at EXECUTION time on every Badass image task when the host
        // is an unsupported known-gap target, so the failure surfaces before expensive
        // jlink work and unrelated tasks (check/installDist) still succeed on any arch.
        doFirst {
          error(unsupportedHostMessage())
        }
      }
    }
  }

  private fun Project.configureRuntimeImageZip(zipName: String) {
    // Versioned archive name derived from project.version — never hardcoded (AC6).
    extensions.configure<RuntimePluginExtension>("runtime") {
      imageZip.set(layout.buildDirectory.file("runtime-image/$zipName"))
    }
  }

  private fun imageZipName(baseName: String, version: String, hostRuntimeToken: String?): String {
    val tokenSegment = hostRuntimeToken ?: UNSUPPORTED_HOST_SEGMENT
    return "$baseName-$version-$tokenSegment.zip"
  }

  private fun Project.registerSha256Task(baseName: String, zipName: String): TaskProvider<*> {
    // AC2: write a SHA-256 sidecar next to the produced image zip. F-101: the zip path
    // is resolved to a primitive String OUTSIDE the doLast closure; the closure captures
    // only Strings, never a Gradle File/Provider.
    val runtimeImageZipPath =
      layout.buildDirectory
        .file("runtime-image/$zipName")
        .get()
        .asFile.absolutePath
    return tasks.register("runtimeZipSha256") {
      group = "distribution"
      description = "Write a SHA-256 sidecar next to the $baseName image zip (AC2)."
      dependsOn("runtimeZip")
      val archivePath = runtimeImageZipPath
      val checksumPath = "$archivePath.sha256"
      inputs.file(archivePath)
      outputs.file(checksumPath)
      notCompatibleWithConfigurationCache("Sidecar follows the not-cacheable runtimeZip task.")
      doLast {
        // F-001: delegate to the shared sidecar writer so the `<hex>  <name>\n` format
        // stays byte-identical with runtime-desktop's installer sidecars. The sidecar it
        // writes (<archive>.sha256) is exactly the declared `checksumPath` output above.
        writeSha256Sidecar(File(archivePath))
      }
    }
  }

  private fun Project.registerRuntimeLicenseStaging(baseName: String): TaskProvider<*> {
    val rootLicensePath = rootProject.projectDir.parentFile.resolve("LICENSE").absolutePath
    val imageLicensePath = layout.buildDirectory.file("image/LICENSE").get().asFile.absolutePath
    val installLicensePath = layout.buildDirectory.file("install/$baseName/LICENSE").get().asFile.absolutePath
    return tasks.register("stageRuntimeLicense") {
      group = "distribution"
      description = "Stage the repository LICENSE in the $baseName install and runtime images."
      dependsOn("runtime")
      inputs.file(rootLicensePath)
      outputs.files(imageLicensePath, installLicensePath)
      doLast {
        val source = Path.of(rootLicensePath)
        try {
          RuntimeImageLicense.stage(source, listOf(Path.of(imageLicensePath), Path.of(installLicensePath)))
        } catch (error: IllegalArgumentException) {
          throw GradleException(error.message.orEmpty(), error)
        }
      }
    }
  }

  private fun Project.registerRuntimeLicenseVerification(
    baseName: String,
    licenseStageTask: TaskProvider<*>,
  ): TaskProvider<*> {
    val rootLicensePath = rootProject.projectDir.parentFile.resolve("LICENSE").absolutePath
    val imageLicensePath = layout.buildDirectory.file("image/LICENSE").get().asFile.absolutePath
    val installLicensePath = layout.buildDirectory.file("install/$baseName/LICENSE").get().asFile.absolutePath
    return tasks.register("verifyRuntimeImageLicense") {
      group = "verification"
      description = "Verify $baseName install and runtime images carry the root LICENSE unchanged."
      dependsOn(licenseStageTask)
      inputs.file(rootLicensePath)
      inputs.files(imageLicensePath, installLicensePath)
      doLast {
        val source = Path.of(rootLicensePath)
        listOf(Path.of(imageLicensePath), Path.of(installLicensePath)).forEach { destination ->
          if (!RuntimeImageLicense.matches(source, destination)) {
            throw GradleException("Packaged LICENSE differs from repository LICENSE at $destination.")
          }
        }
      }
    }
  }

  private fun Project.configureRuntimeZipTask(
    baseName: String,
    hostRuntimeToken: String?,
    licenseVerificationTask: TaskProvider<*>,
    sha256Task: TaskProvider<*>,
  ) {
    // F-008: SINGLE runtimeZip configuration block — description + sha256 finalizer.
    // Fail-fast for an unsupported host (F-001) is handled on every BaseTask in
    // configureStaticRuntimeWiring, so this block stays config-time safe on any arch.
    val tokenSegment = hostRuntimeToken ?: UNSUPPORTED_HOST_SEGMENT
    tasks.named("runtimeZip") {
      group = "distribution"
      description =
        "Build the self-contained $baseName image and a versioned image zip ($tokenSegment)."
      dependsOn(licenseVerificationTask)
      finalizedBy(sha256Task)
    }
  }

  private fun unsupportedHostMessage(): String = "SKILL-55: cannot build a self-contained runtime image on this host " +
    "(os.name='${System.getProperty("os.name")}', os.arch='${System.getProperty("os.arch")}'). " +
    "This is a known-gap target; build on a matching CI runner."
}
