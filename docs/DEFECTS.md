# Defects

What audit found and what is left. An item is deleted when it is fixed, not
ticked: the git history is the record, and this file is the baseline the next
audit starts from.

The rule it exists to serve, from AGENTS.md: *a control that does nothing is a
bug, and the menu must not promise what the code does not do.*

## Open

Measured, not yet acted on.

- **Accumulating counts whose top is only reached on a long watch.** Trail,
  dots, points and history sliders on lorenz, harmonograph, n-body, fourier,
  random walk, chaos game and predator–prey draw the same picture over their
  top few positions for the first minute, because a buffer of forty thousand
  points at a few hundred a second takes minutes to fill. Now that sims run for
  as long as you watch, those positions are reached — late. Lowering them would
  take away the long-exposure picture from the person who stays; leaving them
  means a slider whose top does nothing for a while. Kept, and written down.
- **The size slider still offers more than a modest phone can draw.** It is
  not capped, on purpose: tens of thousands is the point. The drawer now says so
  when the frame rate has been under 70% of this phone's own best for three
  seconds — measured on the phone, not predicted from the benchmark. `Bench`
  still only steers TickTock.

## Fixed this round

- **Silent stretches, measured on the real synth at 8-bit.** Gradient descent
  sounded only while the overall best improved — three seconds — then held six
  silent seconds; each walker now lands with its own note and the run ends when
  all have landed, at a learning rate slow enough to watch (12 s, was 12 s with
  half of it silent). Lorenz was silent for up to five seconds while it circled
  one wing; it now plays a quiet note at the top of every loop. Pathfinding's
  route reveal was silent; it sings like the maze's. The prime spiral's held
  ending is 2.5 s, from 4.

- The payoff flash and chime, live and in export; the bell at the end note.
- The vignette, which drew a dark frame around every sim.
- The feed reaching only three sims (a swipe handler holding a stale index),
  and the swipe now following the finger.
- Finished sims replaced the instant they finished; the feed holds the resolve
  and goes again, and the chaos game and Monte Carlo pi never stop.
- The whole drawer drawing on one line, in Simple and Advanced mode.
- The first-run pop-up.
- Hanoi's and n-queens' speed sliders dead below a floor; binary tree's Nodes
  slider dead above twelve hundred; the tops of five speed sliders that
  finished a run in under two seconds; logic gates' Addend above 63; circle
  packing's Largest circle above 0.25; boids' Neighbour range above 0.1.
- Mandelbrot holding still seven frames in eight and then jumping; Voronoi's
  cells lagging their own seeds.
- Pause shown as Play over a running sim after loading, a clip, or Studio.
- Saved settings keeping old defaults on an existing install.
- Binary tree opening every run on a black screen.
- A Studio project lost whenever Android reclaimed the app; and the exporter's
  copy of it merging keyframes closer than 0.3 s, so a clip could be missing a
  key the preview showed.
- Seeds above 2^53 coming back as a different number from a pasted run.
- Three more false "Perfect loop" claims — Mandelbrot, the particle field and
  charges — let through because LoopClaimTest compared only the shape buffer,
  and a sim drawn in points was judged on its point count alone.
- The sound presets other than 8-bit, rendered and measured: Music box 2.3x
  and Ambient pad 2.9x 8-bit's loudness, Marimba three times as bright from a
  white-noise pluck, Ticks a burst of raw hiss. All within a few per cent of
  8-bit's level now, the pluck struck with a soft mallet, the tick filtered.
- TickTock stalling on any clip whose sim never finishes — 31 of 58.
- Studio's "Perfect loop" template set its length from the typical length,
  which is no sim's loop period; it lays out whole measured cycles now, and is
  only offered on a sim that loops.

## What the last two rounds found

### The control audit

The user, running the APK on a phone: *"not all buttons and sliders work."*
Five audits, one per screen group, traced all 131 controls end to end. Three
causes accounted for almost all of it, and each one is worth remembering
because none of them was a broken handler — every handler ran, and every write
landed.

1. **Every per-sim slider sprang back.** Parameter values live in a
   `ConcurrentHashMap` the sim thread reads each step, not in snapshot state,
   and `ParamControl`, `ExactParamRow` and `MacroControl` each displayed one
   without reading any snapshot state of their own. Compose had nothing to tell
   it the row needed redrawing, so the row went on being handed the old number
   and the thumb jumped back under the finger. `uicheck` rule 12 is this class,
   and it reproduces all five instances against the code as it shipped.

2. **Eighty-eight values did nothing at all.** `needsRestart` answered "is it on
   the Data card", and only Data-card values restart the run — but eighty-eight
   Motion and Look values are copied into a field in `setup()` and never looked
   at again. Boids' entire Motion card. The sorting sims' Algorithm chip and
   View. Mandelbrot's Resolution, Detail and Zoom speed. And, because every
   value they move is one of those, the Chaos and Energy macros that Simple
   mode leans on hardest were inert on four sims.

   The fix is not a list, because a list of fifty would rot the first time a sim
   changed. `ParamValues` records which keys are read while a run is stepping —
   thirty frames after each setup, then it stops, so the steady-state cost is
   one volatile read — and a value nothing reads live is applied by rebuilding.
   `ControlEffectTest` is the proof and does not trust that bookkeeping: for
   each such value it builds a fresh run at the bottom of the range, draws a
   frame, builds one at the top, draws again, and compares what would be on
   screen. 88 driven, 87 differ.

3. **Studio was hiding fifteen of its twenty-two controls.** The preview sat in
   a `fillMaxWidth().aspectRatio(0.62f)` box — about 1.6 times the screen width,
   some 620 dp of an 860 dp phone — and the editor column below it had no
   weight, so it was measured against what was left: fifteen dp on a large
   phone, none on a small one.

The eighty-eighth control was the one real find of a different kind: optics
overwrites its Angle on every step while "Sweep the angle" is on, and that
switch ships on, so no rebuild could rescue it. A `Param` can now name the
switch that owns it, and the control goes dim and says so rather than lying.
Song mode over the scale, pitch and range; a typed force law over Force
falloff; `conserveEnergy` over bounce — all the same treatment, which is what
`ValueSlider`, `ChipRow` and `ToggleRow` gained an `enabled` for.

Three wrong measurements from that round, recorded because each looked like a
finding: at 40 frames three trail lengths drew identical frames, because a
trail cannot differ before enough of it exists to draw; testing parameters
cumulatively on one core made the hypercube's second spin plane look dead when
it was the first plane's value carrying over; and the first draft of rule 12
compared the literal argument rather than resolving one level of local
assignment, so it saw a bare `v` and waved all three broken rows through.

Three builds also broke on the same class of mistake — a local declared below
its use, and a name declared twice in one scope — which is now rules 13 and 14.
Rule 14's first draft compared indentation and immediately flagged two
perfectly legal `val shown` in an if/else; it walks braces instead.

The checks that keep the findings closed, all of which reproduce the defect
they were written for: `tools/uicheck.py` rules 5 to 15 (redeclarations, a
payoff fired by the clock alone, a law nothing reads or nothing can set, a
declared `LawKnob` that does not match the code, a named argument the
constructor does not have, a Library mock-up naming a sim that does not exist,
a luaj call not proven to compile, a control showing a value Compose cannot
observe, a local used before its declaration, a name declared twice in one
scope, and `String.format` without a locale), `tools/glslcheck.js` and
`tools/glslpixels.js` (the shaders compiled and their pixels measured in a real
rasteriser), `ControlEffectTest` (every control changes the picture),
`DriftHoldTest` (Evolve standing off a value a hand just moved), `MacroTest`
(Simple mode's contract across every sim), `SafetyTest` (the flash cap against
the worst case the app can produce) and `RegistryTest` (every sim run under
every law preset, paying off, making a sound, previewable, and at its largest
size).
