# Hypnotica

An Android app that turns math and code into looping visual simulations with
sound generated from the simulation itself. Sorting algorithms, pendulums,
bouncing balls, cellular automata, chaos, particle fields — open it and something
is already moving.

**Install:** open the [latest release](https://github.com/EmotionalLeaves/Hypnotica/releases/latest)
on your phone, download the `.apk` and tap it. Android asks once for permission
to install from your browser. Every build is signed with the same key, so each
one installs over the last.

No computer is needed to build this. Push to `main` and GitHub Actions compiles,
tests, signs and publishes the APK.

## What is in it

| | |
| --- | --- |
| Sims | 58, across Algorithms, Physics, Math, Dimensions, Life & nature and Art & music |
| Sound | A synth fed by sim events — every note has a visible cause |
| Laws | Gravity, bounciness, friction, time direction and more — each sim offered the ones it reads |
| Controls | Simple mode (macros and big sliders) and Advanced mode (exact numbers, formulas, Lua) |
| Scale | Measured on a two-core runner, simulation only: 200,000 sorting bars in 4.7 ms a frame, 20,000 pendulums in 1.7 ms, 400,000 particles in 35.3 ms, and a 1024² Game of Life in 7.0 ms a generation — per generation rather than per frame, because it advances on a rate and a per-frame figure would describe the rate slider instead of the work |
| TickTock | An endless generated feed where every clip is simulated through before you see it |
| Studio | Keyframed camera, laws and captions, rendered offline to MP4 with sound |

## Building

CI does it for you. If you ever do have a machine:

```
./gradlew :app:assembleRelease      # signed APK in app/build/outputs/apk/release
./gradlew :app:testDebugUnitTest    # every sim under every law, every payoff, Simple mode's
                                    # contract, the flash cap, determinism and the sorts
```

Requires JDK 17 and the Android SDK (compileSdk 35).

## Signing

`keystore/hypnotica-release.jks` is committed on purpose, with the password
`hypnotica`, so a fresh clone produces an installable APK with no setup. **It is
public and must never be used for a Play Store listing.** To use your own key,
add these repository secrets and the workflow picks them up automatically:

`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

## Docs

| File | What it covers |
| --- | --- |
| [docs/AGENTS.md](docs/AGENTS.md) | Rules for anyone (or any AI) working on this repo |
| [docs/DESIGN.md](docs/DESIGN.md) | Design principles, tokens, the satisfaction checklist |
| [docs/ROUNDS.md](docs/ROUNDS.md) | What is built, what is next, and the test for each round |
| [docs/RECIPE_SPEC.md](docs/RECIPE_SPEC.md) | The API a sim is written against |
| [QA.md](QA.md) | The phone checklist to run on every build |

## Licence

Personal project. No licence granted yet.
