package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.error.MalformedMachineConfigError
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliFeatureTaskRuntimeModelDirectiveTest {
  @Test
  fun `feature-task runtime applies a cli phase model directive`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val monitor = StringBuilder()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-model", "plan=gpt-sol@high", "--monitor")),
      fixture.context(launcher) { liveStdout = { monitor.append(it) } },
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("gpt-sol", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
    assertTrue(
      launcher.requests.filterIndexed { index, _ -> ALL_PHASES[index] != "plan" }.all { request ->
        request.skillRunRequest.modelOverride == null && request.skillRunRequest.effortOverride == null
      },
    )
    assertContains(monitor.toString(), "phase plan started agent=codex attempt=1 model=gpt-sol effort=high")
  }

  @Test
  fun `feature-task runtime uses the final repeated phase model assignment`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(
        extra = listOf("--agent", "codex", "--phase-model", "plan=first", "--phase-model", "plan=second@high"),
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("second", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
  }

  @Test
  fun `feature-task runtime applies a model only cli phase directive`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-model", "plan=gpt-sol")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("gpt-sol", plan.modelOverride)
    assertNull(plan.effortOverride)
  }

  @Test
  fun `feature-task runtime reads phase directives from the machine execution matrix`() {
    val fixture = runtimeFixture()
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "agents": {
            "codex": {
              "reasoning": {
                "model": "gpt-sol",
                "effort": "high"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals("gpt-sol", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
  }

  @Test
  fun `feature-task runtime applies cursor execution matrix model directives`() {
    val fixture = runtimeFixture()
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "phase_tiers": {
            "preplan": "reasoning",
            "plan": "reasoning"
          },
          "agents": {
            "cursor": {
              "reasoning": {
                "model": "cursor-grok-4.5-high"
              },
              "implementation": {
                "model": "cursor-grok-4.5-medium"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val preplan = launcher.requests[ALL_PHASES.indexOf("preplan")].skillRunRequest
    val plan = launcher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    val implement = launcher.requests[ALL_PHASES.indexOf("implement")].skillRunRequest
    assertEquals("cursor-grok-4.5-high", preplan.modelOverride)
    assertNull(preplan.effortOverride)
    assertEquals("cursor-grok-4.5-high", plan.modelOverride)
    assertEquals("cursor-grok-4.5-medium", implement.modelOverride)
  }

  @Test
  fun `feature-task runtime rejects a malformed machine execution matrix`() {
    val fixture = runtimeFixture()
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "agents": {
            "codex": {
              "reasoning": {
                "effort": "high"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val launcher = RecordingPhaseLauncher()

    val error = assertFailsWith<MalformedMachineConfigError> {
      CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex")),
        fixture.context(launcher),
      )
    }

    assertContains(error.message.orEmpty(), "Machine config")
    assertContains(error.message.orEmpty(), "execution_matrix.agents.codex.reasoning.model")
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal continuation re-resolves execution matrix directives for child phase launches`() {
    val fixture = runtimeFixture(specFileName = "spec_subtask_5_runtime.md")
    val config = fixture.tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      """
      {
        "execution_matrix": {
          "agents": {
            "codex": {
              "reasoning": {
                "model": "gpt-sol",
                "effort": "high"
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val directLauncher = RecordingPhaseLauncher()
    val direct = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(directLauncher),
    )
    val childLauncher = RecordingPhaseLauncher()
    val child = CliRuntime.run(
      fixture.runCommand(
        extra = listOf(
          "--agent",
          "codex",
          "--goal-parent-issue-key",
          "SKILL-650",
          "--goal-subtask-id",
          "5",
          "--goal-branch",
          "feat/existing-runtime-branch",
          "--goal-review-base-sha",
          "0000000000000000000000000000000000000000",
          "--suppress-pr",
        ),
      ),
      fixture.context(childLauncher),
    )

    assertEquals(0, direct.exitCode, direct.stdout)
    assertEquals(0, child.exitCode, child.stdout)
    val directPlan = directLauncher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    val childPlan = childLauncher.requests[ALL_PHASES.indexOf("plan")].skillRunRequest
    assertEquals(directPlan.modelOverride, childPlan.modelOverride)
    assertEquals(directPlan.effortOverride, childPlan.effortOverride)
    assertEquals("gpt-sol", childPlan.modelOverride)
    assertEquals("high", childPlan.effortOverride)
  }

  @Test
  fun `feature-task runtime rejects malformed phase model directives before workflow opening`() {
    listOf("plan", "=model", "plan=", "unknown=model", "plan=model@high@xhigh", "plan=@high", "plan=model@").forEach {
        assignment ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex", "--phase-model", assignment)),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, "expected rejection for $assignment: ${result.stdout}")
      assertContains(result.stdout, "--phase-model")
      assertEquals(emptyList(), launcher.requests, result.stdout)
      assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
    }
  }

  @Test
  fun `feature-task runtime rejects malformed and unknown review modes before workflow opening`() {
    listOf(
      listOf("--code-review-mode", "unknown"),
      listOf("--code-review-mode", "delegated"),
      listOf("--code-review-mode="),
    ).forEach { modeArgs ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, result.stdout)
      assertContains(result.stdout, "Unknown code-review execution mode")
      assertEquals(emptyList(), launcher.requests)
      assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
    }
  }

  @Test
  fun `feature-task runtime pins omitted and explicit review modes on durable run invariants`() {
    val kotlinDelta = """
      diff --git a/src/Foo.kt b/src/Foo.kt
      --- a/src/Foo.kt
      +++ b/src/Foo.kt
      @@ -0,0 +1 @@
      +fun foo() = 1
    """.trimIndent()
    listOf(
      "omitted" to (emptyList<String>() to "inline"),
      "auto" to (listOf("--code-review-mode", "auto") to "auto"),
      "inline" to (listOf("--code-review-mode", "inline") to "inline"),
    ).forEach { (caseName, modeAndExpected) ->
      val (modeArgs, expectedPinnedMode) = modeAndExpected
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()
      val git = FakeRuntimeGitOperations(trackedDelta = kotlinDelta)

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher) { workflowGitOperations = git },
      )

      assertEquals(0, result.exitCode, "$caseName: ${result.stdout}")
      val workflowId = result.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
      assertEquals(
        expectedPinnedMode,
        runInvariantsCodeReviewMode(fixture.dbPath, workflowId),
        "$caseName durable code_review_mode",
      )
      assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder(), "$caseName: ${result.stdout}")
    }
  }

  @Test
  fun `feature-task runtime rejects an unknown review mode before workflow opening`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--code-review-mode", "external")),
      fixture.context(launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Unknown code-review execution mode 'external'")
    assertEquals(emptyList(), launcher.requests)
    assertFalse(result.stdout.contains("workflow_id:"), result.stdout)
  }

  @Test
  fun `feature-task runtime rejects missing duplicate and conflicting review modes before workflow opening`() {
    listOf(
      "missing" to listOf("--code-review-mode"),
      "duplicate" to listOf("--code-review-mode", "auto", "--code-review-mode", "auto"),
      "conflicting" to listOf("--code-review-mode", "inline", "--code-review-mode", "auto"),
    ).forEach { (caseName, modeArgs) ->
      val fixture = runtimeFixture()
      val launcher = RecordingPhaseLauncher()

      val result = CliRuntime.run(
        fixture.runCommand(extra = listOf("--agent", "codex") + modeArgs),
        fixture.context(launcher),
      )

      assertEquals(1, result.exitCode, "$caseName: ${result.stdout}")
      assertEquals(emptyList(), launcher.requests, "$caseName: ${result.stdout}")
      assertFalse(result.stdout.contains("workflow_id:"), "$caseName: ${result.stdout}")
    }
  }

  @Test
  fun `feature-task run and resume refuse a directive that resolves to junie before launching`() {
    val runFixture = runtimeFixture()
    val runLauncher = RecordingPhaseLauncher()
    val run = CliRuntime.run(
      runFixture.runCommand(
        extra = listOf("--agent", "codex", "--phase-agent", "plan=junie", "--phase-model", "plan=model"),
      ),
      runFixture.context(runLauncher),
    )

    assertEquals(1, run.exitCode, run.stdout)
    assertContains(run.stdout, "phase 'plan'")
    assertContains(run.stdout, "agent 'junie'")
    assertContains(run.stdout, "Capable agents: claude, codex, cursor.")
    assertEquals(emptyList(), runLauncher.requests)
    assertFalse(run.stdout.contains("workflow_id:"), run.stdout)

    val resumeFixture = runtimeFixture()
    val resumeLauncher = RecordingPhaseLauncher()
    val resume = CliRuntime.run(
      listOf(
        "--db",
        resumeFixture.dbPath.toString(),
        "feature-task",
        "resume",
        "wftr-model-directive",
        "SKILL-650",
        resumeFixture.specPath.toString(),
        "--repo-root",
        resumeFixture.tempDir.toString(),
        "--agent",
        "codex",
        "--phase-agent",
        "plan=junie",
        "--phase-model",
        "plan=model",
      ),
      resumeFixture.context(resumeLauncher),
    )

    assertEquals(1, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "agent 'junie'")
    assertContains(resume.stdout, "Capable agents: claude, codex, cursor.")
    assertEquals(emptyList(), resumeLauncher.requests)
  }
}

class CliFeatureTaskRuntimeSpecLookupTest {
  @Test
  fun `feature-task resolves single feature spec match when only issue key is provided`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "SKILL-650",
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
  }

  @Test
  fun `feature-task explicit run resolves single feature spec match when spec path is omitted`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "run",
        "SKILL-650",
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: complete")
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
  }

  @Test
  fun `feature-task key-only lookup reports missing and ambiguous specs`() {
    val missingFixture = runtimeFixture()
    val missing = CliRuntime.run(
      listOf(
        "--db",
        missingFixture.dbPath.toString(),
        "feature-task",
        "SKILL-999",
        "--repo-root",
        missingFixture.tempDir.toString(),
      ),
      missingFixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, missing.exitCode)
    assertContains(missing.stdout, "no .feature-specs match found")

    val ambiguousFixture = runtimeFixture()
    val secondSpec = ambiguousFixture.tempDir.resolve(".feature-specs/SKILL-650-other/spec.md")
    Files.createDirectories(secondSpec.parent)
    Files.writeString(secondSpec, "# second spec\n")
    val ambiguous = CliRuntime.run(
      listOf(
        "--db",
        ambiguousFixture.dbPath.toString(),
        "feature-task",
        "SKILL-650",
        "--repo-root",
        ambiguousFixture.tempDir.toString(),
      ),
      ambiguousFixture.context(RecordingPhaseLauncher()),
    )

    assertEquals(1, ambiguous.exitCode)
    assertContains(ambiguous.stdout, "multiple .feature-specs matches found")
    assertContains(ambiguous.stdout, "SKILL-650-runtime")
    assertContains(ambiguous.stdout, "SKILL-650-other")
  }

  @Test
  fun `feature-task-runtime resume refuses a terminal workflow without re-launching phases`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()
    val run = CliRuntime.run(fixture.runCommand(extra = listOf("--agent", "codex")), fixture.context(launcher))
    assertEquals(0, run.exitCode, run.stdout)
    val workflowId = run.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    // F-018: drive the resume through a SECOND launcher and assert it received zero launches, so
    // the no-relaunch claim is proven against a fresh capture rather than an unchanged shared count.
    val resumeLauncher = RecordingPhaseLauncher()
    val resume = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "resume",
        workflowId,
        "SKILL-650",
        fixture.specPath.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(resumeLauncher),
    )

    assertEquals(1, resume.exitCode, resume.stdout)
    assertContains(resume.stdout, "is terminal and cannot be resumed")
    assertEquals(emptyList(), resumeLauncher.requests, resume.stdout)
  }

  @Test
  fun `feature-task runtime router resumes existing post-plan workflow at implement`() {
    val fixture = runtimeFixture()
    val interruptedLauncher = InterruptAtImplementLauncher()
    val firstRun = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(interruptedLauncher),
    )
    assertEquals(1, firstRun.exitCode, firstRun.stdout)
    val workflowId = firstRun.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()
    assertEquals(listOf("preplan", "plan", "implement"), interruptedLauncher.phaseOrder())

    val resumedLauncher = RecordingPhaseLauncher()
    val resumed = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "feature-task",
        "resume",
        workflowId,
        "SKILL-650",
        fixture.specPath.toString(),
        "--repo-root",
        fixture.tempDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context(resumedLauncher),
    )

    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertContains(resumed.stdout, "workflow_id: $workflowId")
    assertEquals(
      AGENT_LAUNCHED_PHASES.dropWhile { it != "implement" },
      resumedLauncher.phaseOrder(),
    )
    val implementPrompt = resumedLauncher.requests.first().skillRunRequest.promptOverride.orEmpty()
    assertContains(implementPrompt, "### from: plan")
    assertFalse(implementPrompt.contains("### from: preplan"), implementPrompt)
    assertFalse(implementPrompt.contains("preplan_digest"), implementPrompt)
  }

  @Test
  fun `feature-task runtime completion drains a non-empty telemetry outbox`() {
    val fixture = runtimeFixture()
    val requester = RecordingTelemetryRequester()
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")
    assertEquals(1, pendingTelemetryOutboxCount(fixture.dbPath))

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(RecordingPhaseLauncher()) { this.requester = requester },
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertEquals(0, pendingTelemetryOutboxCount(fixture.dbPath))
    // The run records its own events too, so the batch count is incidental; every request must
    // still be the fixture proxy, which is what proves nothing reached a real relay.
    assertTrue(requester.requests.isNotEmpty())
    assertTrue(requester.requests.all { it.contains(TELEMETRY_FIXTURE_PROXY_URL) }, requester.requests.toString())
  }

  @Test
  fun `feature-task runtime completion with telemetry off transmits nothing and retains the outbox`() {
    val fixture = runtimeFixture()
    val requester = RecordingTelemetryRequester()
    // The database only materializes through an enabled read, so enable first and then resolve
    // the install back to 'off' for the run under assertion.
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")
    writeTelemetryConfig(fixture.tempDir, level = "off", proxyUrl = TELEMETRY_FIXTURE_PROXY_URL)

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(RecordingPhaseLauncher()) { this.requester = requester },
    )

    assertEquals(0, run.exitCode, run.stdout)
    assertEquals(emptyList(), requester.requests)
    assertEquals(0, syncedTelemetryOutboxCount(fixture.dbPath))
    assertTrue(pendingTelemetryOutboxCount(fixture.dbPath) >= 1)
  }

  @Test
  fun `a throwing telemetry drain leaves the feature-task runtime outcome byte-for-byte unchanged`() {
    val passing = runtimeDrainOutcome(RecordingTelemetryRequester())
    val throwing = runtimeDrainOutcome(
      RecordingTelemetryRequester(failure = { throw TelemetryProxyExplosion("telemetry proxy exploded") }),
    )

    assertEquals(0, passing.exitCode)
    assertEquals(passing.exitCode, throwing.exitCode)
    assertEquals(passing.stdout, throwing.stdout)
    assertEquals(passing.payload, throwing.payload)
    assertEquals("", passing.stderr)
    assertEquals("", throwing.stderr)
  }

  private fun runtimeDrainOutcome(requester: RecordingTelemetryRequester): DrainOutcome {
    val fixture = runtimeFixture()
    val stderr = StringBuilder()
    fixture.materializeDatabaseWithTelemetry(level = "anonymous", requester = requester)
    seedTelemetryOutbox(fixture.dbPath, "skillbill_fixture_event")

    val run = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex")),
      fixture.context(RecordingPhaseLauncher()) {
        this.requester = requester
        liveStderr = { stderr.append(it) }
      },
    )

    // workflow_id is minted per run, so it is the one field two runs may legitimately differ on.
    val workflowId = run.payload?.get("workflow_id")?.toString().orEmpty()
    return DrainOutcome(
      exitCode = run.exitCode,
      stdout = run.stdout.replace(workflowId, "<workflow-id>"),
      payload = run.payload.orEmpty().filterKeys { it != "workflow_id" },
      stderr = stderr.toString(),
    )
  }
}

private data class DrainOutcome(
  val exitCode: Int,
  val stdout: String,
  val payload: Map<String, Any?>,
  val stderr: String,
)

class CursorAgentRuntimeCliTest {
  @Test
  fun `cursor is accepted for feature-task run`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())
    assertTrue(
      launcher.requests.all { it.agentId == "cursor" },
      "All phases should use cursor agent",
    )
  }

  @Test
  fun `cursor is accepted for feature-task resume`() {
    val fixture = runtimeFixture()
    val interruptedLauncher = InterruptAtImplementLauncher()

    val firstRun = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(interruptedLauncher),
    )
    assertEquals(1, firstRun.exitCode, firstRun.stdout)
    val workflowId = firstRun.stdout.lines().single { it.startsWith("workflow_id:") }.substringAfter(":").trim()

    val resumedLauncher = RecordingPhaseLauncher()
    val resumed = CliRuntime.run(
      fixture.resumeCommand(workflowId, agentId = "cursor"),
      fixture.context(resumedLauncher),
    )

    assertEquals(0, resumed.exitCode, resumed.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES.dropWhile { it != "implement" }, resumedLauncher.phaseOrder())
    assertTrue(
      resumedLauncher.requests.all { it.agentId == "cursor" },
      "All resumed phases should use cursor agent",
    )
  }

  @Test
  fun `cursor goal-child invocation route succeeds`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(launcher.requests.isNotEmpty(), "Should launch cursor for at least one phase")
    assertTrue(launcher.requests.all { it.agentId == "cursor" }, "All phases should use cursor")
  }

  @Test
  fun `cursor accepted for phase-agent override`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--phase-agent", "plan=cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(AGENT_LAUNCHED_PHASES, launcher.phaseOrder())

    val planRequest = launcher.requests.single {
      phaseIdFromPromptOrNull(it.skillRunRequest.promptOverride.orEmpty()) == "plan"
    }
    assertEquals("cursor", planRequest.agentId)

    val nonPlanPhaseRequests = launcher.requests.filter {
      val phaseId = phaseIdFromPromptOrNull(it.skillRunRequest.promptOverride.orEmpty())
      phaseId != null && phaseId != "plan"
    }
    assertTrue(nonPlanPhaseRequests.all { it.agentId != "cursor" }, "Non-plan phases should not use cursor")
  }

  @Test
  fun `cursor accepted for run-wide agent override`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--agent", "codex", "--agent-override", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(
      launcher.requests.all { it.agentId == "cursor" },
      "All phases should use cursor override",
    )
  }

  @Test
  fun `feature-task rejects removed parallel-review-agent option`() {
    val fixture = runtimeFixture()
    val launcher = RecordingPhaseLauncher()

    val result = CliRuntime.run(
      fixture.runCommand(extra = listOf("--parallel-review-agent", "cursor")),
      fixture.context(launcher),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "parallel-review-agent")
    assertEquals(emptyList(), launcher.requests)
  }
}

private class TelemetryProxyExplosion(message: String) : RuntimeException(message)
