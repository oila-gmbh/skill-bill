# TypeScript LLM / Agentic Backend Review Add-On

> SCAFFOLD (SKILL-116). Structure and activation signals are authored; the
> per-topic rules are TODO. This file cannot pass the maintained substance gate
> until the companion topic files exist and each carries concrete, reachable
> failure modes. Do not ship as-is.

Use this governed add-on only after stack routing has already selected `typescript`
and the review scope touches an LLM or agentic backend — model-provider calls, a
retrieval-augmented pipeline, an agent/tool loop, or a document-indexing pipeline.

This file is a review index for `bill-typescript-code-review` and its
`reliability`, `security`, `performance`, and `api-contracts` specialists. It is
not a standalone review command. The guidance is generic to LLM-backed TypeScript
services; it encodes recurring failure modes, not any single project's internals.

## Activation signals

Activate `ai-llm-backend` when the diff shows any of:

- a model-provider SDK/gateway call (chat/completions/responses/embeddings), a
  provider/model-routing switch, or a per-deployment health/circuit-breaker
- embedding generation, a vector-store upsert/query, or a reranker call
- an agent/tool loop, a plan/step executor, or a tool registry bound to data
  sources
- a document-indexing pipeline (event → chunk → embed → store), or prompt/token
  budgeting and streaming of model output to a client

If the diff only touches non-AI application code with no model, embedding, vector,
or agent surface, do **not** activate this add-on.

## Section index

Scan this file first. Then open only the linked topic files whose cues match the
diff instead of loading all AI guidance by default.

- `ai-llm-backend-gateway.md` *(TODO — create)*
  Read when the diff touches model access, routing, scope, or resilience.
  Cluster: single-entry model-gateway indirection with provider/model routing and
  per-deployment circuit-breaking; keeping the gateway out of request scope
  (explicit user context vs upward scope cascade); model-tier indirection so a
  model swap is config, not code.
- `ai-llm-backend-pipeline.md` *(TODO — create)*
  Read when the diff touches indexing, retrieval, or agent loops. Cluster: thin
  events (publish ids, never full content) on a durable queue vs in-request
  queuing; idempotent re-index; RAG tenant-scoped metadata filter, hybrid search,
  rerank; bounded fan-out and per-step timeouts in the agent loop.
- `ai-llm-backend-safety-and-cost.md` *(TODO — create)*
  Read when the diff touches prompts, tokens, streaming, or model logging.
  Cluster: token/cost budgeting and truncation; prompt-injection / untrusted-
  content boundaries; structured model-resolution logging without leaking
  secrets/PII; streaming/subscription cleanup and cancellation.

## How to use it

- Treat findings from this add-on as
  `reliability`/`security`/`performance`/`api-contracts` findings — fold them
  into those specialists' registers, do not create a parallel lane.
- Every finding must name a concrete, reachable failure: a request-scoped gateway
  cascading scope across the container, an unbounded agent fan-out or missing
  timeout exhausting the event loop or budget, a re-index that duplicates or
  drops vectors, a cross-tenant retrieval leak from a missing metadata filter, a
  prompt that concatenates untrusted content, an unbounded token cost. Describe
  the triggering state explicitly.
