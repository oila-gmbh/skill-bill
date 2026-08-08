# Token economy

Skill Bill treats token and context cost as product surface, not tuning.

The failure mode it removes: long agent work rotting inside one conversation.
By subtask N the context is polluted, the model rediscovers the world, and
cost climbs while quality falls. Skill Bill inverts that — expensive discovery
happens once high in the tree; later phases and subtasks receive narrow
projections from durable state.

## What you get

- Large multi-subtask goals stay viable (dozens of subtasks, overnight runs)
- Context pollution is structurally out of scope: fresh session per subtask
- Continuity travels through workflow state and curated artifacts, not transcripts
- Review and validate spend scales with need, not with “run every specialist every time”

You do not need to configure this. `/bill-feature` (or the CLI equivalent) is
the entry point; the economy runs underneath.

## Architecture (spend once, project narrowly)

### Goal planning

- Discovery and **shared preplan** run once at the parent goal
- Each subtask plan **reuses** that shared preplan instead of rediscovering the repo
- Child workflows **hydrate** shared preplan + their own plan as completed
  dependencies — no re-execution, no token attribution for the import
- Each subtask launches in a **fresh session** briefed from durable artifacts
  (subtask spec, shared preplan, plan, boundary memory) — not an inherited
  mega-transcript

### Phase handoffs

- Downstream phases get **compact projections and references**, not full upstream bodies
- Projection **budgets** reject overflow before launch (no silent truncation)
- Resume returns a **compact continuation**; full state is on-demand only
- Audit remediation **reuses** the original immutable preplan/plan — it does not
  replan the whole feature to close gaps

### Review

- Parent prepares **one compact packet** (scope, routing, guidance, build context)
- Specialists get **assigned hunks + direct dependencies** only
- Lanes are selected from **diff signals** — not a blind fan-out of every area
- Follow-up / remediation passes prefer **inline** depth over another full delegated fan-out
- Hard budgets bound packet size, evidence reads, tool calls, and turns
- Specialists are **forbidden from rediscovering** what the parent already resolved
  (status, diff, pack routing, guidance, and related parent work)

### Validate

- Goal children can run **build-only** validation until the last subtask, then full
  depth — intermediate work does not repeatedly pay the full test-obligation cost

### Boundary memory (direction)

- Programmatic heading indexes and selective body delivery so planning can pull
  only the memory slices it needs — indexing without spending model tokens on it

## Design rule

Spend once high. Project narrowly down. Forbid rediscovery. Bound every specialist.
Keep durable state as the continuity medium.

That is why a long goal finishes without the run getting dumber or more expensive
just because it got longer.
