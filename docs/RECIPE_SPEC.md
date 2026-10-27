# Recipe spec

The contract a sim is written against. Everything in this file is enforced by
code; everything not in this file is not available to a sim.

There are two ways to write a sim. A **Kotlin sim** implements `Sim` and is
compiled into the app. A **recipe** is a Lua file the app loads at runtime from
`filesDir/recipes`, and can be pasted in from a chat, a note or an email. Both
end up as a `SimDef` in the registry and are indistinguishable from the outside.

## The Kotlin surface

```kotlin
interface Sim {
    fun setup(env: SimEnv)
    fun step(env: SimEnv, dt: Float)
    fun draw(env: SimEnv, g: G, alpha: Float)

    fun onInput(env: SimEnv, e: TouchEvent) {}
    fun payoff(): Boolean = false
    fun finished(): Boolean = false
    fun counters(): List<Pair<String, String>> = emptyList()
}
```

| Call | When | Rules |
| --- | --- | --- |
| `setup` | Once per run, and again on every restart | Allocate everything here. Read every parameter here. |
| `step` | 120 times a simulated second, with a fixed `dt` | No allocation. No wall clock. No `kotlin.random`. `dt` can be negative when time runs backwards. |
| `draw` | Once per frame, after the steps | No allocation. `alpha` is 0..1 between the last two steps, for interpolation. |
| `payoff` | Every step | Return true on exactly the frame the moment lands. The engine answers with slow motion, a punch-in, a sweep and a flash. |
| `finished` | Every step | True ends the run; the feed advances. |
| `onInput` | Between frames | Never during `step`. Touches are queued on the UI thread and drained on the sim thread. |
| `counters` | Whenever the HUD updates | Cheap strings only. |

### SimEnv

```kotlin
class SimEnv(val rng: Rng, var laws: Laws, var params: ParamValues, val events: EventQueue) {
    var time: Float            // simulated seconds; can run backwards
    var aspect: Float          // width / height of the view
    var palette: Palette       // 512-entry colour ramp, at(0f..1f)
    var reduceMotion: Boolean   // honour it: no shake, no punch-in
    val halfWidth: Float        // x extent; y is always -1..1
    fun emit(kind: EventKind, value: Float, pan: Float = 0f, strength: Float = 1f)
}
```

`emit` is the only way to make a sound, a haptic or impact juice. `value` picks
the note inside the scale (0..1), `pan` is -1..1, `strength` is 0..1 and decides
loudness, the size of the flash and whether the motor ticks.

| EventKind | Means | Sound | Haptic |
| --- | --- | --- | --- |
| `COMPARE` | A read, a test, a look | short tick | no |
| `SWAP` | Two things exchanged | two-note blip | no |
| `WRITE` | A value stored | pluck | no |
| `HIT` | A collision | body-dependent thud | yes |
| `CROSS` | A threshold passed, an alignment | bell | yes |
| `BIRTH` | Something created | rising note | no |
| `DEATH` | Something destroyed | falling note | yes |
| `STEP` | Texture: a generic tick | soft click | no |
| `DONE` | The payoff | chord and sweep | heavy |

A sim may emit thousands of events a second. The synth rate-limits and merges
them, and the haptics have their own ceiling, so emitting freely is correct —
the engine decides what is heard and felt.

### The draw list

`G` is flat arrays; a sim never touches OpenGL.

```kotlin
g.rect(cx, cy, hw, hh, color, rot, alpha)
g.bar(cx, baseY, halfWidth, height, color, alpha)
g.disc(cx, cy, r, color, alpha)
g.ring(cx, cy, r, thickness, color, alpha)
g.glow(cx, cy, r, color, alpha)
g.line(x1, y1, x2, y2, width, color, alpha)
g.polyline(xs, ys, count, width, color, alpha)
g.point(x, y, sizePixels, color)      // the huge-count path
g.reserveInstances(n) / g.reservePoints(n)
g.flash(strength)                      // the safety limiter may reduce it
```

World coordinates: y is up, y runs -1..1, x is widened by the aspect ratio
(`env.halfWidth`). Colours are ARGB ints, normally from `env.palette.at(t)`.

Above a few thousand objects, use `g.point`: discs and rects are instanced
quads, points are one vertex each. Every sim that reaches five figures does
this.

### Declaring a sim

```kotlin
SimDef(
    id = "shelf/name",            // stable; it is what a replay link stores
    title = "What it is called",
    shelf = Shelf.PHYSICS,
    family = "Pendulums & oscillators",
    blurb = "One line, plain words.",
    formats = listOf(TrapFormat.LOOP, TrapFormat.SNAP),
    usesLaws = true,              // shows the Laws card
    heroLabel = "Drop it",
    heroRestarts = false,
    typicalSeconds = 20f,         // how long a run takes; the feed uses it
    params = listOf(...),
    macros = listOf(...),         // optional; derived if absent
    create = { MySim() }
)
```

### Parameters

```kotlin
Param.int(key, label, min, max, default, card, advancedOnly, unit, logScale)
Param.float(key, label, min, max, default, card, advancedOnly, unit, logScale)
Param.bool(key, label, default, card, advancedOnly)
Param.choice(key, label, choices, default, card, advancedOnly, input)
Param.expr(key, label, default, card, advancedOnly, unit)
```

`card` is `DATA`, `MOTION`, `LOOK`, `SOUND` or `LAWS`. Data-card values shape
what `setup` builds, so they apply on the next run; everything else applies
live. `advancedOnly` hides a value from Simple mode. Any numeric field in
Advanced mode also accepts a formula in `t`.

A **macro** is one Simple-mode slider that moves several parameters:

```kotlin
Macro("chaos", "Chaos", Card.MOTION, mapOf("messiness" to 0f..1f, "effort" to 0.4f..3f), 0.6f)
```

Every sim has at least one. Where a sim declares none, `Macros.forDef` derives
"How much" from its largest count and "Energy" from its motion values, so
Simple mode is never just Advanced mode with things removed.

## The Lua surface

A recipe is a Lua file with four well-known globals: a `meta` table, a `params`
table, and the functions `setup`, `step`, `draw` and optionally `payoff` and
`on_input`. The engine's own surface is installed as plain global functions, so
a recipe reads like a script rather than like an API.

The sandbox has no `io`, `os`, `require`, `package`, `load`, `loadstring`,
`dofile`, `loadfile` or `collectgarbage`, and each call has a time budget; a
recipe that overruns it repeatedly is stopped rather than allowed to freeze the
frame.

```lua
meta = {
  id = "orbit", title = "Two-body orbit", shelf = "physics",
  family = "Orbits, gravity & space",
  blurb = "A planet falling around a star, written in twenty lines.",
  hero = "New orbit", palette = "Ember", sound = "Music box",
  seconds = 26, laws = true,
  formats = { "loop", "song" }
}

params = {
  { key = "speed", label = "Launch speed", type = "float",
    min = 0.3, max = 2.0, default = 1.05, card = "motion" },
  { key = "trail", label = "Trail", type = "int",
    min = 50, max = 4000, default = 900, card = "look" }
}

function setup(s)            -- s is your state table; it survives until restart
  s.x = 0.5; s.y = 0
end

function step(s, dt)         -- dt is fixed, and negative when time runs backwards
  s.x = s.x + dt * param("speed")
  emit("cross", 0.5, s.x, 0.8)
end

function payoff(s) return s.laps == 3 end

function draw(s, alpha)
  glow(0, 0, 0.22, palette(0.9), 0.6)
  disc(s.x, s.y, 0.03, palette(0.4))
end

function on_input(s, kind, x, y) end   -- kind: "tap", "drag", "long_press", ...
```

### Globals a recipe may call

| Call | Gives |
| --- | --- |
| `param(key, fallback)` | A numeric parameter |
| `choice(key)` | The selected option of a choice parameter, as a string |
| `rng()` / `rng(hi)` / `rng(lo, hi)` | Seeded random float |
| `rng_int(lo, hi)` | Seeded random int, `hi` exclusive |
| `aspect()` | Half the visible width; y is always -1..1 |
| `sim_time()` | Simulated seconds |
| `law(name)` | `gravity`, `restitution`/`bounciness`, `friction`, `time_dir`, `time_scale`, `falloff`, `g_constant`, `mass_sign`, `light_speed`, `sticky`, `pass_through`, `electric`, `wrap` |
| `emit(kind, value, pan, strength)` | A sound, a haptic and impact juice |
| `rect`, `bar`, `disc`, `ring`, `glow`, `line`, `point` | Draw |
| `palette(t)` | A colour from the run's palette, `t` in 0..1 |
| `hsv(h, s, v)` | A colour of your own |
| `flash(strength)` | Ask for a flash; the limiter decides |

Parameter `type` is `int`, `float`, `bool` or `choice`; `card` is `data`,
`motion`, `look`, `sound` or `laws`; `advanced = true` hides it from Simple
mode. `meta.formats` are the trap-format names in lower case.

Three recipes ship with the app — `orbit`, `wave` and `rain` — written out on
first launch, so there is always a working example to read. A recipe that does
not parse is still saved, and shows with a warning in the editor rather than
disappearing.

## What a sim may not do

- Touch OpenGL, Android, the file system, the network or the clock.
- Use `kotlin.random`, `System.nanoTime` or anything else outside `env`.
- Allocate in `step` or `draw`.
- Block. A sim that needs more than a frame's worth of work should spread it
  across steps, the way the sorting state machines do.
- Opt out of the safety limits. A sim asks for a flash; the limiter decides.
