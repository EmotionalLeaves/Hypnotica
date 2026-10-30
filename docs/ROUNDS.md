# Rounds

Each round ends in an installable APK that passes `QA.md`. A round is ticked
only when its "done when" test actually passes on the phone.

| Round | Status | Ships | Done when |
| --- | --- | --- | --- |
| 1. Skeleton | done | Repo, Actions build, signed release, Compose shell, GL surface | APK installs from a release link |
| 2. Engine core | done | Fixed-timestep loop, seeded RNG, play/pause/speed/step, instrumented array | Bubble sort animates smoothly at 1x–10x |
| 3. Sound | done | Synth, sound events, scales, rate limiter, presets | Quicksort on 1,000 bars sounds musical, not static |
| 4. Sorting pack | done | Simple-mode cards, 12 sorts, 5 views, counters | Every sort finishes and does the final sweep |
| 5. Physics pack | done | Shared Laws table, ball escape, pendulum wave, Newton's cradle, Galton board | Pendulum wave realigns on schedule; the ball escapes |
| 6. Recipes | done | Lua sandbox, Advanced tabs (Data, Params, Laws, Sound, Code), recipe editor, three shipped samples | A recipe written by an AI runs first try |
| 7. Shelf packs | done | Fractals, maze and pathfinding, number theory, probability, visual proofs, graphs, art | 58 sims across six shelves |
| 8. Feed and Library | done | Swipe feed, auto-advance, favourites that persist, live previews on every card | Opening the app plays a sim within 1 s |
| 9. Export | done | MP4 with audio, offline render, 9:16/1:1/16:9, captions, cancel, saved to Movies | A 30 s vertical clip exports with synced sound |
| 10. Builders and polish | done | Equation sim, expressions in any field, Evolve, Record moves, device benchmark, first-run card | Typing a formula draws it; recorded moves land in Studio |
| 11. Laws and dimensions | done | 15 law presets, force-law expressions, N-dimensional engine, six Dimensions sims, each sim declaring the laws it reads | A tesseract spins through XW; raising the dimension count adds a plane to watch it turn in |
| 12. TickTock | done | Hidden settings button, clip director with payoff pre-check, 15 trap formats, on-device weighting that persists | 50 swipes with no repeat and no dull clip |
| 13. Studio | done | Timeline, 9 tracks, keyframes, freezes, captions, templates, offline render | A 30 s clip with a camera orbit and a mid-clip gravity flip exports at 1080p60 |
| 14. On the phone | open | Whatever `QA.md` finds on the device | The checklist passes end to end on real hardware |

Rounds 1–13 are built and verified headlessly. Round 14 is the one that needs a
phone, and nothing here can tick it from a build server.

## Changelog

### Trap loop T3 — a clock to beat

- Paint battle: a ball of each colour paints the floor as it rolls, over
  the others' paint too; most floor when the clock runs out wins. The
  leader wears a ring, the shares run across the top, the clock along the
  bottom turns red and ticks for the last five seconds.
- King of the hill: every ball is drawn to the circle in the middle and
  scores while it holds it alone; two in it shove each other out. A ring
  fills round each ball as it scores, the race runs across the top, and
  there is a heartbeat once someone is close.

### Trap loop T2 — a reward you can't schedule

- Rare runs: about one feed visit in twelve (never the first few) comes up
  gold, folded six ways and slowly turning, tagged ✦ RARE RUN, with a tap
  of haptics. Gold is a palette of its own that remixes never deal, so it
  only ever means rare (and anyone can still pick it by hand).
- Days in a row: the first open of a day says "Day 3 in a row" once
  it is a run of two or more.
- The Up next pill carries a little moving picture of the next sim.

### Trap loop T1 — the pull to stay

- As a run nears its moment, once the buttons have faded, a line breathes
  at the top of the feed — "Down to the last few" for a battle, "Nearly
  there…" for a reveal, "Wait for it…" for a snap — chosen from the sim's
  own formats.
- When it lands: a word ("There it is", "Again?"), the count of runs in a
  row watched through to the end, and an Up next pill with the next sim's
  name (and NEW if it has never been opened) that goes there on a tap.
  Swiping away before a payoff resets the count.
- Magnet domains takes a pick: which colour will the magnet settle on.
- The battles get a heartbeat at the end: Ball battle and Hungry balls
  when it is down to two, Clone war when a side is down to its last few,
  Weapon balls when anyone is under a fifth of their health (their ball
  glows red with it).
- Records are announced: a new best run of winners called (from three),
  and 10, 25, 50, 100 runs in a row watched through.

### Quality loop Q5 — the whole catalogue drawn again

- Mandelbrot, again: past twenty seconds the dive showed the same spiral
  over and over, because it was aimed at a Misiurewicz point, about which
  the set is exactly self-similar. It now dives at the nucleus of a mini-set
  (found offline to sixty digits, sharpened again on the phone): the shapes
  change all the way down, it eases to a stop with the whole set showing
  again, small and turned, holds it six seconds, and the next dive begins.
- Singing sand was an empty plate on the phone: the renderer draws points
  before shapes, and the plate was drawn over the sand. The plate fill is
  gone, and the picture tests now paint in the phone's order so a sim
  can't look right here and wrong there again.
- Three more battles: Weapon balls (a sword, dagger, spear or hammer on
  each ball, better with every hit; blade on blade parries), Hungry balls
  (the bigger swallows the smaller; the wall closes in on the stragglers)
  and Clone war (every wall hit splits a ball, opposite colours that touch
  are both gone; two swarms breed, then fight a front line across the ring).

- The sorts' dot view stands each dot on a faint stalk and threads them,
  so it ends on the sorted triangle rather than a thin dotted diagonal.
- The elliptical pool's streaks stop at the wall instead of poking
  through it just after a bounce.

### Quality loop Q4 — endings held, scribbles that resolve

- Four rules, one fern: Barnsley's fern (and a mutant fern and a maple
  leaf) filling in from a dot hopping by four random rules.
- The sorts' round views thread their dots together: a scribble across
  the disc that pulls itself into one clean curve as the sort finishes.
- The ball that shrinks holds its finished chart three seconds; it ended on
  the frame the last bar topped out. Colour war holds the moment one
  colour takes the whole board instead of cutting on that step.
- The convex hull's finished inside is tinted strongly enough to see.
- The feed deals a big family more thinly still: one sort early, the
  rest drifting later, so the new sims get their turn.

### Quality loop Q3 — two classics from the science channels

- Elliptical pool, after Nils Berglund's most-watched billiard videos: two
  thousand balls leave one focus and all meet at the other, forever. The
  stadium shows the same start smearing into chaos; the circle folds caustics.
- Singing sand: Chladni's plate. Grains thrown about by the shaking run to
  the lines that stand still and draw the note, then the plate moves up to
  the next note and the sand redraws itself.
- Magnet domains (the Ising model): a hot speckle of tiny magnets
  coarsens into marbled continents; the temperature slider takes it to
  the critical point and past it.
- Random coral (diffusion-limited aggregation): specks wander and stick
  where they touch, and a forest of branches becomes a few great trees,
  banded by when each part grew.
- One more bounce: the shorts format done with straight physics — a ball
  grows on every bounce and plays the next note of a tune, faster and
  faster until there is no room left, leaving a string-art fan behind it.
- Taps do something on all of them: break the pool again from where you
  tap, bow the plate to its next note, flick the growing ball, drop a
  grain of reef in the water, paint the magnet.
- The Lissajous table fills a tall screen: the rows carry on to as many
  multiples of the slowest wheel as fit, instead of a square in a band.

### Quality loops Q1–Q2 — every sim looked at again

- No run ends before its moment lands: a run holds at least four seconds
  after its payoff. Spirograph and circle packing rest on the finished
  figure; each sort opens on its own view with a line about what it does.
- Fish and sharks (Wa-Tor): a sea of individuals that makes the
  predator–prey waves by itself. Endless.
- Game of Life leaves fading embers where cells lived, so gliders drag tails.
- Reaction and diffusion runs fast enough to become coral before its
  payoff, and its growing fronts glow.
- Double or nothing: square-root axes so the busts are not crushed into a
  corner, a red mark where each gambler went under, the starting pile drawn.
- Trebuchet: the camera frames the arc under the map instead of behind it.

### Polish loops 6–10 — three bodies, probability, music

- Three stars (3-4-5, figure eight, butterfly, moth, thrown together), The
  sixteenth digit and Planets round two suns, on a shared adaptive RK4
  three-body integrator checked against the Pythagorean problem's known ending.
- Monty Hall, Buffon's needle and A hundred prisoners: run it a thousand
  times and watch the rate settle on the answer.
- Music: a Schroeder room for the reverb, a real kit (pitch-dropping kick,
  shelled snare, high-passed hats), a chord under each bar, two new voices,
  four new presets, four new grooves, eight more tunes.
- Mandelbrot opens on the whole set before diving; the L-system fills in from
  the start.

### Polish loops 1–5 — discovery, eight new sims, pictures of the sims

- The feed deals a fresh order each launch: unopened sims and favourites drawn
  forward, big families dealt thinly, never the same family twice running.
  NEW over a sim never opened.
- The Library counts what has been found, dots what has not, keeps a Recent
  shelf and a Today's pick; finishing a shelf is said in a banner.
- New sims: Block drop, Flood it (Algorithms); Brick breaker, Stack it, Rally,
  Bubble wrap (Physics); Lissajous table (Math); Fireflies (Life & nature).
- `SimPicturesTest` paints every sim's own draw list at three moments into
  `build/screens/sims/` — the screen pictures never showed a sim, because
  the GL surface draws nothing off a phone.
- CI's version code runs ten ahead of the run number, so its next build
  installs over the APKs built by hand during these loops.

### Rounds 1–5 — engine, sound, sorting and physics

- Deterministic fixed-timestep core at 120 Hz with frame interpolation, seeded
  PCG32, snapshot-free scrubbing by re-simulation.
- Instanced OpenGL ES 3 renderer: one shader covers bars, discs, rings with a
  gap, lines and glows; a separate point-sprite path carries the huge counts.
  Trails use a ping-pong framebuffer; the present pass adds vignette and flash.
- Polyphonic synth on its own thread fed by an event queue, with 64 voices,
  nine scales, seven presets and a rate limiter that merges bursts.
- Flash limiter enforcing three large flashes a second, one when Reduce flashing
  is on.
- 12 sorting algorithms written as resumable state machines, so one
  implementation covers 200 bars and 200,000 bars at any speed.
- Laws table shared by every physics sim, with an expression compiler so a force
  law can be typed in.

### Rounds 6–13 — recipes, shelves, feed, export, laws, TickTock, Studio

- Lua recipe sandbox with a per-step time budget; recipes are plain text files
  that register as sims. `docs/RECIPE_SPEC.md` is the contract.
- 58 sims across six shelves: Algorithms, Physics, Math, Dimensions, Life &
  nature, Art & music.
- Live Library previews: each card runs the sim headlessly for a moment and
  paints its real draw list.
- Offline MP4 export with the synth's audio, captions composited in, cancellable,
  published to `Movies/Hypnotica`.
- Equation sim: parametric curves, vector fields and iterated maps typed in as
  formulas, with nine ready-made starting points.
- Macros: one Simple-mode slider moving several values, on every sim.
- TickTock: generated clips, each verified to pay off before it is shown; taste
  and muted formats persist on the phone.
- Studio: nine keyframed tracks, freezes, captions, templates, offline render.
- Device benchmark on first launch, which sizes the generated clips.

### What the test suite checks on every build

`:app:testDebugUnitTest` runs the registry smoke test: every sim is stepped
headlessly under seven law presets looking for crashes, then checked for a
payoff, for sound, for a Simple-mode macro and for a Library preview. The big
sizes are run and their per-frame cost is printed. Sorting correctness, RNG
determinism, the expression compiler, the laws table, parameter clamping and the
flash limiter are covered too.

Since the control audit it also checks that the controls do something, which
nothing had ever checked. `ControlEffectTest` draws frames rather than trusting
any bookkeeping: for every value a sim reads only while being built, a run at
the bottom of the range and a run at the top must draw different frames; for
every value it reads live, changing it mid-run with no restart must change the
frame against a parallel run that was left alone. `DriftHoldTest` checks that
Evolve stands off a value a hand has just moved.

## Known gaps

- Nothing in this repo has been run on a phone by its author. Every number here
  was measured on a CI runner, headlessly — and the one round of feedback that
  did come from a phone ("not all buttons and sliders work") found a class of
  defect that no headless check was looking for, which is why there are now
  checks looking for it.
- The UI cannot be compiled here at all: there is no Android SDK and no network
  route to one, so CI is the only compiler for anything under `ui/`. Five builds
  in the control round were spent on scope and import mistakes that a compiler
  would have caught in a second, which is what rules 12 to 16 of
  `tools/uicheck.py` exist to replace. They are not a type checker, and a wrong
  argument type still costs a round trip.
- N-body is O(n²) and stops at 4,000 bodies; the other big sims reach five
  figures. Barnes-Hut would fix it.
- The in-app browser of recipes has no sharing; a recipe moves by copy and paste.
- Studio renders to MP4 only — no GIF, no PNG sequence.
