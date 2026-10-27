# Open defects

Everything found by audit that is not yet fixed, worst first. Each line names
the file to start from and what the user sees, so the work can be picked up
cold. Fixed items are deleted, not ticked: the git history is the record.

The rule this list exists to serve, from AGENTS.md: *a control that does
nothing is a bug, and the menu must not promise what the code does not do.*

## Laws that do not do what the menu says

- **Backwards time is a no-op for most of the library.** 67 sites advance their
  own clocks with `abs(dt)`, which is right for a state machine — a maze does
  not unsolve — but `ROUNDS.md:18` claims "backwards time rebuilds a collapsed
  tower", which nothing implements. Either build one sim that genuinely runs in
  reverse, or stop claiming it. The Friction slider and the "No friction" pill
  also have no effect under reversed time in ball-escape, pendulum-wave and
  newtons-cradle, because those clamp `friction * dt` to a positive range.
- **`dimensions/ball-volume` and `dimensions/curved-space` are not dimension
  simulations** — one is a bar chart of a formula, the other is flat 2-D with no
  dimension parameter. The shelf holds six sims; `DimensionSims.defs()` returns
  five, and `curved-space` lives in `sims/math/`.

## Payoffs and sizes

- **Three size sliders cannot deliver their own maximum** at the shipped pace:
  `algorithms/binary-tree` nodes (3,119 of 100,000 in a minute),
  `physics/galton-board` beads (1,866 of 20,000), `art/spirograph` points
  (9,212 of 300,000). Simple mode now carries the pace with the size, but the
  advanced sliders still disagree with each other.
- **A derived "How much" on 31 sims duplicates a slider rendered right beneath
  it,** so Simple mode is a worse subset rather than a superset. It now moves
  the subject rather than a drawing detail, carries both grid axes and the
  pace, and sweeps logarithmic counts logarithmically — but on a sim with one
  size parameter it is still one slider over one value.
- **The scale and the per-event mutes are still Advanced-only,** and a preset
  tap overwrites the pitch, range, decay and scale with no warning, in either
  mode. Simple mode now has Brightness and Busyness over the other five
  values.
- **The recipe editor is reachable only through Advanced → Code,** and Lua
  recipes cannot declare macros at all, so every user-written sim is stuck with
  a single derived slider.