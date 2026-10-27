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
- **The first clip waits for two full generations,** each up to twelve headless
  payoff checks of 45 simulated seconds, with no cancellation check — measured
  at 11.6 s for `physics/charges` on a desktop JVM. Leaving the screen does not
  stop it.
- **The size cap covers 7 of 16 big parameters** (`ClipDirector.kt:216-225`), so
  the pre-check can be handed 400,000 darts or 80,000 tracers.
- **The Template chips destroy the project with no confirmation.** The row no
  longer highlights a template that is not loaded, and says what replacing it
  will cost, but a tap still wipes the work with no undo.
- **The preview accepts pokes the export cannot reproduce,** and scrubbing
  erases them with nothing said.
- **The 60 s auto-stop on Record moves navigates by writing state during
  composition** (`PlayerScreen.kt:119-124`), which can double-invalidate a
  frame.
- **Scrub catch-up has no indicator** even though `SimEngine.seeking` exists for
  exactly that.

## Dimensions

- **`dimensions/random-walk` cannot pay off above two dimensions** — 2.5% of
  walkers get home in 3-D against a 35% gate — and `Nd.project` multiplies
  unbounded coordinates by 20, so three quarters of the dots leave the screen.
- **The hypercube gets dimmer and smaller as dimensions rise:** the per-axis
  depth factor clamps at 0.05, so at 5 and 6 dimensions every corner is
  sub-pixel and every edge the same colour, and 6 looks identical to 5. Axis 5
  never moves unless the user finds the advanced plane pickers, and the payoff
  is unreachable below a spin of about 0.48.
- **`dimensions/ball-volume` and `dimensions/curved-space` are not dimension
  simulations** — one is a bar chart of a formula, the other is flat 2-D with no
  dimension parameter. The shelf holds six sims; `DimensionSims.defs()` returns
  five, and `curved-space` lives in `sims/math/`.

## Payoffs and sizes

- **Seven sims use a stopwatch as their payoff** — `elapsed > T` with nothing
  happening on screen: nd-bounce, gears, cloth, optics, voronoi, curved-space,
  mandelbrot. Cloth fires at 25% of its run; DESIGN.md puts the payoff at
  70–80%.
- **Three size sliders cannot deliver their own maximum** at the shipped pace:
  `algorithms/binary-tree` nodes (3,119 of 100,000 in a minute),
  `physics/galton-board` beads (1,866 of 20,000), `art/spirograph` points
  (9,212 of 300,000). Simple mode now carries the pace with the size, but the
  advanced sliders still disagree with each other.
- **`scale/particle-field` has the id prefix `scale/` and the shelf `LIFE`;**
  no `Shelf.SCALE` exists. `SimRegistry` never checks id uniqueness, and two
  recipes with the same id make `byId` rebuild on every access.

## Simple and Advanced

- **A derived "How much" on 31 sims duplicates a slider rendered right beneath
  it,** so Simple mode is a worse subset rather than a superset. It now moves
  the subject rather than a drawing detail, carries both grid axes and the
  pace, and sweeps logarithmic counts logarithmically — but on a sim with one
  size parameter it is still one slider over one value.
- **Ten advanced-only parameters have no Simple route,** four of them `INT` so
  Evolve cannot reach them either: `range`, `drag`, `oscillations`, `viewer`,
  `x0`, `y0`, `loops`, `planeA`, `planeB`, `stiffness`.
- **Sound: scale, base pitch, range, decay, note ceiling and the per-event mutes
  are Advanced-only,** and those Advanced sliders' own readouts never update
  because `SoundMapping` fields are not snapshot state. A preset tap silently
  overwrites them in either mode.
- **Laws: falloff, the gravitational constant, the speed of light and the force
  law are Advanced-only,** and the Simple Laws card is hidden entirely on sims
  with `usesLaws = false`.
- **Palette, trails, speed, Evolve and Record have no Advanced control,** so the
  dual interface loses capability in both directions.
- **The recipe editor is reachable only through Advanced → Code,** and Lua
  recipes cannot declare macros at all, so every user-written sim is stuck with
  a single derived slider.