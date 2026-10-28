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

- **A beat under the sound.** The clips this app is modelled on always have a
  track under them, and the thing on screen lands on its beat; here every sim
  was one instrument with nothing under it, its notes at whatever moments the
  algorithm happened to make them. Now a groove — four on the floor, half-time,
  breakbeat or a slow drift — quantises the sim's notes to sixteenths, plays at
  most a three-note chord per step, moves the whole scale through a four-chord
  loop bar by bar, and plays a quiet kick, snare, hats and bass under it while
  the sim is making sound. "Auto" gives each sim its own groove and tempo and
  each TickTock clip a fresh one; Settings can pin one or turn it off. Exports
  carry the same beat. Measured, the busiest sims went from all sixty-four
  voices stolen and restolen to under thirty-five.
- **Jam out of nowhere** (new): phantom traffic jams on a stadium track, cars
  following the intelligent driver model, a jam wave rolling backwards with
  brake lights; tap a car, or the big button, to stamp on the brakes.
- The feed's time check is gone again: the app is meant to be hard to put down.

- **A UX pass over the feed, player, Library and Settings.**
  - The feed was a hook, a title, three round buttons and two big ones over
    the sim. The big ones are gone: hold the sim for its controls (long-press
    did nothing), and Settings is a fourth small glyph. The swipe hint now
    shows until the first swipe of the session, not of every visit.
  - The ★ bounces and turns accent-coloured when a sim is kept.
  - The player's bars stay while paused — a still picture with no Play in
    sight looked broken — and a tap with the drawer open closes only the
    drawer. The bars fade and the drawer slides in on arrival instead of
    being there on the first frame; the feed's chrome fades in on return.
  - Simple cards lead with what is worth dragging: Sound's two sliders sit
    under the preset, not below nine pills; Motion's sim controls come before
    Reverse time; Look's before the camera switch. Speed reads "normal" and
    snaps to 1x near it. Recording closes the drawer, and the red line is
    the stop button.
  - A one-line gesture tip shows the first three times the drawer opens.
  - Library: ‹ like every panel, a × to clear a search, and tapping the sim
    already playing no longer restarts it.
  - Settings: the TickTock format pills fold away, Gestures comes before
    them, and "Forget it all — sure?" stands down after four seconds.

- **TickTock, made to feel like the feeds it is named after.** The swipe now
  follows the finger — the caption and buttons travel with it, the clip dims,
  and the next one comes up out of the dark — where it used to be a dead
  ninety pixels and then a cut. Swiping down goes back to the clip before
  (thirty deep) instead of leaving TickTock and losing it; swiping up again
  returns. A hairline along the bottom shows how far through the clip you
  are, there even when the rest has faded. A double-tap like blooms a star
  in the middle of the screen.

- **New: plants from rules.** An L-system — a short string rewritten by a rule a
  few times over — read as turtle moves draws a fern, a bush, a weed, a tree or
  grass. It grows outward from the root, every branch at once, climbing a scale
  as it climbs, and sways in the wind once grown. Tall by nature, it fills an
  upright phone.

- **New: the ripple tank.** Straight waves roll down onto a wall with slits and
  fan out beyond it; a screen along the bottom keeps the running total, and the
  bright and dark stripes of the double-slit pattern build up on it. The payoff
  is the pattern forming, measured from the waves' arrival. A soft-edged sponge
  round the tank keeps reflections from striping the screen with a pattern
  that is not the slits'. A tap drops a stone in.

- **New: sandpile avalanches.** Grains dropped one at a time on the centre of a
  grid; four in a cell spill one to each neighbour, and a four-fold fractal
  grows out of a rule that mentions none. Every avalanche worth hearing is a
  note pitched by its size. Toppling is budgeted per step, so a long avalanche
  runs on across frames instead of stalling one.

- **Some finished pictures got one second.** Sims that report finished on the
  frame of their payoff — the spirograph, circle packing — were restarted a
  second later, while others held a second and a half first. Every screen now
  tops the hold up to the same length, counted from the payoff.
- **Monte Carlo uses the tall screen.** On an upright phone the square moves up
  and the estimate's history runs underneath it on a log scale, narrowing onto
  a line at pi. Its notes come every 512 darts instead of in random clumps.
- **TickTock opened on "Finding something…" every time.** Its clips are kept
  between visits, and Settings — the only way in — finds the first one while
  it is open.

- **N-body threw a fifth of its bodies off the screen.** The disc was squashed
  to the screen's shape while each body kept the speed of a circular orbit at
  its unsquashed radius. The disc is round and fits the width, and the view
  follows the bulk of the system: nine in ten stay on screen, from seven.
- **Library cards showed the first 1.6 seconds**, so a sim that builds looked
  empty. Each card is now the sim just after its payoff, or at the end of its
  run, within a third of a second of a phone's time.
- **Two sims sized their points in pixels.** The pendulum-wave curtain and the
  binary tree past ten thousand nodes passed 1.2–2.6 and 1.6 — most of the
  screen in world units — and drew walls of 96-pixel blobs. `FrameTest` now
  checks every point at a large count.
- **New: the double-pendulum fan.** Hundreds of double pendulums let go a
  millionth of a radian apart, swinging as one and then splitting into a
  spray; the payoff is the split, measured.

- **A read-through of every screen, as a phone shows it.**
  - On the feed, once the buttons had faded, a tap to bring them back opened
    the player instead; the first tap now only brings them back.
  - Leaving the player with its drawer open left it open: the next Back
    press only closed a drawer nobody could see, and it was open again on the
    next visit. Changing screen now closes it and clears any notice, which
    had followed people from Settings into Studio and the player.
  - The on-screen ‹ and system Back went to different places. Both now go
    back to where you came from.
  - TickTock's caption and buttons never faded; they now fade like the feed's,
    and come back with each clip. "1 clips" reads "1 clip", and "Nothing came
    up" fades in and out instead of flashing for a frame.
  - Settings printed the reset warning twice, once squeezed into a narrow
    column. The version line sat mid-page, far from the hidden glyphs meant
    to sit under it. Volume read "0.80"; it reads a percentage.
  - Studio's "Clear track — sure?" survived a switch to another track, so the
    next tap cleared a track it had not warned about. It is now tied to the
    track, and both of Studio's two-tap confirmations stand down after four
    seconds.
  - The Evolve switch carried two contradicting captions; it has one.
  - Simple mode's sound card showed hertz, notes a second and the engine's
    own event names; it shows words. Motion showed "Speed" and "Time speed",
    two sliders on the same clock; it shows one. "Break a law" no longer
    sits over nothing on sims with no law to break.

- **Three to four seconds of a still screen between runs.** Sims held their
  finished picture for up to two and a half seconds before reporting finished,
  and every screen then waited its own 1.8 s resolve beat on top. Holds are
  capped at 1.5 s and the screens wait one second.

- **A dozen sims were cut off on a phone held upright.** The screen there is
  under half a unit wide, and these drew at a fixed size as if it were square.
  Measured, a quarter to three quarters of what they drew was off the sides:
  the ball-escape ring cut to two arcs, the gear train running off to the right,
  the Monte Carlo square, prime spiral, spirograph, polyrhythm, chaos game,
  Fourier curve, hypercube, slicing shells, N-D box and Lorenz wings all
  clipped, and the predator–prey loop two thirds above the top of the screen.
  A sim drawn at a fixed size now says how far it reaches and the view pulls
  back to fit (touch, pan and export follow). The gear train zigzags down the
  screen and scales to fit; Hanoi's outer discs fit; the predator–prey plot
  scales to the loop; the pendulum wave is drawn from above, one row per
  pendulum — the snake view it is known by — so it fills an upright screen
  instead of being a small picture in the middle of it. `FrameTest` holds every
  sim to the width of an upright phone.

- **Silent stretches, measured on the real synth at 8-bit.** Gradient descent
  sounded only while the overall best improved — three seconds — then held six
  silent seconds; each walker now lands with its own note and the run ends when
  all have landed, at a learning rate slow enough to watch (about 12 s, as before, but no
  longer half silent). Lorenz was silent for up to five seconds while it circled
  one wing; it now plays a quiet note at the top of every loop. Pathfinding's
  route reveal was silent; it sings like the maze's. The prime spiral's held
  ending is 2.5 s, from 4.
- **Ball escape was silent seventy per cent of the time.** At its start speed the
  ball made slow hops under gravity that never reached a wall, three quiet
  seconds at a time. It starts faster with a narrower gap; quiet stretches are
  under a fifth of the run and it is six times louder on average. The outer gap
  now starts overhead: started anywhere, it sat under the ball one run in ten
  and the ball fell out before anything had built.
- **The chaos game's notes came in clumps with three-second silences.** It sounded
  when the dot count fell inside a narrow window, which a frame's batch only
  sometimes straddled; it now sounds every time another block of dots lands.
  The Mandelbrot dive's beat is every half-again of zoom, not every doubling —
  one note every three seconds was too sparse to read as a beat.
- **One sim was up to twenty times louder than the next.** Measured at 8-bit, the
  Lorenz attractor and the chaos game sat at a twentieth of bubble sort's
  level, so a swipe could be a jolt. The synth now has a slow leveller —
  two-second window, at most a factor of two either way, measured before the
  volume slider and frozen through silence — and the spread is five times, not
  twenty.

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
