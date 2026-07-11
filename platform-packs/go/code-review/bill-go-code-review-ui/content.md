---
name: bill-go-code-review-ui
description: Use when reviewing Go-owned HTML template, component, form, fragment, CLI, TUI, progress, layout, and rendering correctness.
internal-for: bill-code-review
---

# Go UI Review Specialist

Own rendering and interactive state correctness. Security owns escaping and authorization; UX/accessibility owns semantics and successful task completion.

## Applicability

Apply to Go-rendered web surfaces and interactive terminal programs. Apply templ, htmx, Bubble Tea, or other ecosystem guidance only when repository evidence confirms its use.

## Project-Specific Rules

### Go UI Correctness Rules

Every rule below identifies a concrete rendering or interaction failure.

- Require `html/template.ExecuteTemplate` errors to reach the response or fallback before bytes are committed; ignored failures produce truncated pages with a success status.
- Verify template data uses a purpose-built `struct` view model rather than persistence rows or request objects; leaking internal state risks invalid rendering and accidental data exposure.
- Ensure template lookups use `template.Must` only during controlled startup; request-time panic on a missing template can crash active work.
- Require form repopulation from validated `r.Form` values to preserve submitted input and field errors; clearing input causes a task failure and breaks interaction state.
- Verify `r.FormValue` handling for checkbox, radio, and multi-select controls distinguishes absent from false or empty values; incorrect decoding can mutate unintended data.
- Ensure POST success follows `http.StatusSeeOther` redirect when duplicate refresh submission is unsafe; rendering success directly risks repeated writes.
- Require fragment responses to preserve the `HX-Target` owner and expected content type; mismatched htmx targets cause a state-ordering failure by replacing the wrong region.
- Verify applicable `templ.Component` values receive immutable shaped parameters and propagate render errors; hidden shared state can race and produce incorrect output.
- Ensure each rendered list item carries a stable `ID` through fragment reordering; unstable identity causes incorrect controls after a state-ordering failure.
- Require a `RenderState` value to cover empty, loading, success, and failure branches at render boundaries; omitted states produce an invalid blank or misleading screen.
- Verify links and form actions use the active router or `url.URL` rather than concatenated strings; stale paths break navigation after route changes.
- Ensure interactive CLI output detects `term.IsTerminal` or an equivalent capability before cursor control; ANSI sequences corrupt redirected logs and files.
- Require TUI update logic such as Bubble Tea `Update` to own state transitions and return commands separately; mutation from background goroutines risks races and redraw corruption.
- Verify terminal layout uses measured cell width for Unicode rather than `len(string)`; byte counts can break columns and hide status text.
- Ensure progress renderers writing to `io.Writer` stop and restore the cursor on cancellation or error; abandoned terminal state creates an operational failure by making later output unreadable.
- Require CLI and TUI failures to retain an actionable `error` after redraw; transient messages can disappear and leave an invalid success impression.
