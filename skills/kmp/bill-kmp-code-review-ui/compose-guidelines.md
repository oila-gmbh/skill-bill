# Compose UI Guidelines

## 1. State Hoisting

Every composable that displays or reacts to state MUST follow the state hoisting pattern:

- **Stateless composables** receive state as parameters and emit events via lambdas.
- **Stateful wrappers** own `remember` / `ViewModel` state and delegate to the stateless version.
- Never call `remember {}`, `mutableStateOf()`, or collect flows inside a low-level UI composable — hoist it up.

```kotlin
// ✅ Stateless — testable, previewable, reusable
@Composable
fun LoginForm(
    email: String,
    password: String,
    isLoading: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) { /* UI only */ }

// ✅ Stateful wrapper — owns the state
@Composable
fun LoginFormStateful(
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LoginForm(
        email = uiState.email,
        password = uiState.password,
        isLoading = uiState.isLoading,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLoginClick = viewModel::onLoginClick,
    )
}
```

### State Hoisting Rules

- Hoist state to the **lowest common ancestor** that needs it.
- Never hoist higher than necessary — don't push everything to the screen level if only a subsection needs it.
- Use `collectAsStateWithLifecycle()` (not `collectAsState()`) for `Flow` → `State` conversion. It is lifecycle-aware and avoids wasted work when the UI is off-screen.
- Use `rememberSaveable` instead of `remember` for state that must survive configuration changes (e.g., text field input, scroll position, selected tabs).

---

## 2. Composable Function Signature Conventions

Follow the official Compose API guidelines for every composable signature:

```kotlin
@Composable
fun MyComponent(
    // 1. Required parameters first
    title: String,
    onAction: () -> Unit,
    // 2. Optional parameters with defaults
    subtitle: String? = null,
    isEnabled: Boolean = true,
    // 3. modifier — always last non-trailing-lambda parameter, default = Modifier
    modifier: Modifier = Modifier,
    // 4. Trailing lambda content slot (if any)
    content: @Composable () -> Unit = {},
) { }
```

### Signature Rules

- `modifier: Modifier = Modifier` is **mandatory** on every public/internal composable **except** screen-level composables (`XxxScreen`) and their stateful wrappers (`XxxRoute` / `XxxScreenStateful`). These top-level composables are never placed inside other layouts, so modifier is unnecessary. Everything below them must have it. It is always the last non-trailing-lambda parameter. Default is always `Modifier` (not `Modifier.something()`).
- Apply the received `modifier` to the **root** layout element only. Never apply it to a child.
- Use `@Composable () -> Unit` slots instead of passing complex data when the caller should control layout (slot-based API).
- Keep parameter count to 6 or fewer (Detekt threshold). If exceeding, extract a state data class.
- Use descriptive lambda names: `onItemClick`, `onDismissRequest`, `onValueChange` — not `onClick` if ambiguous.
- Navigation lambdas and action lambdas should be separate parameters, not bundled into a single callback.

---

## 3. Recomposition & Performance

### Stability

- Only pass **stable** types to composables: primitives, `String`, `@Immutable` / `@Stable` annotated classes, `kotlinx.collections.immutable` persistent collections.
- Never pass `List<T>`, `Map<K,V>`, or `Set<T>` from `kotlin.collections` directly — use `ImmutableList<T>`, `PersistentList<T>`, etc.
- Annotate UI state classes with `@Immutable` or `@Stable` as appropriate.
- Never pass `ViewModel`, `Repository`, or any unstable object as a composable parameter.

```kotlin
// ✅ Stable state class
@Immutable
data class ProfileUiState(
    val name: String,
    val avatarUrl: String,
    val tags: ImmutableList<String>,
)
```

### Avoid Unnecessary Recomposition

- Wrap expensive computations in `remember(key) { }` with proper keys.
- Use `derivedStateOf {}` when a state value is computed from other state and changes less frequently than its inputs.
- Use `key(stableId)` inside `LazyColumn` / `LazyRow` items to preserve state across reorders.
- Never create lambdas or objects inside a composable body without `remember` — they cause child recomposition on every pass.
- Use `Modifier.clickable` with a remembered lambda or a method reference, not an inline lambda that captures changing state.

```kotlin
// ❌ New lambda every recomposition
items.forEach { item ->
    ItemCard(onClick = { viewModel.onItemClick(item.id) })
}

// ✅ Stable reference
items.forEach { item ->
    val onClick = remember(item.id) { { viewModel.onItemClick(item.id) } }
    ItemCard(onClick = onClick)
}
```

### Lazy Lists

- Always provide `key` for `LazyColumn` / `LazyRow` items.
- Always provide `contentType` when items are heterogeneous.
- Use `LazyListState` hoisted outside the composable for scroll control.
- Never use `items(list.size) { index -> ... }` — use `items(items = list, key = { it.id }) { }`.

---

## 4. Theming & Design System

- **Never hardcode** colors, text sizes, shapes, or padding values.
- Always use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, and `MaterialTheme.shapes`.
- Define custom theme tokens in a central theme file if the design system extends Material.
- Use `Dp` from a dimension system or constants file, not magic numbers.

```kotlin
// ❌ Hardcoded
Text(text = title, fontSize = 18.sp, color = Color(0xFF333333))

// ✅ Themed
Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
```

### Spacing & Dimensions

- Define spacing tokens: `object Spacing { val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp; val xl = 32.dp }` or use a CompositionLocal.
- Use `Arrangement.spacedBy()` in `Row` / `Column` instead of manual `Spacer` elements where possible.
- Use `Modifier.padding()` with theme tokens, never raw literals like `16.dp` scattered everywhere.

---

## 5. String Resources & Localization

- **Never hardcode user-facing strings.** Use `stringResource(R.string.xxx)`.
- Use `pluralStringResource` for quantity strings.
- Use string formatting with arguments: `stringResource(R.string.greeting, userName)`.
- Content descriptions for icons and images must always use string resources.
- Non-user-facing strings (log tags, test data) are exempt.

```kotlin
// ❌
Text(text = "No results found")

// ✅
Text(text = stringResource(R.string.no_results_found))
```

---

## 6. Composable Structure & Decomposition

### When to Extract a Composable

- Extract when a block is **reused** in multiple places.
- Extract when a block has **its own logical meaning** (e.g., `UserAvatar`, `PriceTag`).
- Extract when the parent composable exceeds ~80-100 lines.
- Do **NOT** extract every 10 lines into a composable for the sake of it — unnecessary decomposition hurts readability and adds overhead.

### Naming

- Composable functions are `PascalCase` (they act like UI components).
- Name should describe **what** it is, not what it does: `UserProfileCard`, not `RenderUserProfile`.
- Screen-level composables: `XxxScreen` (e.g., `LoginScreen`).
- Stateful wrappers: `XxxRoute` or `XxxScreenStateful`.

### File Organization

- One screen composable per file.
- Private helper composables live in the same file below the main composable.
- Shared components go in a `components/` or `ui/common/` package.
- Preview functions go at the bottom of the file.

---

## 7. Side Effects

Use the correct side-effect handler for each scenario:

| Scenario | API |
|----------|-----|
| Run once on first composition | `LaunchedEffect(Unit)` |
| Run when key changes | `LaunchedEffect(key)` |
| Fire-and-forget (no suspend) | `SideEffect` |
| Cleanup on dispose | `DisposableEffect(key)` |
| Hold a value without triggering recomposition | `rememberUpdatedState(value)` |
| Create a coroutine scope tied to composition | `rememberCoroutineScope()` |

### Side Effect Rules

- Never launch coroutines with `GlobalScope` or `CoroutineScope` created inside a composable.
- Never call `suspend` functions directly inside composable body — use `LaunchedEffect`.
- Be careful with `LaunchedEffect(Unit)` — it only runs once. If you need re-triggering, use a proper key.
- Use `snapshotFlow { }` to convert Compose state into a `Flow` inside `LaunchedEffect`.
- Avoid side effects in the composable body — they make the function impure and hard to reason about.

---

## 8. Navigation Integration

- Screen composables receive **only** primitives, state objects, and event lambdas — never `NavController`.
- Pass navigation actions as lambdas: `onNavigateToDetail: (String) -> Unit`.
- The navigation graph (or a Route-level composable) is responsible for calling `navController.navigate(...)`.

```kotlin
// ❌ Screen knows about navigation
@Composable
fun ProfileScreen(navController: NavController) {
    Button(onClick = { navController.navigate("settings") })
}

// ✅ Screen is navigation-agnostic
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onSettingsClick)
}
```

- **Navigation Handling**: When a composable has too many parameters (usually above 6), prefer defining an `interface` for navigation/action callbacks rather than a
  `data class` of lambdas.
    - *Bad*: `data class Navigation(val onBack: () -> Unit)`
    - *Good*: `interface Navigation { fun onBack() }`

---

## 9. Preview Annotations

Every public/internal composable MUST have at least one `@Preview`:

```kotlin
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun LoginFormPreview() {
    AppTheme {
        LoginForm(
            email = "user@example.com",
            password = "********",
            isLoading = false,
            onEmailChange = {},
            onPasswordChange = {},
            onLoginClick = {},
        )
    }
}
```

### Preview Rules

- Wrap previews in your app's theme composable.
- Provide at least light + dark mode previews.
- Consider font scale preview: `@Preview(fontScale = 1.5f)`.
- Preview functions are `private` and named `XxxPreview`.
- Use realistic but static data — never call a ViewModel or repository.
- For screens with many states, create multiple previews: `XxxLoadingPreview`, `XxxErrorPreview`, `XxxContentPreview`.
- Use `@PreviewParameter` with a `PreviewParameterProvider` for combinatorial previews.

---

## 10. Error Handling & Loading States

- Screens MUST handle loading, content, error, and empty states.
- Prefer a sealed interface for UI state:

```kotlin
@Immutable
sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Content(
        val name: String,
        val email: String,
    ) : ProfileUiState
    data class Error(
        val message: UiText,
    ) : ProfileUiState
}
```

- Use `UiText` (sealed class wrapping `StringResource` / `DynamicString`) instead of raw strings for error messages — this keeps the ViewModel free of Android context.
- Show appropriate UI for each state — don't just show a blank screen on error.

---

## 11. Proper UI Elements

Choose the right composable for the job:

| Need | Use | Not |
|------|-----|-----|
| Clickable card | `Card` + `Modifier.clickable` | `Box` with manual elevation |
| Text input | `OutlinedTextField` / `TextField` | `BasicTextField` unless custom design |
| Top bar | `TopAppBar` / `MediumTopAppBar` | Custom `Row` with manual styling |
| Bottom navigation | `NavigationBar` + `NavigationBarItem` | Custom `Row` of icons |
| Lists | `LazyColumn` / `LazyRow` | `Column` with `forEach` (for large lists) |
| Pull-to-refresh | `PullToRefreshBox` | Custom scroll detection |
| Dialogs | `AlertDialog` / `Dialog` | Overlay `Box` |
| Loading | `CircularProgressIndicator` / `LinearProgressIndicator` | Custom Canvas spinner (unless custom design) |
| Scaffold layout | `Scaffold` | Manual `Box` stacking |

- Use `Scaffold` for screens with top bar, bottom bar, FAB, or snackbar.
- Use `SnackbarHostState` with `Scaffold` for snackbars — never use `Toast` from a composable.
- Use `Surface` as a base container when you need elevation, shape, and color from the theme.
- Never introduce deprecated Compose or Material components when a supported replacement exists. If a dependency or platform constraint leaves no viable alternative, keep the deprecated usage isolated and call it out explicitly in the review.

---

## 12. Modifier Best Practices

- Chain order matters: `padding` before `background` means padding is inside the background, after means outside.
- Always set `modifier` on the root element.
- Use `Modifier.fillMaxWidth()` / `fillMaxSize()` intentionally, not by default.
- Avoid `Modifier.wrapContentSize()` unless needed for alignment within a larger container.
- Use `Modifier.weight()` inside `Row` / `Column` for proportional sizing.
- Use `Modifier.testTag("xxx")` for UI testing — put tags on interactive and assertable elements.

---

## 13. ViewModel Integration

- ViewModels expose `StateFlow<UiState>`, not `LiveData`.
- Use a single `uiState` flow per screen when possible — avoid multiple independent state flows that the UI must combine.
- Collect state with `collectAsStateWithLifecycle()`.
- ViewModel events (one-shot): use `Channel` → `Flow` consumed in a `LaunchedEffect`, or use `SnackbarHostState` for messages.
- Never pass `Context`, `Activity`, or Android framework objects into a ViewModel.
