# Seed-Growth Flowing Pattern Algorithm

## Context
Implementing a procedurally-generated seed-growth pattern where lines grow with curves, collide with existing geometry, and create a nested topology. Each line spawn point must lie on the "outer perimeter" (canvas edges + existing line boundaries), creating an organic, layered appearance.

## Algorithm Overview

### Core Concept
- **One seed spawns per iteration** (configurable density multiplier)
- **Seed location**: Random point on the "outer perimeter"
  - Initially: canvas edges only
  - After each line: outer boundary becomes part of perimeter
- **Line growth**: Procedural curves + collision-based termination
- **Topology**: Creates clear inside/outside regions; new seeds only spawn on outer edge

### Line Growth Mechanics

Each line:
1. Starts from a random perimeter point with randomized initial direction
2. Grows step-by-step with:
   - **Procedural curves**: Perlin noise + sine wave variations for organic flow
   - **Step size**: Canvas size / 10 (ensures ≥10 segments fit on canvas)
   - **Max length**: Random within configurable bounds (e.g., 0.3–0.7 of canvas diagonal)
3. Terminates when:
   - **Collision detected**: Stops exactly at intersection point with existing line
   - **Canvas edge reached**: Stops at boundary
   - **Max length approaching**: Line navigates toward nearest stopping point (other line or canvas edge) and terminates

### Termination Strategy
As line approaches its max length (~90% progress):
- Identify nearby stopping points (other lines, canvas edges)
- Steer toward nearest stopping point
- Terminate when reached

## Implementation Plan

### Files to Modify/Create

1. **FlowingLinePattern.kt** (existing, to be completed)
   - Main pattern generator
   - Orchestrates seed spawning and line growth

2. **LineGrower.kt** (new utility class)
   - Handles individual line growth
   - Procedural curve generation (Perlin + sine)
   - Step-by-step segment generation with collision checking
   - Termination logic (max length navigation)

3. **PerimeterTracker.kt** (new utility class)
   - Maintains list of valid spawn edges
   - Tracks canvas edge segments
   - Tracks line segment outer boundaries
   - Provides random perimeter point selection

4. **PatternGenerator.kt** (update if needed)
   - Integration point; may need minor tweaks

### Key Data Structures

```kotlin
// Line segment
data class LineSegment(val start: PointF, val end: PointF)

// Perimeter edge (could be canvas edge or line segment)
data class PerimeterEdge(val start: PointF, val end: PointF, val isCanvasEdge: Boolean)

// Growing line state
data class GrowingLine(
    val segments: MutableList<LineSegment>,
    val maxLength: Float,
    val currentLength: Float,
    val targetExitPoint: PointF?,
    val proceduralCurvePhase: Float
)
```

### Algorithm Flow (Pseudocode)

```
Initialize:
  - Set perimeter = canvas edges
  - Set allLines = []
  - Set density = 1 (seeds per iteration)

Loop (per frame or batch):
  For each seed (count = density):
    1. Pick random point on perimeter
    2. Create new line with:
       - Random initial direction
       - Random max length (0.3–0.7 × canvas diagonal)
       - Perlin noise seed
    3. Grow line step-by-step:
       Loop until terminated:
         a. Calculate next segment:
            - Apply Perlin noise (directional steering)
            - Apply sine modulation (perpendicular oscillation)
            - Generate segment from current position
         b. Check collision with all existing lines
            - If collision: terminate at intersection point
         c. Check canvas boundary crossing
            - If crossing: terminate at boundary
         d. Check max length approach (~90%):
            - If approaching: identify nearest exit (line or edge)
            - Steer toward exit point
         e. Add segment to line
         f. Update currentLength
    4. Add completed line to allLines
    5. Update perimeter with line's outer boundary
```

### Critical Implementation Details

#### 1. Procedural Curve Generation
- **Perlin Noise**: Smooth directional steering over line length
- **Sine Modulation**: Perpendicular oscillations for organic flow
- Combine: `direction = baseDirection + perlinNoise(step) + sin(step * frequency) * amplitude`

#### 2. Perimeter Tracking
- Canvas edges: 4 segments (top, bottom, left, right)
- Line outer boundary: The line segments themselves (not interior points)
- Perimeter points: Uniform random sampling along all edges
- **Key**: Only outer edges spawn new seeds (prevents interior filling)

#### 3. Collision Detection
- Use existing `IntersectionDetector` (returns fraction along segment)
- Check growing segment against all existing line segments
- Terminate exactly at intersection point (not past it)

#### 4. Max Length Navigation
- When line reaches ~90% of max length:
  - Scan nearby lines and canvas edges
  - Pick closest exit point
  - Adjust steering to head toward it
  - Terminate when reached

#### 5. Configurable Parameters
- `seedCount`: Density multiplier (1 = one seed per iteration; 2+ = distributed around perimeter edge)
- `maxLineLength`: Range (min–max) as fraction of canvas diagonal
- `stepSize`: Canvas size / 10 (or configurable divisor)
- `coverage`: "elegant" (sparse, nested) or "dense" (fill canvas)
- `curveIntensity`: Perlin noise amplitude + sine frequency

## Verification

Test by:
1. Single seed run → line grows, collides, terminates
2. Multiple iterations → perimeter grows, new seeds spawn on boundary
3. Visual inspection → no seeds spawn inside existing shapes
4. Density test → seedCount multiplier distributes seeds around perimeter
5. Termination accuracy → lines stop exactly at collisions, not overshooting

## Notes

- The "outer perimeter" concept is critical: it enforces topology and creates the layered, elegant appearance
- Collision detection uses `IntersectionDetector.getIntersectionFraction()` from existing code
- Perimeter tracking should be efficient; consider spatial partitioning if canvas is large
- Procedural curves (Perlin + sine) allow variations while maintaining organic flow
