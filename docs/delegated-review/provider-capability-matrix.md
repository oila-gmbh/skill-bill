# Delegated provider capability matrix

The runtime records this matrix as an evaluation boundary, not as a promotion
claim. The eight dimensions are fresh-context isolation, worker tracking,
output capture, declared specialist progress, cancellation, timeout,
token reporting, and terminal-result behavior. A provider marked unsupported
must terminate as unsupported; it must not silently fall back to inline review.

| Provider | Status | Isolation | Tracking | Output | Progress | Cancel | Timeout | Tokens | Terminal |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Codex | experimental | yes | yes | yes | yes | yes | yes | completion-only | yes |
| Claude | experimental | yes | yes | yes | yes | yes | yes | completion-only | yes |
| Cursor | experimental | yes | yes | yes | yes | yes | yes | completion-only | yes |
| Junie | unsupported | no | no | no | no | no | no | no | no |
| Copilot | unsupported | no | no | no | no | no | no | no | no |
| Opencode | unsupported | no | no | no | no | no | no | no | no |
| Zcode | unsupported | no | no | no | no | no | no | no | no |

The runtime registry is the executable source for this table:
DelegatedReviewProviderCapabilityRegistry. The matrix is deliberately
independent from routing and inline-review defaults. Completion-only token
observations are retained for measurement and never extend an idle deadline or
prove specialist progress.

Reliability boundary by provider: Codex uses `fork_turns: none` and its own
native lifecycle callbacks; Claude retains fresh-process and stream-decoder
behavior; Cursor retains its independent stream decoder and process strategy;
Junie, Copilot, Opencode, and Zcode remain explicit unsupported outcomes. All
providers share only the coordinator-owned capacity plan, bounded lifecycle
diagnostics, strict aggregation gate, and durable terminal classifications.
