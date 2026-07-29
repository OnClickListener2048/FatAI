# Repository Guidelines

## Project Structure & Module Organization

FatAI is a Kotlin Multiplatform Compose application targeting Android, desktop JVM, and iOS. `composeApp/` owns UI, navigation, and platform application setup. Business capabilities are split into feature modules: `feature-chat/`, `feature-model/`, `feature-prompt/`, `feature-memory/`, `feature-files/`, `feature-workspace/`, `feature-settings/`, `feature-user/`, and `feature-tools/`. Shared primitives live in `core/`; SQLDelight schemas and drivers live in `database/`; Koin bootstrap is in `shared/`; `server/` is a sample Ktor service. Kotlin sources use `src/commonMain`, with platform code in `androidMain`, `jvmMain`, and `iosMain`.

## Build, Test, and Development Commands

Use JDK 17 and the Gradle wrapper.

```powershell
.\gradlew.bat :composeApp:run                 # Run the desktop app
.\gradlew.bat :composeApp:assembleDebug       # Build Android debug APK
.\gradlew.bat :composeApp:compileKotlinJvm    # Fast desktop compilation check
.\gradlew.bat :composeApp:compileDebugKotlinAndroid
.\gradlew.bat test                            # Run available tests
.\gradlew.bat detekt                          # Run static analysis
```

Run a focused module check while editing it, for example `:feature-model:compileKotlinJvm`. The project uses Gradle configuration cache; avoid changing build scripts unnecessarily during feature work.

## Coding Style & Naming Conventions

Follow Kotlin official style: four-space indentation, no wildcard imports, PascalCase types, camelCase functions/properties, and `UPPER_SNAKE_CASE` constants. Keep common code platform-neutral; use `expect`/`actual` only at platform boundaries. Prefer feature-local APIs and depend on `core` abstractions rather than reaching across feature modules. Run Detekt before submitting; its configuration is at `config/detekt/detekt.yml`.

## Testing Guidelines

Place cross-platform tests in `src/commonTest/kotlin` and platform-specific tests in the matching test source set. Use Kotlin Test and name tests by behavior, such as `streamEmitsDoneChunk`. Tests are limited in this repository, so compilation checks are the minimum verification for implementation changes.

## Commit & Pull Request Guidelines

Use concise Conventional Commit-style messages already present in history, e.g. `feat: add provider-aware tool framework` or `fix: limit message copy controls`. Keep commits scoped to one concern. PRs should explain behavior changes, list verification commands, link relevant issues, and include screenshots for Compose UI changes. Do not commit API keys, `local.properties`, generated build outputs, or local databases.
