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

## Known gaps

- Nothing in this repo has been run on a phone. Every number here was measured
  on a CI runner, headlessly.
- N-body is O(n²) and stops at 4,000 bodies; the other big sims reach five
  figures. Barnes-Hut would fix it.
- The in-app browser of recipes has no sharing; a recipe moves by copy and paste.
- Studio renders to MP4 only — no GIF, no PNG sequence.
