---
name: stack-routing
description: Internal playbook for stack detection, dominant-signal tie-breakers, and router delegation decisions.
---

# Shared Stack Routing Playbook

Use this playbook when another skill needs to decide which stack-specific skill should handle the current repo, diff, or validation scope.

This is not the place for stack-specific review heuristics or build commands. Keep it focused on:
- stack taxonomy
- signal collection order
- dominant-stack tie-breakers
- mixed-stack delegation rules

## Project Guidance

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance when using this playbook.

## Signal Collection Order

When classifying stack or platform:

1. Inspect the changed files first
2. Then inspect repo markers and dependency manifests
3. Prefer strong platform markers over generic language markers
4. When signals are mixed, keep the routing explicit instead of collapsing different stacks into one bucket

## Stack Taxonomy

Classify work as one of:
- `kmp`
- `backend-kotlin`
- `kotlin`
- `php`
- `go`
- `Unknown/Unsupported`

## Stack Signals

### kmp Signals

- `.kt`, `.kts`, `build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`
- `kotlin("multiplatform")`, `org.jetbrains.kotlin.multiplatform`, `expect` / `actual`
- `androidx`, `AndroidManifest.xml`, `androidMain`, `iosMain`, `commonMain`
- Compose, Activities, Fragments, resources, platform source sets

### backend-kotlin Signals

- `.kt`, `.kts`, `build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`
- Kotlin backend/server modules or services without strong Android/KMP markers
- `io.ktor.server`, Spring, Micronaut, Quarkus, http4k
- Exposed, jOOQ, Flyway, Liquibase, Hibernate/JPA, JDBC, R2DBC
- `application.yml`, `application.yaml`, `application.conf`

### kotlin Signals

- `.kt`, `.kts`, `build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`
- Kotlin/JVM libraries, CLIs, or shared utilities without strong `kmp` or `backend-kotlin` markers

### php Signals

- `composer.json`, `composer.lock`, `phpunit.xml`, `phpunit.xml.dist`
- `.php`, `artisan`, `routes/web.php`, `routes/api.php`, `bootstrap/app.php`
- Laravel, Symfony, Slim, Doctrine, Eloquent, PHPUnit, Pest, Blade, Twig

### go Signals

- `go.mod`, `go.sum`, `go.work`, `go.work.sum`
- `.go`, `cmd/`, `internal/`, `pkg/`, `vendor/`
- `net/http`, `google.golang.org/grpc`, chi, gin, echo, fiber, cobra
- `database/sql`, sqlx, `gorm.io`, `entgo.io`, migration tooling, worker or service packages using `context.Context`

## Decision Rules

- If Android/KMP markers are strong, classify as `kmp`.
- Treat Android-only scope as `kmp` too; `kmp` is the package-aligned bucket for Android/KMP work.
- If backend/server markers are strong without meaningful `kmp` markers, classify as `backend-kotlin`.
- If generic Kotlin markers are present without meaningful `kmp` or `backend-kotlin` markers, classify as `kotlin`.
- If PHP markers are strong, classify as `php`.
- If Go markers are strong, classify as `go`.
- If multiple supported stacks are present in one unit of work, classify as `Mixed` for routing purposes and delegate to each matching package-aligned stack-specific skill when the caller supports multi-route execution.
- If no supported stack has clear evidence, classify as `Unknown/Unsupported` and say so explicitly.

## Router Contract

Skills that use this playbook should:
- cite the detected stack
- cite the key signals that drove the choice
- explain whether they routed to one skill or multiple skills
- keep stack-specific heuristics in the delegated skill, not here
