package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeFindingVerificationBoundaryMemoryTest {
  private val memory = FeatureTaskRuntimeFindingVerificationBoundaryMemory(
    FileSystemGoalPlanningContextDiscovery(),
    FileSystemGoalPlanningBoundaryBodyResolver(),
  )

  @Test
  fun `verify_findings prompt carries catalog titles only and never whole boundary files or unselected bodies`() {
    val repo = Files.createTempDirectory("verify-findings-prompt-leak")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n\n" +
        "## [2026-08-01] unselected-title\n\nunselected body sentence\n",
    )
    Files.writeString(
      agent.resolve("decisions.md"),
      "# Boundary Decisions\n\n## [2026-08-01] decision-title\n\ndecision body sentence\n",
    )

    val prompt = memory.promptSection(
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      ),
    )

    assertTrue(prompt.contains("selected-title"))
    assertTrue(prompt.contains("unselected-title"))
    assertFalse(prompt.contains("selected body sentence"))
    assertFalse(prompt.contains("unselected body sentence"))
    assertFalse(prompt.contains("decision body sentence"))
    assertFalse(prompt.contains("# Boundary History"))
    assertFalse(prompt.contains("# Boundary Decisions"))
  }

  @Test
  fun `path with no eligible boundary keeps intent only availability signal`() {
    val repo = Files.createTempDirectory("verify-findings-intent-only")
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-002",
          findingPaths = listOf("Foo.kt"),
        ),
      ),
    )
    val prompt = memory.promptSection(sections)

    assertTrue(sections.single().discovery.boundaryContextUnavailable)
    assertTrue(prompt.contains("boundary_context_unavailable: true"))
    assertTrue(prompt.contains("Proceed intent-only"))
  }

  @Test
  fun `goal findings projection shape includes selected heading provenance fields`() {
    val provenance = FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
      headingId = "runtime-kotlin/agent/history.md#abc",
      sourcePath = "runtime-kotlin/agent/history.md",
    ).toArtifactMap()
    assertTrue(provenance.containsKey("heading_id"))
    assertTrue(provenance.containsKey("source_path"))
  }

  @Test
  fun `resolved bodies appear only from persisted boundary selection not checkpoint dispositions`() {
    val repo = Files.createTempDirectory("verify-findings-checkpoint-bodies")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n",
    )
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-001",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
        ),
      ),
    )
    val headingId = sections.single().discovery.boundaryCatalog.single().headingId
    val checkpoint = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
        location = "Foo.kt",
        message = "example",
        selectedBoundaryHeadings = listOf(
          FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
            headingId = headingId,
            sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
          ),
        ),
      ),
    )
    val selections = memory.boundarySelectionsForResolvedBodies(persisted = null)
    assertTrue(selections == null)
    val persistedSelections = mapOf(
      "F-001" to listOf(
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = headingId,
          sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
        ),
      ),
    )
    val resolvedPrompt = memory.resolvedBodiesPromptSection(
      repo,
      sections,
      selectionsByFindingId = memory.boundarySelectionsForResolvedBodies(persisted = persistedSelections)!!,
    )

    assertTrue(resolvedPrompt.contains("selected body sentence"))
    assertFalse(checkpoint.single().selectedBoundaryHeadings.isEmpty())
  }

  @Test
  fun `resolved bodies appear only after boundary selection is settled`() {
    val repo = Files.createTempDirectory("verify-findings-selected-bodies")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n\n" +
        "## [2026-08-01] unselected-title\n\nunselected body sentence\n",
    )
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-001",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
        ),
      ),
    )
    val headingId = sections.single().discovery.boundaryCatalog
      .first { it.heading.contains("selected-title") }
      .headingId
    val resolvedPrompt = memory.resolvedBodiesPromptSection(
      repo,
      sections,
      mapOf(
        "F-001" to listOf(
          FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
            headingId = headingId,
            sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
          ),
        ),
      ),
    )

    assertTrue(resolvedPrompt.contains("selected body sentence"))
    assertFalse(resolvedPrompt.contains("unselected body sentence"))
    assertFalse(resolvedPrompt.contains("# Boundary History"))
  }

  @Test
  fun `over budget verification resolution surfaces through disposition validation helper`() {
    val repo = Files.createTempDirectory("verify-findings-cap-gate")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val headings = (0 until 20).joinToString("\n\n") { index ->
      "## [2026-08-${"%02d".format((index % 28) + 1)}] entry-$index\n\nbody $index"
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$headings\n")
    val sections = memory.sectionsForFindings(
      repo,
      listOf(FeatureTaskRuntimeFindingBoundaryMemoryRequest("F-001", listOf("modules/a/src/Main.kt"))),
    )
    val selected = sections.single().discovery.boundaryCatalog.map {
      FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
        headingId = it.headingId,
        sourcePath = it.sourcePath,
      )
    }
    val reason = memory.validateDispositionBoundaryBodies(
      repo,
      sections,
      listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
          findingId = "F-001",
          disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
          reason = "Matches intent",
          severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
          location = "Main.kt",
          message = "example",
          selectedBoundaryHeadings = selected,
        ),
      ),
    )

    assertTrue(
      reason?.contains("max_selected_bodies") == true || reason?.contains("max_total_body_bytes") == true,
    )
  }

  @Test
  fun `no-owner disposition must record boundary context unavailable`() {
    val repo = Files.createTempDirectory("verify-findings-no-owner-disposition")
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-002",
          findingPaths = listOf("Foo.kt"),
        ),
      ),
    )
    val disposition = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
      findingId = "F-002",
      disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
      reason = "Matches intent",
      severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
      location = "Foo.kt",
      message = "example",
    )

    val reason = memory.validateDispositionBoundaryContext(sections, listOf(disposition))

    assertTrue(reason?.contains("boundary_context_unavailable") == true)
  }

  @Test
  fun `disposition provenance must match catalog heading ids and source paths`() {
    val repo = Files.createTempDirectory("verify-findings-provenance")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n",
    )
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-001",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
        ),
      ),
    )
    val catalogEntry = sections.single().discovery.boundaryCatalog.single()
    val wrongSourcePath = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
      findingId = "F-001",
      disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
      reason = "Matches intent",
      severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
      location = "Foo.kt",
      message = "example",
      selectedBoundaryHeadings = listOf(
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = catalogEntry.headingId,
          sourcePath = "wrong/path/history.md",
        ),
      ),
    )

    val reason = memory.validateDispositionBoundaryProvenance(sections, listOf(wrongSourcePath))

    assertTrue(reason?.contains("source_path") == true)
  }

  @Test
  fun `persisted boundary selections must match later disposition headings`() {
    val persisted = mapOf(
      "F-001" to listOf(
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = "runtime-kotlin/agent/history.md#abc",
          sourcePath = "runtime-kotlin/agent/history.md",
        ),
      ),
    )
    val changed = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
      findingId = "F-001",
      disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
      reason = "Matches intent",
      severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
      location = "Foo.kt",
      message = "example",
      selectedBoundaryHeadings = listOf(
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = "runtime-kotlin/agent/history.md#def",
          sourcePath = "runtime-kotlin/agent/history.md",
        ),
      ),
    )

    val reason = memory.validatePersistedBoundarySelectionsMatch(
      listOf(changed),
      persisted,
    )

    assertTrue(reason?.contains("persisted") == true)
  }

  @Test
  fun `selections without persisted delivery are rejected at settlement`() {
    val repo = Files.createTempDirectory("verify-findings-pending-delivery")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n",
    )
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-001",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
        ),
      ),
    )
    val catalogEntry = sections.single().discovery.boundaryCatalog.single()
    val disposition = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
      findingId = "F-001",
      disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
      reason = "Matches intent",
      severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
      location = "Foo.kt",
      message = "example",
      selectedBoundaryHeadings = listOf(
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = catalogEntry.headingId,
          sourcePath = catalogEntry.sourcePath,
        ),
      ),
    )

    val reason = memory.validateBoundarySelectionsDelivered(
      sections = sections,
      dispositions = listOf(disposition),
      persisted = null,
    )

    assertTrue(reason?.contains("not yet") == true)
  }

  @Test
  @Suppress("LongMethod")
  fun `persisted boundary selections must cover every current finding requiring body delivery`() {
    val repo = Files.createTempDirectory("verify-findings-persisted-omits-current")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n",
    )
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-001",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
        ),
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-002",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Bar.kt"),
        ),
      ),
    )
    val catalogByFindingId = sections.associate { it.findingId to it.discovery.boundaryCatalog.single() }
    val dispositions = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
        location = "Foo.kt",
        message = "example",
        selectedBoundaryHeadings = listOf(
          FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
            headingId = catalogByFindingId.getValue("F-001").headingId,
            sourcePath = catalogByFindingId.getValue("F-001").sourcePath,
          ),
        ),
      ),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-002",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
        location = "Bar.kt",
        message = "example",
        selectedBoundaryHeadings = listOf(
          FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
            headingId = catalogByFindingId.getValue("F-002").headingId,
            sourcePath = catalogByFindingId.getValue("F-002").sourcePath,
          ),
        ),
      ),
    )
    val persisted = mapOf(
      "F-001" to listOf(
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = catalogByFindingId.getValue("F-001").headingId,
          sourcePath = catalogByFindingId.getValue("F-001").sourcePath,
        ),
      ),
    )

    val reason = memory.validateBoundarySelectionsDelivered(
      sections = sections,
      dispositions = dispositions,
      persisted = persisted,
    )

    assertTrue(
      reason != null &&
        reason.contains("F-002") &&
        reason.contains("not yet"),
    )
  }

  @Test
  fun `persisted boundary selections must match scoped catalog`() {
    val repo = Files.createTempDirectory("verify-findings-persisted-catalog")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [2026-08-01] selected-title\n\nselected body sentence\n",
    )
    val sections = memory.sectionsForFindings(
      repo,
      listOf(
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = "F-001",
          findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
        ),
      ),
    )
    val reason = memory.validatePersistedBoundarySelectionsAgainstCatalog(
      sections,
      mapOf(
        "F-001" to listOf(
          FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
            headingId = "off-catalog-id",
            sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
          ),
        ),
      ),
    )

    assertTrue(reason?.contains("absent from the scoped boundary catalog") == true)
  }
}
