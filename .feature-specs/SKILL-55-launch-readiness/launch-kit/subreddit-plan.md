# Subreddit plan

Ranked targets for the narrative-led soft launch. All facts here trace to the
[ground-truth fact sheet](README.md). The launch is **Reddit-first** (see
[sequencing-note.md](sequencing-note.md)); this plan picks where to post and how to
behave once posted.

The voice everywhere is the same: a solo maintainer who built a tool to scratch a real
itch, sharing it for feedback — **not** a launch announcement, **not** a pitch. Reddit
punishes marketing and rewards a genuine story plus visible willingness to defend the
design in comments.

## Primary target: r/ClaudeAI

**This is the subreddit the [reddit-draft.md](reddit-draft.md) primary draft is written
for.**

- **Why it ranks first.** The audience already lives in structured-AI-coding workflows
  (Claude Code, slash commands, MCP). The lead agent of the five synced agents is Claude
  Code, so the install lands on a tool they already run. The maintainer narrative — "I
  got so used to structured AI work that unstructured now feels incomplete" — is a
  feeling this audience recognizes immediately, which makes the post resonate rather
  than read as promotion.
- **Receptiveness.** Tooling and workflow shares are common and welcomed when they lead
  with a story and a working artifact. The prebuilt one-command quickstart (`./install.sh`,
  no JDK) is exactly the low-friction trial this crowd will actually run.
- **Risk.** The crowd is sharp on "is this just a prompt pack?" — the
  framework-not-prompts positioning and the vs-alternatives FAQ answers must be ready in
  comments. The "five agents, not just Claude" angle reads as a feature here, not a
  dilution.

## Secondary targets (ranked)

### 2. r/ChatGPTCoding

- **Why.** Broad agent-agnostic coding-with-AI audience; the cross-agent angle (five
  agents, one source of truth) is the headline differentiator here rather than a Claude
  detail. Good fit for the alternate draft variant.
- **Engagement note.** Lead harder on "stop maintaining five copies of your prompts" —
  the pain is universal across this sub even for people who do not use Claude.

### 3. r/LocalLLaMA

- **Why.** Self-hosting and ownership values align with the self-hostable telemetry
  proxy and the offline-capable prebuilt install. Technically demanding, allergic to
  hype.
- **Engagement note.** Be precise and modest. Do not overstate. Expect hard questions
  about what is enforced by contract vs model reasoning — answer plainly. This is a good
  third post only after the first two land cleanly.

### 4. r/programming (and r/devtools)

- **Why.** Largest reach, but the strictest self-promotion culture and lowest
  story-tolerance. Only worth it if the project already has a little traction and a
  comment- worthy "why I built this" framing, posted as a genuine show-and-tell.
- **Engagement note.** High downvote risk for anything that smells like a launch. Save
  for last, or skip for the soft launch entirely. If posted, the title must be a
  problem/story, never a product name.

### Watch-but-do-not-lead: r/SideProject, r/madewithlove style subs

- Friendly to solo-builder shares and good for a low-stakes warm-up post, but the
  audience is builders, not the target *users*. Use as a confidence/feedback warm-up,
  not a primary channel.

## Cross-subreddit engagement guidance

- **Timing.** Post in the morning US Eastern on a weekday (Tue–Thu) for the
  highest-traffic English-speaking window; avoid Friday/weekend for a feedback-seeking
  post. Post to **one** subreddit at a time — do not cross-post simultaneously; a
  same-day duplicate across subs reads as spam.
- **First-hours presence is mandatory.** Block ~2–3 hours after posting to answer every
  comment quickly and honestly. Early author engagement is the single biggest driver of
  a post's trajectory. Have the [objection-faq.md](objection-faq.md) open in a tab.
- **Self-promo norms.** Lead with the story and the problem, link the repo once in the
  body (not the title), and never use a marketing tone. Read and follow each sub's
  self-promotion rule before posting; some require a flair or a ratio of non-promo
  participation first. When in doubt, frame it as "I built this and I'd love feedback,"
  not "check out my tool."
- **Honesty in comments.** If asked about signing, say it is unsigned for v1 and give
  the open-anyway steps. If asked about maintenance, say solo but actively maintained.
  Never claim the demo video exists — it is storyboarded only.
- **Handling pushback.** "Overengineered" and "isn't this just CLAUDE.md?" are the two
  predictable hits. Answer with the prepared FAQ lines, concede the honest parts (yes,
  it is a lot of machinery; yes, one command is all you run), and do not get defensive.
