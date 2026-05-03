package skillbill.scaffold

internal fun renderCeremonySection(context: TemplateContext): String = buildString {
  append(CANONICAL_CEREMONY_SECTION)
  if (skillRequiresReviewScope(context.skillName)) {
    appendLine()
    appendLine("Determine the review scope using [review-scope.md](review-scope.md).")
  }
  if (skillRequiresStackRouting(context.skillName)) {
    appendLine()
    appendLine("When stack routing applies, follow [stack-routing.md](stack-routing.md).")
  }
  if (skillRequiresSpecialistContract(context.skillName)) {
    appendLine()
    appendLine("When delegated specialist review applies, use [specialist-contract.md](specialist-contract.md).")
  }
  if (skillRequiresReviewDelegation(context.skillName)) {
    appendLine()
    appendLine("When delegated review execution applies, follow [review-delegation.md](review-delegation.md).")
  }
  if (skillRequiresShellContentContract(context.skillName)) {
    appendLine()
    appendLine(
      "When the shell+content contract enforcement applies, follow " +
        "[shell-content-contract.md](shell-content-contract.md).",
    )
  }
  if (skillRequiresReviewOrchestrator(context.skillName)) {
    appendLine()
    appendLine("When review reporting applies, follow [review-orchestrator.md](review-orchestrator.md).")
  }
  if (skillRequiresTelemetryContract(context.skillName)) {
    appendLine()
    appendLine("When telemetry applies, follow [telemetry-contract.md](telemetry-contract.md).")
  }
  if (skillRequiresAuditRubrics(context.skillName)) {
    appendLine()
    appendLine("When feature verification audit rules apply, follow [audit-rubrics.md](audit-rubrics.md).")
  }
}

private fun skillRequiresTelemetryContract(skillName: String): Boolean = skillName.startsWith("bill-") && (
  skillName.endsWith("-code-review") ||
    "-code-review-" in skillName ||
    skillName.endsWith("-quality-check") ||
    skillName.endsWith("-feature-implement") ||
    skillName.endsWith("-feature-verify") ||
    skillName == "bill-pr-description"
  )

private fun skillRequiresReviewScope(skillName: String): Boolean =
  skillName == "bill-code-review" || (skillName.startsWith("bill-") && skillName.endsWith("-code-review"))

private fun skillRequiresStackRouting(skillName: String): Boolean =
  skillName.startsWith("bill-") && (skillName.endsWith("-code-review") || skillName.endsWith("-quality-check"))

private fun skillRequiresSpecialistContract(skillName: String): Boolean =
  skillName != "bill-code-review" && skillName.startsWith("bill-") && skillName.endsWith("-code-review")

private fun skillRequiresReviewDelegation(skillName: String): Boolean =
  skillName.startsWith("bill-") && skillName.endsWith("-code-review")

private fun skillRequiresShellContentContract(skillName: String): Boolean = skillName == "bill-code-review"

private fun skillRequiresAuditRubrics(skillName: String): Boolean =
  skillName == "bill-feature-verify" || skillName.endsWith("-feature-verify")

private fun skillRequiresReviewOrchestrator(skillName: String): Boolean =
  skillName != "bill-code-review" && skillName.startsWith("bill-") &&
    (skillName.endsWith("-code-review") || "-code-review-" in skillName)
