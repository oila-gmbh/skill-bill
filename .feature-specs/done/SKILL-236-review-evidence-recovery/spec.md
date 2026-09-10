# SKILL-236: Restore review evidence access and truthful coverage

## Intended outcome

Inline code review can discover and consume its assigned evidence, apply explicit expansions, receive required rubric guidance, and block when coverage is incomplete.

## Scope

Restore trustworthy inline review in 0.3.3-SNAPSHOT. Trace CLI preparation through broker binding, MCP transport, worker guidance, and coverage settlement. Keep this one coherent implementation subtask.

## Evidence and implementation direction

The current parent prompt hides assigned paths while its static tools require exact repository-relative paths. ParallelReviewPreparationCompiler stores prelaunchExpansions on ReviewSpecialistLaunchRequest, but the production launch path never consumes that field. Ordinary unassigned_file_access refusals do not make parallelCodeReviewBrokerEvidenceCompletionState incomplete. The KMP UI rubric requires compose-guidelines.md without a corresponding companion projection.

Do not assume the original broker assignment was empty. Preparation checks nonempty routes and exact assigned-hunk projection. Reproduce rejected delta reads with a controlled MCP worker and inspect exact requested paths and bound identity. An unchanged packet digest alone does not establish dropped expansions. Evidence reads have separate accounting from recordToolCall.

Implement runtime-owned, bounded target discovery through the existing governed operations. Track actual delivered evidence units for coverage. Apply prelaunch expansion requests against final assignment identity before worker launch. Resolve required companion guidance from declared pack metadata into the rubric projection. Adjust canonical contracts first if their shapes change, with version parity and typed parse failures.

## Acceptance Criteria

1. A controlled worker using the real governed MCP interface can discover its assigned targets and read committed diff evidence for both a single rubric and multiple rubrics sharing an inline broker, without guessing paths or using shell access.
2. Assignment discovery is bounded and paginated through the existing two governed operations. It derives from the broker's actual assignment and exposes authorized expansion identities without adding complete path inventories to the parent prompt.
3. Valid --expand-file requests reach the final broker, authorize the intended whole-file read, preserve lane and assignment provenance across merged assignments, and are discoverable by the worker. Invalid lane, path, containment, or reachability requests fail explicitly. Tests assert delivered evidence and authorization, not packet-digest changes alone.
4. Nonempty assignments with undelivered required evidence units produce incomplete coverage and an unsuccessful review gate even when the worker prints verdict: approved. A single successful read cannot establish full multi-rubric coverage. Recoverable refusals followed by complete evidence delivery do not permanently fail the run.
5. Required pack companion guidance, including the KMP UI compose-guidelines.md, reaches the worker through manifest-driven governed rubric composition. Missing required guidance fails before launch. No platform identity special cases or arbitrary reads outside the target repository are introduced.
6. Inline instructions describe only available tools. Discovery, delivery, refusals, and expansions have consistent bounded accounting; zero tool_calls is not treated as proof that no evidence requests occurred, and zero evidence_bytes alone is not a universal failure predicate.
7. Focused regressions exercise the real transport and broker behavior, including all-refused access, partial coverage, valid full coverage, expansions, and companion guidance. Generated skill output is refreshed with ./install.sh when sources or generation change. Installed CLI smoke verification covers commit scope, paired revisions with --diff-file, and explicit expansions, with any unavailable external provider reported as a validation limitation.

## Constraints and non-goals

Follow AGENTS.md, docs/code-principles.md, docs/skill-source-generation.md, runtime-kotlin/ARCHITECTURE.md, and docs/observability-policy.md. Do not add code comments. Keep generated SKILL.md wrappers and provider outputs out of source control. Preserve manifest-driven routing and the two governed evidence operations. Preserve the review prompt's boundary against full path inventories. No changes to the unrelated TicketAutomationIds back-button literals or the external 3f964fe78 commit message. Do not fabricate review findings or attest to coverage from worker prose alone.

## Dependency notes

No preceding subtask. All coupled contracts, producers, transport consumers, coverage settlement, guidance generation, and meaningful regressions land together.

## Validation strategy

Start with a realistic nonempty committed diff and a controlled worker that discovers tools, obtains assigned paths through the MCP boundary, reads evidence, and returns a verdict. Exercise single and merged rubric assignments. Assert actual evidence payloads, authorized expansion provenance, and terminal gate behavior for complete, partial, refused-only, and recovered reads. Keep schema parity and redaction checks where contracts change. Run focused checks appropriate to changed modules, then regenerate/install affected sources and exercise the installed CLI. Follow the pack's build-only restrictions if this goal is stamped for the build gate; do not widen that gate into a root checklist. Report provider or environment blockers without substituting a clean result.

## Next path

The runtime completes this subtask with one reviewed commit and records the result in the decomposition manifest. A blocked smoke review remains a reported limitation until actual installed evidence delivery is established.
