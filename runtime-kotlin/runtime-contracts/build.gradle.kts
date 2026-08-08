import org.gradle.api.tasks.testing.Test
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

val copyGoalPlanningDiscoveryExclusions =
  tasks.register<Copy>("copyGoalPlanningDiscoveryExclusions") {
    val contractPath = canonicalGoalPlanningDiscoveryExclusionsPath
    from(contractPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(contractPath)
    doFirst {
      require(File(contractPath).exists()) {
        "SKILL-174: canonical goal-planning discovery exclusion contract is missing at $contractPath."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyGoalPlanningDiscoveryExclusions)
}

tasks.named("processTestResources") {
  dependsOn(copyGoalPlanningDiscoveryExclusions)
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}
