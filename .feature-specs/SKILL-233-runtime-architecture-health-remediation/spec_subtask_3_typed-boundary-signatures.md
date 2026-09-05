# SKILL-233 · Subtask 3: Typed boundary signatures and shared vocabulary

## Scope

Make the compiler check what string comparisons check today, declare every
wire word once, and decide the database location once.

**Value-class identifiers.** The runtime declares zero `value class` types.
Six identifier names cross ports and application as `String` in 674
signatures: `workflowId`, `issueKey`, `subtaskId`, `runId` (review run),
`sessionId`, `agentId`. Introduce `@JvmInline value class` types for each in
`runtime-domain` under the area that owns the concept (`WorkflowId` and
`SessionId` in `skillbill.workflow.engine.model`, `IssueKey` and `SubtaskId` in
`skillbill.workflow.decomposition.model`, `ReviewRunId` in
`skillbill.review.model`, `AgentId` in `skillbill.agent.model`). Each carries
its existing normalisation (issue keys already have a schema contract under
`orchestration/contracts/issue-key-schema.yaml` and
`skillbill.contracts.issuekey`; the value class calls it, it does not duplicate
it). Every port and application signature that carries one of the six names
takes the value type. Conversion to and from `String` happens at exactly three
kinds of boundary: CLI option parsing, MCP payload parsing, and SQLite column
read and write. Contract DTOs in `runtime-contracts` keep `String` fields
because they are wire shapes, and the mapper converts.

**Closed status types.** Seventy-eight fields named `status`, `mode`, `kind`,
`phase`, or `outcome` are `String` in `runtime-ports` and `runtime-domain`
models; 23 are literally `val status: String`. Application code compares them
to string literals in 58 places. `WorkflowGitOperationResult.ok` is
`status == "ok"`. Each field is either:

- a closed set, and becomes an `enum class` with `wireValue` and one `fromWire`
  companion following `docs/code-principles.md` Type Modeling
  (`WorkflowGitOperationResult` becomes a sealed `Ok` / `Failed` result;
  workflow status, phase ledger action, subtask status, and review disposition
  are the obvious enums), or
- a pack-authored open surface, and is inventoried in `ARCHITECTURE.md` under
  the existing open-boundary markers with the reason it cannot close.

The 58 literal comparisons become `when` branches over the enum, exhaustive
without `else`, or are the inventoried open cases with a one-line reason in
code as the SKILL-220 amendment already allows.

**One declaration per wire word.** The tokens behind those fields are
re-declared wherever they are used. `"completed"` appears in 70 main-source
files and 48 of them define their own constant for it. There are 131 local
`setOf("token", ...)` sets and 41 local alias maps; `ProsePhaseOutputParse` in
`skillbill.workflow.taskruntime` is the exemplar, with a private
`STATUS_TOKENS = setOf("completed", "blocked", "failed")` and a private
`STATUS_ALIASES` map, both of which restate a status enum that should exist
once. Across application, domain, and ports main source there are 1,695
distinct snake_case literals, the most frequent being wire keys:
`"contract_version"` (93), `"status"` (90), `"subtask_id"` (63),
`"produced_outputs"` (53), `"phase_id"` (49), `"workflow_id"` (39),
`"step_id"` (38).

Two vocabulary families, two homes:

- **Tokens** (status, phase, kind, mode, action, outcome, verdict, severity
  values) are the `wireValue` of their enum in `runtime-domain`. Aliases the
  runtime accepts on input (`"complete"` for `"completed"`) are declared on
  that enum's `fromWire`, once. A parser such as `ProsePhaseOutputParse` calls
  `fromWire`; it owns no token set.
- **Keys** (field names of durable and wire payloads: `"contract_version"`,
  `"status"`, `"subtask_id"`, `"produced_outputs"`) are constants on the
  contract that owns the payload in `runtime-contracts`, beside the
  `*_CONTRACT_VERSION` constant and `*SchemaPaths` that already live there.
  Every `@OpenBoundaryMap` accessor and every codec reads and writes through
  those constants. Where a typed DTO already exists, the key constant is the
  DTO's serial name and nothing else spells it.

A new architecture scanner, `WireVocabularyArchitectureTest`, indexes the
declared tokens and keys and fails on any main-source snake_case literal that
equals a declared token or key but is not the declaration, and on any
`setOf(...)` or `mapOf(...)` whose elements are all snake_case literals outside
a declaration site. Test sources are exempt. Literals that are neither tokens
nor keys (log message fragments, file names, CLI help text) are not this
rule's concern and the scanner ignores them by construction: it matches only
against the declared index.

**Resolve the database path once.** `dbOverride` / `dbPathOverride: String?`
appears as a parameter 305 times in `runtime-application`, 70 in
`runtime-ports`, and 105 in `runtime-cli`. `EnvironmentContext.dbPathOverride`
already exists on the `RuntimeContext` that `RuntimeComponent` binds, and
`RuntimeBootstrapBindings.databaseSessionFactory(context: EnvironmentContext)`
already receives it. `DatabaseSessionFactory.read`, `readIfPresent`,
`transaction`, `selfManagedWrite`, `resolveDbPath`, and `databaseExists` drop
the parameter and resolve the path from the bound context. Every application
service method drops the trailing `dbOverride` argument. `runtime-cli` keeps
`--db` as a top-level option and threads it into `RuntimeContext` in
`CliRuntime` only; individual commands no longer read `inputs.dbPathOverride`.
Tests that passed an explicit override construct a `RuntimeContext` with it
instead.

## Acceptance Criteria

1. `WorkflowId`, `SessionId`, `IssueKey`, `SubtaskId`, `ReviewRunId`, and
   `AgentId` exist as `@JvmInline value class` types in `runtime-domain`, each
   with the normalisation the corresponding string carried and no more.
2. No `runtime-ports` or `runtime-application` main-source signature declares
   `workflowId`, `issueKey`, `subtaskId`, `runId`, `sessionId`, or `agentId`
   as `String`. An architecture scanner asserts that count is zero with a
   rejection fixture.
3. `String` conversion for the six identifiers happens only in `runtime-cli`
   option parsing, `runtime-mcp` payload parsing, `runtime-infra-sqlite` column
   mapping, and `runtime-contracts` DTO mappers. `runtime-domain` value classes
   expose `value` for those mappers and nothing else reads it.
4. Every `status`, `mode`, `kind`, `phase`, and `outcome` field in
   `runtime-ports` and `runtime-domain` models is an enum or sealed type with
   one `wireValue` and one `fromWire`, or is listed in `ARCHITECTURE.md`'s
   open-boundary inventory with the reason. The report states how many closed
   and how many were inventoried.
5. `WorkflowGitOperationResult` is a sealed type; `status == "ok"` does not
   exist. Every `*GitOperations` implementation and every consumer compiles
   against the sealed shape.
6. No `runtime-application` main-source `when` or `if` compares a status,
   mode, kind, phase, or outcome field to a string literal, except the
   inventoried open cases, each with its one-line reason.
7. Every wire token accepted or emitted by the runtime is declared exactly
   once as an enum `wireValue` in `runtime-domain`, and every accepted alias is
   declared exactly once on that enum's `fromWire`. No main-source file outside
   the declaration contains a `setOf` or `mapOf` of snake_case literals that
   restates tokens. `ProsePhaseOutputParse.STATUS_TOKENS` and `STATUS_ALIASES`
   do not exist; the parser calls the enum.
8. Every durable or wire payload key is declared exactly once as a constant on
   its owning contract in `runtime-contracts`. Every codec, `@OpenBoundaryMap`
   accessor, and mapper reads and writes through the constant. The literal
   `"completed"` appears in exactly one main-source file, and the same holds
   for every other declared token and key.
9. `WireVocabularyArchitectureTest` exists, indexes declared tokens and keys,
   fails on a duplicate literal or a local token set outside a declaration,
   has an acceptance fixture and a rejection fixture, and its baseline is
   empty. The report states the distinct snake_case literal count in
   application, domain, and ports main source against the starting 1,695.
10. `dbOverride` and `dbPathOverride` do not appear in any `runtime-application`
    or `runtime-ports` main-source method signature. `DatabaseSessionFactory`'s
    methods take no path argument.
11. `runtime-cli` reads `--db` in one place and puts it on `RuntimeContext`. No
    command class references `dbPathOverride`.
12. Every test that previously passed an override constructs a `RuntimeContext`
    carrying it. No test bypasses the context to reach a database path.
13. Behaviour is unchanged. A previously accepted wire token or alias that the
    single `fromWire` rejects is a bug fix, is named in the report, and carries
    a test. An alias that two local maps disagreed on is resolved in the report
    with the reason.
14. `runtime-kotlin/gradlew check` and `skill-bill validate` pass with no new
    suppression, no new exemption, and no baseline entry.

## Non-Goals

- Retyping `Map<String, Any?>` payloads or inventoried `@OpenBoundaryMap`
  sites. This subtask closes named fields, not open maps.
- Changing SQLite column types. Identifiers stay `TEXT`; the value class is a
  compile-time wrapper.
- Changing CLI flag names, MCP tool schemas, or contract DTO field types. Wire
  shapes are unchanged; only the inside is typed.
- Introducing value types for every remaining `String` field. Six identifiers
  and five status-family names are the scope; the report may list candidates
  for a later pass.
- Removing string literals that are not wire tokens or keys: log and diagnostic
  message text, CLI help strings, file and directory names, and prompt
  directive prose stay as literals. The vocabulary rule is about words two
  sites must agree on, not about every string.
- Generating the key constants from the YAML schemas under
  `orchestration/contracts/`. Hand-declared constants beside the existing
  `*_CONTRACT_VERSION` are the shape; schema-driven generation is a later
  spec if the parity tests prove too costly to maintain.
- Touching the run loop's structure (subtask 1) or module placement (subtask 4).

## Dependency Notes

Depends on subtask 2: the value classes live in a domain module that no longer
imports JVM types, and the ports they retype are the pure interfaces subtask 2
leaves behind. Retyping a port that still carried a codec would mean retyping
the codec twice.

Depends on subtask 1 for `runtime-application`: retyping consolidated
signatures is one pass; retyping 65 fragments and then consolidating them is
two.

Subtask 4 depends on this one so the engine module is extracted with its final
signatures.

## Validation Strategy

- The identifier scanner and the literal-comparison scanner are set-equality
  assertions against empty baselines with synthetic rejection fixtures. The
  bug they catch is a new service method taking `workflowId: String`.
- Value-class normalisation is proven by the existing issue-key schema tests
  passing through the value class, plus one test per type that rejects the
  malformed shape the string version silently accepted, if one exists.
- Closed status types are proven by the compiler: removing `else` on the
  `when` either compiles or names the missing branch.
- The vocabulary rule is proven by `WireVocabularyArchitectureTest` against an
  empty baseline, with a rejection fixture containing a second
  `setOf("completed", "blocked")` and one containing a bare `"contract_version"`
  outside its contract. The bug it catches is a parser that accepts an alias
  the persistence layer does not, which is what two private alias maps allow
  today.
- Alias consolidation is proven by a table-driven test over every declared
  alias asserting `fromWire(alias) == fromWire(canonical)` for each enum, so a
  dropped alias fails by name.
- `dbOverride` removal is proven by the compiler and by a CLI test that runs a
  command with `--db` pointing at a temp file and asserts the writes land
  there. The bug it catches is a command that silently falls back to the
  default database.
- `runtime-kotlin/gradlew check` in a clean checkout.

## Next Path

```bash
skill-bill goal SKILL-233
```
