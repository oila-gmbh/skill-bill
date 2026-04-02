---
name: stack-routing
description: Maintainer-facing reference snapshot for shared stack detection, dominant-signal tie-breakers, and router delegation decisions.
---

# Shared Stack Routing Snapshot

This maintainer-facing reference snapshot documents the shared stack-routing contract used when authoring or updating installable skills.

Runtime-facing skills consume this contract through sibling supporting files such as `stack-routing.md` inside each skill directory. Do not reference this repo-relative path directly from installable skills.

## Signal Collection Order

When classifying stack or platform:

1. Inspect the changed files first
2. Then inspect repo markers and dependency manifests
3. Prefer strong platform markers over generic language markers
4. When signals are mixed, keep the routing explicit instead of collapsing different stacks into one bucket

## Stack Taxonomy

Classify work as one of:
- `agent-config`
- `kmp`
- `backend-kotlin`
- `kotlin`
- `php`
- `go`
- `Unknown/Unsupported`

## Strong Stack Signals

### agent-config

- `SKILL.md`, `AGENTS.md`, `CLAUDE.md`
- `install.sh`, `uninstall.sh`, `config.yaml`, `.claude-plugin/plugin.json`
- `orchestration/`, `skills/`, `scripts/validate_agent_configs.py`, `scripts/skill_repo_contracts.py`
- tests or docs that primarily govern skill contracts, routing, installer behavior, or catalog validation

### kmp

- `kotlin("multiplatform")`, `org.jetbrains.kotlin.multiplatform`, `expect` / `actual`
- `androidMain`, `iosMain`, `commonMain`, `AndroidManifest.xml`
- Compose Multiplatform, Android resources, platform source sets

### backend-kotlin

- Kotlin files/builds plus server markers such as `io.ktor.server`, Spring, Micronaut, Quarkus, http4k
- `application.yml`, `application.yaml`, `application.conf`
- Exposed, jOOQ, Flyway, Liquibase, Hibernate/JPA, JDBC, R2DBC

### kotlin

- Kotlin/JVM libraries, CLIs, or shared utilities without strong `kmp` or `backend-kotlin` markers

### php

- `composer.json`, `composer.lock`, `phpunit.xml*`, `.php`
- Laravel, Symfony, Slim, Doctrine, Eloquent, PHPUnit, Pest, Blade, Twig

### go

- `go.mod`, `go.sum`, `go.work*`, `.go`, `cmd/`, `internal/`, `pkg/`
- `net/http`, gRPC, chi, gin, echo, fiber, `database/sql`, sqlx, GORM, Ent

## Tie-Breakers

- If skill/agent-config repository markers dominate, route to `agent-config`
- If Android/KMP markers are strong, route to `kmp`
- Treat Android-only scope as `kmp` because that is the package-aligned Android/KMP bucket
- If backend/server markers are strong without meaningful `kmp` markers, route to `backend-kotlin`
- If generic Kotlin markers appear without meaningful `kmp` or `backend-kotlin` markers, route to `kotlin`
- If multiple supported stacks are clearly present, treat the scope as mixed and route to each matching stack-specific skill when the caller supports it
- If no supported stack has clear evidence, stop and say the stack is unsupported instead of pretending coverage exists
