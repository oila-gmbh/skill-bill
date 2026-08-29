import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(libs.kotlinx.serialization.json)
  implementation(libs.snakeyaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

// SKILL-174: the discovery-exclusion contract is the single deny source shared by filesystem
// discovery (runtime-infra-fs) and shared-context packet migration (runtime-application), so it
// stages onto this module's classpath rather than either consumer's.
val canonicalGoalPlanningDiscoveryExclusionsPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/goal-planning-discovery-exclusions.yaml")
    .absolutePath

val canonicalGoalVerificationBoundaryCapsPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/goal-verification-boundary-caps.yaml")
    .absolutePath

val copyGoalPlanningDiscoveryExclusions =
  tasks.register<Copy>("copyGoalPlanningDiscoveryExclusions") {
    val contractPath = canonicalGoalPlanningDiscoveryExclusionsPath
    from(contractPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(contractPath)
    doFirst {
      require(File(contractPath).exists()) {
        "SKILL-174: goal-planning discovery exclusion contract is missing at $contractPath."
      }
    }
  }

val copyGoalVerificationBoundaryCaps =
  tasks.register<Copy>("copyGoalVerificationBoundaryCaps") {
    val contractPath = canonicalGoalVerificationBoundaryCapsPath
    from(contractPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(contractPath)
    doFirst {
      require(File(contractPath).exists()) {
        "SKILL-202: goal verification boundary caps contract is missing at $contractPath."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

// Every task that consumes the generated resource directory must declare the dependency, not just
// the two obvious ones: skillbill.jvm-library calls withSourcesJar(), so sourcesJar reads this
// source set too and fails on a clean build without it.
listOf("processResources", "processTestResources", "sourcesJar").forEach { consumer ->
  tasks.matching { task -> task.name == consumer }.configureEach {
    dependsOn(copyGoalPlanningDiscoveryExclusions, copyGoalVerificationBoundaryCaps)
  }
}
