# XegPaper

Native Android live wallpaper (Kotlin + Jetpack Compose). See [README.md](README.md)
for the build/install steps, the module layout, and how to add a pattern.

## Things worth knowing before changing code

- The drawing logic in `engine/` must stay free of `WallpaperService` and Compose
  types. The wallpaper service and the in-app preview both drive the same
  `PatternEngine`, and that is what keeps the preview honest.
- A growing line has to stop *exactly* where it crosses an existing one, so
  `IntersectionDetector` returns the fraction along the growing segment rather than a
  boolean, and `GrowingLinePattern` caps how far the head can move in one step.
- Segments that share a vertex are not crossings. That is handled by exact float
  comparison against the growing segment's start point — start coordinates are copied
  verbatim from the head, so the comparison is safe. Do not "improve" it into a
  tolerance check without thinking through polyline turns and radial hubs.
- Settings live in one DataStore read by both the activity and the wallpaper service,
  which run in the same process. That is why live updates need no IPC.
- Builds need JDK 17+; the default `java` on this machine is 8. Set `JAVA_HOME` to
  Android Studio's JBR.
- AndroidX versions are pinned to the AGP 8.x / compileSdk 36 generation. Newer
  releases demand AGP 9.x and compileSdk 37; bumping them means bumping all three.
- A **scythe** is half of a crescent moon. A crescent (see `ScytheLinePattern`) is two
  circular arcs — an outer one and a shallower inner one — meeting at two sharp "horn"
  points. Cut it through the midpoint of each arc and keep one half: it keeps exactly
  one horn as a sharp tip, and its other end is left open as two "base" points where
  the cut was made, instead of closing into a second horn. `ScytheGeometry` builds this
  shape; `ScytheBubblePattern` sprouts scythes as thorns off a growing bubble curve,
  and `MultiScythePattern` chains them base-to-base into a zigzag.
