# Defects

What audit found and what is left. An item is deleted when it is fixed, not
ticked: the git history is the record, and this file is the baseline the next
audit starts from.

The rule it exists to serve, from AGENTS.md: *a control that does nothing is a
bug, and the menu must not promise what the code does not do.*

## Open

Measured, not yet acted on. Each is a decision about how the app should feel
rather than a thing that is simply wrong, which is why it is written down
instead of changed.

- **About thirty sliders are flat over the top of their travel.** Sampled at
  nine positions across the range and run for fifteen seconds, the picture is
  identical at several of them: `math/chaos-game`'s Dots draws the same frame at
  189k, 275k and 400k; `physics/n-body`'s Trail is identical from 25k up; `life/boids`
  Range does nothing above 0.0975, which is the bottom quarter of the slider;
  `algorithms/logic-gates` Addend does nothing above 64 of 255. Two causes, and
  they want different fixes. Most are accumulating counts — a trail of forty
  thousand points on a sim that emits three hundred a second needs two minutes
  to fill, so the slider's top half is real but unreachable in a run the sim
  declares as typical. A few are hard caps inside the sim, where the top of the
  range is simply ignored. The first wants a ceiling set from what the run can
  reach; the second is a plain lie in the menu. The travel sweep that found them
  is worth keeping as a tool.
- **Nine of the ten sims that do loop are cut at the wrong point.** `LOOP`'s
  claim is now true everywhere it is made (`LoopClaimTest`), but a sim's loop
  period is not its declared length: the hypercube closes at 49.8 s and declares
  26, slicing at 14.2 and declares 8, the pendulum wave at 31.0 and declares 38.
  Nothing cuts a clip at that number any more, so this is no longer a visible
  jump — but Studio's "Perfect loop" template sets its project length from it,
  and that project does not loop.
- **Two sims refresh below the frame rate.** Mandelbrot recomputes at 8 Hz while
  zooming continuously, so the scale steps 2.8% eight times a second; Voronoi
  recolours at 12 Hz while its seed dots glide at the full rate. Both are
  deliberate performance choices and both are frame-rate independent, so they
  look the same on every phone — but they are visible.
- **A Studio project lives only in memory.** Keyframes, freezes and captions are
  a few kilobytes and there is no key for them in preferences and no
  onSaveInstanceState, so process death takes a session's editing. Rotation is
  safe; a phone call is not.
- **The feed's swipe does not follow the finger.** You drag ninety pixels
  against a picture that does not move, let go, and a different picture is
  simply there. The sim cannot be animated into place — it is a GLSurfaceView,
  and the system composites a surface a frame behind the view tree, so moving
  one tears. Doing this properly means drawing the outgoing frame into a bitmap,
  which is a round of its own.
- **The size slider ignores the device benchmark.** `Bench` measures the phone
  and classifies it, and only TickTock's generated clips use the result; the
  player's slider offers 400,000 particles to a phone already measured as
  modest. Nothing reports frame rate either — `SimEngine.fps` is computed for a
  performance badge that was never built.

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
