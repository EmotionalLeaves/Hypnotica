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

### Shorts: no words on the picture; auto-tap, from its first log

- The words and the voice are gone from the videos: no hook sticker, no
  burned-in callouts, no count, no mark, no text-to-speech. The person
  found them ridiculous. The caption, description and tags carry the words,
  on the post; the edit, the beat and the soft start stay.
- Auto-tap's first log on the phone, read line by line: YouTube's share
  lands in the Shorts editor ("Swipe up to edit", Text, Effects…, Next).
  After Next the details screen read as five empty nodes — the walk of the
  screen stopped at a container that called itself not visible, and never
  read what was in it; children are now read whatever their parent says.
  Next tapped while the editor was still loading did nothing — nothing is
  tapped now until the screen has held still for two reads, and a tap that
  changed nothing is tried again as a real touch. Asked to show the way on,
  the person tapped Volume and it was learned as Next — taps on the
  editor's tools and on any way out are never learned now, a learned tap is
  only used on the screen it was shown on, and the old taps are dropped. A
  "Delete edits?" was answered Continue and the video was lost — any
  question with discard, delete or lose in it is answered Cancel.

### Shorts that hold attention — after YouTube's own review

YouTube's automated review of an early Short (a sort, captioned "Listen to
it") marked it C for the hook, B for visuals, B− for sound, B+ for structure
and C+ for presence. Each point, answered:

- Hook: every Short opens on a line about its own sim — "Snake battle: which
  one wins? 👇", "Glass bridge: which one wins?", "Break and run — wait for
  it… 😳" — and a sort hides its name: "Can you guess this sorting
  algorithm? 🤔", answered on screen and aloud at the end ("It's Radix sort
  (LSD)!"). The post's caption is the same hook plus hashtags, and never
  gives a sort's answer away.
- Visuals: the hook is a violet sticker that pops in and settles, smaller
  lines sit on dark plates, the app's callouts ("Double KO!", "Photo
  finish!") are burned in as they happen in outlined lettering, a number
  clip's number runs big on screen, and a small HYPNOTICA mark sits at the
  top of every frame.
- Sound: a beat under every Short, chosen per clip as the app's Auto beat
  does — the sim's notes land on its grid with a kick and a bass under
  them instead of a stream of raw clicks — and a fade over the first 0.8 s,
  so a busy opening isn't a burst of noise.
- Structure: the edit runs the hook at real time, the dull middle of the
  build at 1.6–2×, real time again, then 0.72× for the last breath before
  the moment, and real time after. `ShortsPlanTest` renders the edit the
  way the exporter does and checks the moment lands where the captions
  around it are placed.
- Presence: the hook is spoken (the phone's own text-to-speech, laid into
  the sound with the sim ducked under it), the reveal or "Did you call it?"
  after the moment, and every Short ends on "Follow for a new sim every
  day".
- Choice: Shorts come only from clips where at least two moments are called
  on the way (a KO, a near miss, a lead change), never the slow loop or
  grow kinds, checked at the video's own 1080 × 1920 shape.
- Posting through the YouTube app now also fills in the description and
  tags when the upload screens have fields for them, answers No to "altered
  or synthetic content" (a simulation isn't realistic footage of anything
  that happened), picks "not made for kids", and then taps Upload.

### Auto-tap — Shorts through the YouTube app

- A second way to post (Settings → YouTube → "YouTube app"), with no Google
  Cloud project and no private-only rule: Hypnotica renders the batch, then
  hands each Short to the YouTube app, and an accessibility service the
  person switches on ("Hypnotica auto-tap") steps through YouTube's upload
  screens — closes tips, Next, types the title, Upload — then brings
  Hypnotica back for the next one. It reads only YouTube's windows, and
  only while an upload from Hypnotica is running.
- Nothing about YouTube's screens is fixed in the code, so an update to the
  YouTube app doesn't break it: buttons are found by what they say (whole
  words, so "Uploading…" is never an Upload button) and where they sit; a
  tap that moved the screen on is remembered and tried first next time, one
  that didn't loses trust and is dropped after two misses; when the YouTube
  app's version changes, every remembered tap goes back on probation; and
  on a screen it can't get past it stops and asks for one tap, learns that
  button, and carries on. Settings shows how many taps it has learned and
  can forget them.
- Android 13 and later block accessibility services for apps installed
  outside the Play Store until "Allow restricted settings" is turned on in
  the app's App info; the section says so and opens App info. YouTube's
  terms don't allow automated use of the service, and the section says that
  too.

### Check loop C1 — TickTock played through, every sim shuffled, YouTube

- TickTock, played back headless the way the screen plays it (now
  `TickTockTest`): sixty generated clips, each run to its end at two phone
  shapes with uneven frames. Payoffs landed exactly when the check said on
  the shape it was checked at; on a 16:9 phone an arena clip was still
  undecided when its clip ended. The director now checks payoffs at the
  shape of the screen the clip will play on.
- TickTock fixes found reading it through: a clip that threw an error left
  the feed frozen behind the halt card with its timer stopped — it is now
  skipped ("That one broke — skipped it."), and the next clip clears the
  halt so it can draw. The end of the previous clip's run, arriving on the
  frame before the new clip was applied, could skip the clip that had just
  started. A swipe up made while the next clip was still being found said
  "Finding the next one…" and then never moved on when it was found.
- Random games (now `RandomGamesTest`, two a sim every build; it was run at
  four for this round): every setting anywhere in its range, ends of ranges
  on purpose, any seed, phones upright, 16:9, square and on their side,
  other universes' laws, taps, drags and flings. Found: the fastest-way-down
  race threw on a square screen (its side-by-side layout left the timing
  board less than no room — a square export would have crashed); the
  pendulum wave never came back into line past a few dozen pendulums (the
  i-th of thousands swung 13 + i times a cycle, a period of milliseconds —
  past 48 they now share lengths in bands, and fast pendulums take smaller
  steps); a sandpile wider than about 300 spent two minutes budget-bound
  before its moment (the budget now follows the pile's area: about 30 s);
  and a sixteen-fold dragon curve on a square screen threw on the render
  thread — the drawing list dropped shapes past its cap only when told to
  reserve room, and a single shape past it wrote off the end of the buffer;
  and reaction-diffusion with eighty blobs packed into a narrow field used up
  its first chemical everywhere at once and went black for good (blobs now
  cover at most a sixth of the field, and a field gone dark is reseeded);
  and the Game of Life started at nine cells in ten starved in one
  generation and stayed a black board (a dead board now gets a fresh soup).
  Each game now has its own generator, from the sim and the game's number,
  so the test finds the same things on every machine; a six-a-sim soak
  after the fixes came back clean.
- YouTube: Settings → YouTube connects a channel through Google sign-in and
  makes and posts a chosen number of Shorts (1, 2, 3, 5 or 10) — each a
  TickTock clip checked at 1080 × 1920, rendered upright with its hook as
  the opening caption and no flash, cut a few seconds after its moment and
  under 58 s, uploaded with the resumable protocol (picking up where a
  dropped connection stopped), then deleted from the phone. Private,
  unlisted or public. Google needs the app registered once in a Google
  Cloud project (YouTube Data API on, the account as a test user, an
  Android OAuth client with the package name and SHA-1 the section shows
  and copies). Until that project passes YouTube's API audit, YouTube keeps
  API uploads private whatever is chosen.

### Refine loop R3 — two number-go-up machines and a snake battle

- Ball multiplier: a dozen balls drop from a sliding hopper through three
  rows of gates — ×2, ×3, a narrow ×5, a narrower ×10 and a red one that
  swallows the ball — with pegs between the rows to scatter them, into four
  cups. Pick the cup that fills most; the count is called at 100, 250, 500
  and 1,000, a ×10 pass is called out, and the run's total is a live best.
  Runs land anywhere from about 130 to 500 balls. Two fixes before it
  shipped: the hopper swept at an even speed (a sine lingered over the
  outer cups and they won half the time) and the offset peg rows reach the
  walls (balls slid down the sides untouched).
- 1 to 1,000: one ball in a ring, and every time a ball hits the wall there
  is a chance it splits in two. Slowly, then all at once: 10, 100, 250, 500,
  "1,000!!" in about fifteen seconds; the fastest time to the top is the
  best. A just-split ball cannot split again until it has crossed the ring,
  and nothing leaves the wall at a grazing angle — without both, a ball
  skimming the floor split on every step and the thousand was a clump.
- Snake battle: four snakes on one board after the same apples, each
  steering for the nearest apple while keeping clear of walls, bodies, a
  bigger snake's head and any pocket too small to fit in. A crash leaves the
  body as apples for the others; heads meeting, the longer one wins and a
  tie takes both. Past fifteen seconds the walls close in a ring at a time —
  a head caught is out, a body caught loses its tail — so every battle ends,
  usually around twenty seconds. Colours are dealt to starting places at
  random so no colour owns a corner, and the winner holds still for its
  moment (left to roam a closed-in board it crashed on its lap of honour).

### Refine loop R2 — the build-up, called

- Live bests: on the twelve sims whose number climbs during a run (a
  perfect streak, a rally, pumps before a pop, parries, the biggest
  avalanche) the counters carry "best 14" beside it, and passing it pops
  "New best!" right then, not at the end. A count still climbing after the
  moment is saved quietly every few seconds.
- Races name their winner: "Insertion sort wins!", "Cycloid wins!",
  "Wall follower takes the lead!", "Photo finish!" when the runner-up is a
  hair behind, "Shortest path, last place!" when the straight ramp loses.
- Countdowns on the way to the moment: Snake "Half the board!" … "99%!!",
  Sudoku "3 to go…", Bubble wrap "3… 2… Last one!", Power wash "One spot
  left!", Pour "Last glass!", Twin stars "2 digits left…", Fireflies "Half in
  step…", Metronomes "Falling in…", the Slinky "The bottom hasn't moved…",
  the double pendulum "Starting to drift…" then "Split at 6.2 s!".
- Near misses and results: the wheel bouncing off the jackpot's peg is
  "So close!", landing next to it "One off!"; the trebuchet "12 m short!";
  billiards "Two on the break!", "Double!", "Scratch!"; Monty Hall "Stick is
  ahead?!" before switching takes over; the prisoners "Loop of 61 — caught!";
  the birthday room "A match at just 19 people!"; π found a digit at a time
  ("3.1…", "3.14!", "3.141!!") by the darts and by Buffon's needles.
- New bests for Flood it (fewest moves), the birthday rooms (fewest people
  for a match), Buffon's needle (closest to π) and the sandpile (biggest
  avalanche).

### Refine loop R1 — moments named, records kept

- Callouts: a word pops over the picture when something happens — "Double
  KO!", "Parry!", "Critical!", "Comeback!", "Final two!", "Photo finish!",
  "SO close!" when the corner badge nearly makes it, "TETRA!", "Perfect ×5",
  "POP!", "SHATTER!", "CLEARED!", and a word for the moment itself on fifty
  more sims ("Sorted!", "Solved!", "Bell curve!", "The whole set again!").
  Small words are a second apart at most and a test keeps every sim under
  about one every two seconds; big ones come with a buzz.
- Personal bests: twelve sims keep a number that varies run to run — the
  longest perfect streak in Stack it, pumps before a pop, near misses
  before the corner, parries, shoves, lead changes, the biggest swarm, how
  close the darts got to π. The first run sets it quietly; beating it says
  "New best: …", and the Library card shows it ("Best: 14 parries").
- The feed carries the watched-through run under every title once it is
  three or more ("🔥 7").

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
