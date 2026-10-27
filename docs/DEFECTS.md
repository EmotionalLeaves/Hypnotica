# Defects

What audit found and what is left. An item is deleted when it is fixed, not
ticked: the git history is the record, and this file is the baseline the next
audit starts from.

The rule it exists to serve, from AGENTS.md: *a control that does nothing is a
bug, and the menu must not promise what the code does not do.*

## Open

Nothing. Three audits — one on the hidden TickTock and Studio modes, one on the
dual interface, one on the laws, the dimension sims and the guide — raised
about fifty findings between them, and all of them are closed.

The last two to go were the ones that needed something built rather than
repaired:

- **A sim that genuinely runs in reverse.** Every other sim integrates
  forwards, so "Backwards time" reverses the physics and leaves a maze or a
  sort alone — correctly, since neither unsolves. `physics/tower` is written
  the other way round: each brick's position is a function of the clock, so
  reversing time reverses the picture exactly, and the clock folds at the top
  so neither direction ever runs out of tower. `RegistryTest.theTowerRunsBothWays`
  checks the reverse retraces the build within two bricks and that a run which
  starts reversed is not simply frozen.
- **The per-event sound mutes**, now a row of pills on the Simple Sound card
  beside the preset, the scale, Brightness and Busyness.

The checks that keep the findings closed, all of which reproduce the defect
they were written for: `tools/uicheck.py` rules 5 to 10 (redeclarations, a
payoff fired by the clock alone, a law nothing reads or nothing can set, a
declared `LawKnob` that does not match the code, a named argument the
constructor does not have, and a Library mock-up naming a sim that does not
exist), `tools/glslcheck.js` and `tools/glslpixels.js` (the shaders compiled
and their pixels measured in a real rasteriser), `MacroTest` (Simple mode's
contract across every sim), `SafetyTest` (the flash cap against the worst case
the app can produce) and `RegistryTest` (every sim run under every law preset,
paying off, making a sound, previewable, and at its largest size).
