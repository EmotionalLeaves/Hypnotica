# Working on Hypnotica

Read this first, then `docs/ROUNDS.md` for the round you are on.

## Rules

1. **Read before writing.** `docs/DESIGN.md` is the contract for how anything
   looks, moves and sounds. `docs/RECIPE_SPEC.md` is the contract for what a sim
   may call. A change that breaks either one is a bug, not a style preference.
2. **Stay in the round.** Touch the packages the current round names. Do not
   refactor code another round owns.
3. **CI must stay green.** `./gradlew :app:testDebugUnitTest :app:assembleRelease`
   is what the workflow runs. A red build blocks the phone from getting an APK,
   which is the only way this project is tested.
4. **Never change the keystore or the application id.** Doing so means the next
   APK will not install over the last one, and the phone has to uninstall and
   lose everything.
5. **No new dependencies without a reason in the commit message.** Every library
   is weight on a phone-only build loop.
6. **Determinism is load bearing.** Inside `step()` there is no wall clock and no
   `kotlin.random` — only `env.rng`. Replays, the TickTock payoff check and the
   offline renderer all depend on this.
7. **No allocation in `step()`, `draw()` or the audio callback.** Preallocate in
   `setup()`. A garbage collection pause is a visible stutter.
8. **Every sim must pass the satisfaction checklist** in `docs/DESIGN.md` before
   its round is done.
9. **A control that does nothing is a bug.** A law no sim reads, a format no sim
   can produce, a parameter nothing uses: delete it or wire it up. The menu must
   not promise what the code does not do.
10. **The engine is shared, the sims are dumb.** Slow motion, punch-in, flashes,
   haptics, rate limiting and safety all live in the engine. A sim says what
   happened; the engine decides how it is performed.

## The loop

1. Make the change.
2. Run the checks that do not need a device:
   ```
   ./gradlew :app:testDebugUnitTest
   ```
3. Push. CI builds, signs and publishes an APK to Releases.
4. Install it on the phone and run `QA.md`.
5. Report what broke as a numbered list; fix inside the same round.
6. When the round's "done when" test passes, tick it in `docs/ROUNDS.md` and add
   a line to the changelog there.

## Offline checks without an emulator

Before pushing, run `python3 tools/uicheck.py`. The Compose screens, the engine
glue and the exporter cannot be compiled without the Android SDK, so that script
stands in for the compiler's resolution step over those files: it strips
comments and strings, then requires every capitalised symbol to be imported,
declared in the same package or part of the standard library, and every Modifier
extension that is not a layout-scope member to have its import. It exits
non-zero when something is unresolved. It is a stand-in, not a compiler — CI is
still the thing that proves the build.


The engine below the UI is plain Kotlin with no Android imports, so it can be
run headless. The unit tests already cover sorting correctness, RNG determinism,
the expression compiler, the laws table, parameter clamping and the flash
limiter. When adding a sim, add it to the registry: `RegistryTest` then steps it through
seven law presets looking for crashes, and fails the build if it never pays off,
never makes a sound, has no Simple-mode macro or draws nothing in the Library.

## What lives where

```
app/src/main/java/app/hypnotica/
  core/sim/      the simulation core: loop, rng, events, sort array, parallel-for
  core/laws/     the Laws table and the expression compiler
  core/recipe/   parameter schema, live values, macros, the Lua recipe sandbox
  core/director/ TickTock's clip generator and its payoff pre-check
  core/studio/   timeline, keyframes, projects, the move recorder
  core/preview/  headless frame snapshots for the Library cards
  core/perf/     the first-launch device benchmark
  core/nd/       the N-dimensional engine
  core/render/   draw list, shaders, GL renderer, palettes
  core/audio/    synth, scales, event-to-note mapping
  core/safety/   flash limiter and accessibility settings
  sims/          the sims themselves, grouped by shelf, with four exceptions:
                 the particle field, Game of Life and Lorenz share
                 sims/scale/ParticleFieldSim.kt, and curved-space shares
                 sims/math/GeometrySims.kt. An id's prefix is the namespace,
                 and SimRegistry.idNamespaces is the list of allowed ones
  engine/        SimEngine: binds core, renderer, audio, camera and haptics
  export/        EGL + MediaCodec offline renderer, captions, publishing
  ui/            Compose screens
```
