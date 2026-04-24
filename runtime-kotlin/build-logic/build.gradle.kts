plugins {
  base
}

tasks.named("check") {
  dependsOn(":convention:check")
}
