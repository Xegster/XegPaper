# XegPaper — Geometric Live Wallpaper (Android)

## Context

The user wants a new Android app that installs as a selectable **live wallpaper**: a blank canvas that animates lines being drawn along geometric patterns, stopping wherever they intersect each other. It must appear in Android's system wallpaper picker, and must also be launchable as a normal app to configure settings (colors, speed, pattern type, etc.). Future iterations will add multiple simultaneous start points and touch interaction, once pattern references (a video) are provided — this plan only covers the foundational engine + one or two initial patterns + settings.

Investigation of the sibling projects (NetXEG Manager, Pokemon Go Account Manager) showed they are **Expo/React Native** apps (Zustand, not Pinia), with a separate standalone Vue3+Pinia+Vite web app (NetXegLive) that only talks to the mobile app via Firestore — there is no existing "Vue in a WebView inside a native app" pattern to reuse. Critically, a live wallpaper **must** be implemented as a native Android `WallpaperService`, which Expo/React Native and WebViews cannot provide. The user confirmed (via question) to build this as a **fully native Kotlin app** — no Vue/Pinia/Vite, no React Native — using **Jetpack Compose** for the settings UI and **Canvas 2D** for rendering. Package/app ID: `com.xegster.xegpaper`.

Since the sibling apps are Expo-generated, they have no hardcoded/reusable SDK or Gradle conventions (values are injected dynamically by Expo's plugin). This project will use current standard native-Android defaults instead, matching the Gradle wrapper version already seen in use (8.14.x).

## Architecture

Single Gradle module app, standard native Android layout:

- **`app/src/main/java/com/xegster/xegpaper/`**
  - `wallpaper/XegPaperWallpaperService.kt` — extends `WallpaperService`; its `Engine` inner class owns the animation loop, draws to the wallpaper `SurfaceHolder` on a background thread (Handler + `postDelayed` loop, ~30-60fps), pauses when `onVisibilityChanged(false)` or offscreen.
  - `engine/` — pure Kotlin drawing/pattern logic, decoupled from `WallpaperService` so it can be reused for the in-app live preview:
    - `PatternGenerator.kt` (interface) + concrete implementations, e.g. `GeometricLinePattern.kt` — produces line segments over time, following a geometric rule (start with something simple like random-walk connected line segments, or a Lissajous/spiral line path — final pattern shapes to be replaced once the user provides their reference video).
    - `LineSegment.kt`, `DrawState.kt` — data classes for in-progress line paths.
    - `IntersectionDetector.kt` — segment-intersection test (standard line-segment intersection formula) used to stop a growing line the moment it crosses any previously drawn segment.
    - `WallpaperRenderer.kt` — takes a `Canvas` + `DrawState`, paints background + all segments (used by both the wallpaper Engine and the Compose preview).
  - `settings/` — Compose settings screen:
    - `MainActivity.kt` — launcher activity, hosts the Compose UI, includes a live `WallpaperRenderer`-backed preview (drawn via a Compose `Canvas`), and a button that fires the `ACTION_CHANGE_LIVE_WALLPAPER` intent targeted at this service so the user can set it directly from in-app.
    - `SettingsScreen.kt`, `SettingsViewModel.kt` — controls for line color, animation speed, pattern type, background color; persisted via `SettingsRepository.kt` (Jetpack `DataStore` preferences, not SharedPreferences).
  - `di/` or simple manual wiring — keep this small; no need for Hilt/Koin at this scope.
- **`app/src/main/res/xml/wallpaper_info.xml`** — required `<wallpaper>` descriptor (referenced from the service's manifest `<meta-data>`) pointing at a settings `Activity` (reuse `MainActivity` or a small `WallpaperSettingsActivity` alias) so Android's wallpaper picker shows a "Settings" affordance.
- **`AndroidManifest.xml`** — declares `MainActivity` (LAUNCHER), and `XegPaperWallpaperService` with `android.permission.BIND_WALLPAPER`, intent-filter for `android.service.wallpaper.WallpaperService`, and `meta-data` pointing to `wallpaper_info.xml`.
- Settings changes in `MainActivity` write to the shared `DataStore`; the running wallpaper `Engine` observes the same DataStore (via a `CoroutineScope` tied to the Engine's lifecycle) and re-reads config live — no bridge/IPC needed since both run in the same app process/UID.

## Key implementation notes

- **Animation loop**: classic wallpaper pattern — Handler-based draw loop in the Engine, locking the `SurfaceHolder` canvas each frame, unlocking/posting. Stop the loop in `onVisibilityChanged(false)`/`onSurfaceDestroyed` to save battery.
- **Intersection-stop behavior**: each actively-growing line checks new segment extensions against all finalized segments (and itself) via `IntersectionDetector`; on hit, that line is marked finished and (per pattern logic) a new line may start elsewhere.
- **Pattern extensibility**: `PatternGenerator` interface designed now so additional geometric patterns (and later, multiple simultaneous start points / touch-driven start points) can be added without reworking the renderer or wallpaper service.
- **Touch input (future, not this pass)**: `WallpaperService.Engine.onTouchEvent` is already the standard hook — just leave a documented stub/TODO, no need to implement yet.

## Project scaffolding

- Standard Android Studio native project: `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts`.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26` (live wallpapers work fine from API 26+; adjust down only if user wants wider reach later).
- Kotlin (current stable, 2.x) + Jetpack Compose BOM (current stable) + `androidx.datastore:datastore-preferences`.
- Gradle wrapper pinned to `8.14.3` to match the wrapper version already used by sibling projects.
- `applicationId = "com.xegster.xegpaper"`.
- Debug keystore: generate a new debug keystore checked into the repo (matching the sibling apps' convention of committing `debug.keystore` for local dev builds); no release signing config yet.
- Root docs: `README.md` (build/run/install-as-wallpaper instructions), `CLAUDE.md` (brief project pointer, mirroring sibling repos' convention).

## Verification

1. `./gradlew assembleDebug` builds successfully.
2. Install on a device/emulator (`./gradlew installDebug` or via Android Studio run), launch the app — confirm the Compose settings screen renders with a live animated preview of the line pattern.
3. Tap "Set as wallpaper" (or manually: long-press home screen → Wallpapers → Live Wallpapers → XegPaper) — confirm it appears in the system picker with working "Settings" affordance, applies to the home screen, and animates.
4. Change a setting (color/speed) in-app and confirm the live wallpaper updates without needing to reapply it.
5. Visually confirm: lines animate drawing progressively, and a growing line stops exactly where it intersects an existing line.
