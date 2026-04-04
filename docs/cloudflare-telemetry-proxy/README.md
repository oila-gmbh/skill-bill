# Cloudflare Worker telemetry proxy

This example lets Skill Bill clients send telemetry to a small Cloudflare Worker instead of the default hosted relay while keeping the client payload backend-agnostic.

## What it does

1. Accepts `POST` requests containing a JSON body with a `batch` array.
2. Performs lightweight validation on the incoming batch.
3. Adds the server-side `POSTHOG_API_KEY` for this example backend.
4. Forwards the batch to PostHog `/batch/`.

The client payload stays the same privacy-scoped metadata already produced by `scripts/review_metrics.py`:

- completed review run snapshots with finding outcomes, severity buckets, rejected-finding details, and applied learnings (count, references, scope mix, and readable content)

It still excludes:

- repository identity
- raw review text
- local-only learning bookkeeping events

## Deploy

1. Install Wrangler.
2. Copy `wrangler.toml.example` to a local `wrangler.toml`. That generated file is intentionally ignored and should stay machine-specific.
3. From this directory, set the PostHog project key:

   ```bash
   wrangler secret put POSTHOG_API_KEY
   ```

4. Deploy:

   ```bash
   wrangler deploy
   ```

The example defaults `POSTHOG_HOST` to the US Cloud endpoint. If you use EU Cloud or self-hosted PostHog, update `POSTHOG_HOST` in your local `wrangler.toml` before deploying.

## Add the proxy as the telemetry destination

Set the proxy URL on the machine running Skill Bill:

```bash
export SKILL_BILL_TELEMETRY_PROXY_URL="https://your-worker.your-subdomain.workers.dev"
```

When this variable is set, Skill Bill sends telemetry to your Worker only.

Then keep using the normal telemetry commands:

```bash
python3 scripts/review_metrics.py telemetry status
python3 scripts/review_metrics.py telemetry sync
```

## Notes

- This example is intentionally minimal; it protects the PostHog project key, not the endpoint itself.
- If you expect public traffic, add Cloudflare rate limiting, bot protection, or other filtering on top of this Worker.
- The Skill Bill client always emits the same generic `{"batch": [...]}` payload. If you use another analytics backend, adapt this Worker to translate that batch before forwarding it.
