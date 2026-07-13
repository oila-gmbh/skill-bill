plugins {
  id("skillbill.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(project(":runtime-desktop:core:database"))
      implementation(project(":runtime-desktop:core:domain"))
      implementation(libs.room3.runtime)
    }

    jvmMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
      // SKILL-52.2 subtask 5: narrowed allow-list pinned by
      // `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeAdapterDependencyAllowlistTest.kt`.
      // runtime-infra-fs was dropped — the desktop data gateways have no
      // concrete `skillbill.infrastructure.fs.*` imports in jvmMain; the
      // filesystem adapters resolve through `RuntimeComponent`. runtime-contracts
      // is declared explicitly because the gateways import `skillbill.error.*`
      // (e.g. `SkillBillRuntimeException`, `InvalidScaffoldPayloadError`)
      // directly, which previously came through transitive runtime-application API.
      implementation(project(":runtime-application"))
      implementation(project(":runtime-contracts"))
      implementation(project(":runtime-core"))
      implementation(project(":runtime-domain"))
      implementation(project(":runtime-ports"))
      implementation(libs.kotlin.inject.runtime)
    }

    jvmTest.dependencies {
      implementation(project(":runtime-domain"))
      // SKILL-52.2 subtask 5: runtime-infra-fs was dropped from jvmMain but is
      // still concretely imported by `JvmDesktopFirstRunGatewayTest` for the
      // shared `FileSystemInstallSelectionPersistence` test fixture. Test code
      // already crosses module boundaries for fixtures, so this stays as a
      // test-only edge.
      implementation(project(":runtime-infra-fs"))
      // SKILL-48 C8: consume the shared `repoRootFromTest()` helper published by runtime-core
      // via the `java-test-fixtures` plugin so this jvmTest can drop its private copy. The KMP
      // DSL does not expose the `testFixtures()` shorthand; depend on the published capability
      // explicitly instead so the test-fixtures classpath is wired in.
      implementation(project(":runtime-core")) {
        capabilities {
          requireCapability("dev.skillbill:runtime-core-test-fixtures")
        }
      }
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.core)
    }
  }
}
