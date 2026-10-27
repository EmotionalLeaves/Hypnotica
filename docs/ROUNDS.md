# Rounds

Each round ends in an installable APK that passes `QA.md`. Tick a round only
when its "done when" test actually passes on the phone.

| Round | Status | Ships | Done when |
| --- | --- | --- | --- |
| 1. Skeleton | done | Repo, Actions build, signed release, Compose shell, GL surface | APK installs from a release link |
| 2. Engine core | done | Fixed-timestep loop, seeded RNG, play/pause/speed/step, instrumented array | Bubble sort animates smoothly at 1x–10x |
| 3. Sound | done | Synth, sound events, scales, rate limiter, presets | Quicksort on 1,000 bars sounds musical, not static |
| 4. Sorting pack | done | Simple-mode cards, 12 sorts, 5 views, counters | Every sort finishes and does the final sweep |
| 5. Physics pack | done | Shared Laws table, ball escape, pendulum wave, Newton's cradle, Galton board | Pendulum wave realigns on schedule; the ball escapes |
| 6. Recipes | next | Lua sandbox, Advanced tabs (Data, Params, Laws, Sound, Code), recipe editor | A recipe written by an AI runs first try |
| 7. Shelf packs | planned | Fractals, maze and pathfinding, number theory, probability, visual proofs, graphs | 60+ sims across the shelves |
| 8. Feed and Endless | partly done | Swipe feed, auto-advance, favourites; thumbnails still placeholders | Opening the app plays a sim within 1 s |
| 9. Export | planned | MP4 with audio, offline render, 9:16/1:1/16:9, overlays | A 30 s vertical clip exports with synced sound |
| 10. Builders and polish | planned | Equation, grid and particle builders, expressions in fields, Evolve, Record moves | Typing `z = z^2 + c` produces a zoomable fractal |
| 11. Laws and dimensions | partly done | Laws presets and card done; N-dimensional engine and the Dimensions shelf still to come | Backwards time rebuilds a collapsed tower; a tesseract spins through XW |
| 12. TickTock | planned | Hidden settings button, clip director with payoff pre-check, trap formats, on-device weighting | 50 swipes with no repeat and no dull clip |
| 13. Studio | planned | Timeline, keyframes, camera track, templates, background render | A 30 s clip with a camera orbit and a mid-clip gravity flip exports at 1080p60 |

## Changelog

### Rounds 1–5 — engine, sound, sorting and physics

- Deterministic fixed-timestep core at 120 Hz with frame interpolation, seeded
  PCG32, snapshot-free scrubbing by re-simulation.
- Instanced OpenGL ES 3 renderer: one shader covers bars, discs, rings with a
  gap, lines and glows; a separate point-sprite path carries the huge counts.
  Trails use a ping-pong framebuffer; the present pass adds vignette and flash.
- Polyphonic synth on its own thread fed by a lock-free event queue, with seven
  voices, nine scales, seven presets and a rate limiter that merges bursts.
- Flash limiter enforcing three large flashes a second.
- 12 sorting algorithms written as resumable state machines, so one
  implementation covers 200 bars and 200,000 bars at any speed.
- Laws table shared by every physics sim, with an expression compiler so a force
  law can be typed in.
- 20 sims registered across four shelves.

Measured on a two-core CI runner, per 60 Hz frame, step plus draw-list build:

| Sim | Size | Time |
| --- | --- | --- |
| Particle field | 150,000 | 15.8 ms |
| Particle field | 400,000 | 41.2 ms |
| Sorting bars | 200,000 | 3.8 ms |
| Game of Life | 1024 x 1024 | 9.1 ms |

A phone has more cores than the runner, so the particle numbers should improve
there; the figure to trust is the one `QA.md` records on the actual device.

## Known gaps

- Thumbnails in the Library are generated glyphs, not live previews.
- Favourites are in memory only; they do not survive a restart yet.
- The Dimensions shelf, TickTock and Studio are not built yet.
- Sims are Kotlin classes; the Lua recipe layer arrives in round 6.
