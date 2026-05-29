# SKILL-55 Subtask 6 - Launch Kit: Reddit / Product Hunt Assets

Parent spec: [.feature-specs/SKILL-55-launch-readiness/spec.md](./spec.md)
Issue key: SKILL-55
Subtask order: 6 of 6
Depends on: subtask 5
Branch model: same-branch, commit per subtask

## Purpose

Produce the assets and plan for the launch itself, pointing at the now-polished
front door (subtask 5) and the prebuilt install (subtask 4). This is the
capstone: it does not press "post" — it assembles a subreddit-targeted draft,
the personal-story hook, the objection-preempt FAQ, and a Product Hunt gallery
plan, so the maintainer can launch deliberately and well.

## Scope

In scope (all artifacts committed under
`.feature-specs/SKILL-55-launch-readiness/launch-kit/`):

- **Reddit post draft(s)** for a receptive niche debut (e.g. r/ClaudeAI,
  r/ChatGPTCoding, r/LLMDevs, r/Anthropic) — **not** r/programming for the first
  post. Humble, specific, pain-first title + body. Lead with the maintainer's
  authentic narrative ("I got so used to structured AI work that unstructured now
  feels incomplete — so I built this"), not an architecture tour.
- **Subreddit plan**: ranked target list, why each fits, posting-time guidance,
  and a note to be present for the first few hours to answer comments.
- **Objection-preempt FAQ** the maintainer can paste into comments, covering at
  minimum:
  - "It won't open / unsigned" — the macOS Gatekeeper + Windows SmartScreen steps
    recorded in subtask 2.
  - "This is overengineered / too complex" — the deep-inside / simple-outside
    framing and the one-command prebuilt path.
  - "Why not just CLAUDE.md / rulesync / Spec Kit?" — the cross-agent + governed +
    durable-workflow-state intersection none of them occupy.
  - "Is it maintained / who's behind it?" — honest solo-but-active answer.
- **Product Hunt kit** (for the later, bigger moment, not the Reddit debut):
  tagline (≤60 chars), description, a 3-5 image gallery plan with the desktop app
  as the hero, the demo video reused from subtask 5, topics/categories, and a
  maker's-comment draft. Note PH's higher asset bar and that the desktop app must
  be a prebuilt download (satisfied by subtasks 2/4) before PH makes sense.
- **Launch readiness checklist**: the go/no-go gate (prebuilt artifacts live for
  all OSes, clean-machine install verified, README front door merged, demo asset
  embedded, FAQ ready).
- **Sequencing note**: Reddit first to sharpen the pitch and gather feedback,
  Product Hunt later as the polished set-piece tied to a release/milestone.

Out of scope:

- Actually posting to Reddit or Product Hunt (explicit human decision).
- Paid promotion, mailing lists, or other channels (follow-up).
- Any code or installer change — this subtask is assets and plan only.

## Acceptance Criteria

1. At least one complete, ready-to-post Reddit draft (title + body) targeted at a
   named receptive subreddit, leading with the personal narrative and pointing at
   the prebuilt quickstart.
2. A ranked subreddit plan with rationale and engagement guidance exists.
3. An objection-preempt FAQ exists covering at minimum: unsigned/won't-open,
   overengineered, vs-alternatives, and maintenance/solo.
4. A Product Hunt kit exists (tagline, description, gallery plan with the desktop
   app as hero, reused demo video, maker comment) with an explicit note that it
   is the later moment, gated on prebuilt artifacts.
5. A launch go/no-go checklist exists and every item traces to a concrete
   subtask-1-to-5 deliverable (no "trust me, it's ready").
6. All claims in the drafts match reality (install commands, supported OSes,
   what the product does) — no aspirational features stated as shipped.

## Validation

```bash
# Drift / link integrity for the committed kit:
npx --yes agnix --strict .
scripts/validate_agent_configs

# Manual review: every install command and capability claim in the drafts is
# cross-checked against install.sh (subtask 4) and the README (subtask 5).
```

## Implementation Notes

- The strongest opener is the maintainer's own words; keep it genuine — Reddit
  rewards authentic builder stories and punishes hype.
- Honesty is strategic here: stating "solo but actively maintained" and "yes it's
  complex inside, here's the simple path" disarms the two most likely top
  comments instead of letting them define the thread.
- The PH gallery should *show* the value (tree → wizard → inline edit → "synced
  to 5 agents"), not describe it. Reuse the subtask-5 demo as the gallery video.
- Keep the kit in-repo so it versions with the product and the go/no-go
  checklist stays honest against what actually shipped.
- This subtask is "decomposition-aware": if subtasks 1-5 land but signing is
  deferred (subtask 2 option b), the FAQ's "won't open" answer is load-bearing —
  make sure it is accurate and prominent, not buried.
