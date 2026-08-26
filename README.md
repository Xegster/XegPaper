# XegPaper

A native Android **live wallpaper**: a blank canvas where geometric lines draw
themselves across the screen and stop dead wherever they cross a line that is already
there. It ships as a normal launcher app too, so the pattern, colours and speed can be
configured from a Compose settings screen with a live preview.

- Application ID: `com.xegster.xegpaper`
- `minSdk 26`, `targetSdk 36`, `compileSdk 36`
- Kotlin + Jetpack Compose (settings UI) + `android.graphics.Canvas` (rendering)
- Settings persisted with Jetpack DataStore

## Build

Needs JDK 17+. Android Studio's bundled JBR works:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. `app/debug.keystore` is
committed so debug builds are reproducible across machines, matching the convention in
the sibling projects. There is no release signing config yet.

## Install and run

```bash
./gradlew installDebug
```

Then either:

1. Launch **XegPaper** from the app drawer and tap **Set as wallpaper** — this opens the
   system picker already showing XegPaper's preview; or
2. Long-press the home screen → **Wallpapers** → **Live Wallpapers** → **XegPaper**.

The picker's **Settings** affordance opens the same settings screen (via the
`WallpaperSettingsActivity` alias declared in `res/xml/wallpaper_info.xml`).

Settings are written to a DataStore that the running wallpaper reads live, so changing
a colour or the speed updates the wallpaper immediately — no need to re-apply it.

## Settings

| Setting | What it does |
| --- | --- |
| Pattern | **Angular walk** — one line walks the screen turning on a 45° grid. **Radial burst** — spokes fire one at a time from a moving anchor. |
| Line colour / Background colour | Preset swatches. Changing the line colour recolours what is already drawn. |
| Speed | How fast the line head travels, in dp/s. |
| Line thickness | Stroke width in dp. |
| Density before restart | How many segments accumulate before the canvas fades out and starts over. |

## Layout

```
app/src/main/java/com/xegster/xegpaper/
  engine/                       pure drawing logic, no wallpaper/Compose coupling
    WallpaperConfig.kt          the user-tunable settings
    DrawState.kt                surface metrics + everything drawn so far
    LineSegment.kt              one straight piece of a line
    IntersectionDetector.kt     segment crossing + distance maths
    PatternGenerator.kt         the pattern interface, PatternType, factory
    GrowingLinePattern.kt       shared "grow a head until it hits something" base
    GeometricLinePattern.kt     ANGULAR pattern
    RadialBurstPattern.kt       RADIAL pattern
    WallpaperRenderer.kt        paints a DrawState onto a Canvas
    PatternEngine.kt            surface + frame clock + fade/restart cycle
  wallpaper/
    XegPaperWallpaperService.kt WallpaperService, surface and frame loop
  settings/
    MainActivity.kt             launcher + wallpaper settings activity
    SettingsScreen.kt           Compose UI and the live preview
    SettingsViewModel.kt
    SettingsRepository.kt       DataStore persistence
    XegPaperTheme.kt
```

`PatternEngine` is what both hosts drive: the wallpaper service feeds it a
`SurfaceHolder` canvas, the settings screen feeds it a Compose canvas, and the drawing
is identical in both.

## Adding a pattern

1. Add a case to `PatternType`.
2. Implement `PatternGenerator` — or extend `GrowingLinePattern` and just answer *where
   does the next line start* and *how does it turn*.
3. Wire it into `createPatternGenerator`.

Nothing in the renderer, the wallpaper service or the settings plumbing needs to change;
the settings screen picks up the new entry from `PatternType.entries`.

## Not implemented yet

- **Multiple simultaneous start points.** `GrowingLinePattern` tracks a single head;
  promoting those fields to a list of walkers is the intended route.
- **Touch interaction.** `XegPaperWallpaperService.Engine.onTouchEvent` is stubbed with
  a TODO; enabling it also needs `setTouchEventsEnabled(true)`.

Both are waiting on the reference video for the target pattern shapes.
