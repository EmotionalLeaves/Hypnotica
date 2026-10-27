# Hypnotica design contract

Everything here is enforced in code or checked on the phone. When two rules
conflict, the lower-numbered principle wins.

## Ten principles

1. **The sim is the interface.** The simulation fills the screen; UI floats over
   it and fades after about 2.6 s with no touch. Never shrink a sim to fit chrome
   unless controls were asked for.
2. **Motion before menus.** Every entry point shows moving content within one
   second. No loading screens, no splash.
3. **Every event is seen, heard and felt.** A collision, swap or birth gets a
   visual accent, a sound and a haptic tick on the same frame.
4. **Satisfaction is designed, not hoped for.** Every sim ships with a scripted
   arc and must pass the checklist below.
5. **Plain words, real math.** Labels say "Messiness", not `entropy_coeff`. The
   maths underneath stays honest and is one long-press away.
6. **One gesture, one meaning.** Tap shows controls, double-tap likes, long-press
   opens more, pinch zooms, two-finger drag pans. The same everywhere.
7. **Nothing breaks the loop.** No modal dialogs over a running sim, no spinners,
   no sound cutting mid-note.
8. **Dark canvas, bright content.** Near-black background; colour is reserved for
   the simulation.
9. **Deterministic and shareable.** Any frame is reproducible from recipe, seed
   and parameters.
10. **60 fps or it does not ship.** A sim that drops frames on the reference
    phone gets simplified until it does not.

## The four-beat arc

| Beat | Share of a run | What happens |
| --- | --- | --- |
| Setup | ~10% | Clean, calm start; the goal is obvious at a glance |
| Build | ~60% | Steady escalation: more, faster, higher, brighter |
| Payoff | ~10% | The moment — a snap, an escape, a completion |
| Resolve | ~20% | Settle and breathe; the last frame is worth a screenshot |

`Sim.payoff()` returns true on the payoff frame. The engine answers with slow
motion (time drops to 0.25x and eases back over a second), a small camera
punch-in, a rising sweep from the synth and a rate-limited flash.

## Motion craft

- Easing on everything that is not physics; nothing in the UI moves linearly.
- Trails for anything moving faster than about one screen-width a second.
- Anticipation of 100–200 ms before a big event.
- Slow motion at the payoff, ramped back in rather than snapped.
- Camera breath under 1% zoom to keep static sims alive; a 2–5% punch-in on the
  payoff, off when Reduce motion is on.
- Screen shake only on heavy impacts, 2–4 px, under 150 ms, switchable off.

## Impact juice

On the same frame as the sound event: a brightness flash on the object, a glow
or spark burst at the contact point, a colour shift along the palette, and a
haptic tick sized to the impact.

## Completion pleasure

- The final sweep: a success-coloured highlight runs the length of a finished
  sort with rising notes.
- Order made visible: sorted data becomes a smooth gradient or a clean spiral.
- Alignment moments (pendulums lining up, orbits closing) get a glow pulse and a
  chord.

## Tokens

| Token | Value | Use |
| --- | --- | --- |
| `Bg` | `#07080C` | Canvas and app background |
| `Surface` | `#14161C` | Glass panels and drawers |
| `SurfaceStrong` | `#1E212A` | Pressed and selected |
| `TextPrimary` | `#F2F4F8` | Labels and values |
| `TextSecondary` | `#9AA3B2` | Hints, units, captions |
| `Accent` | `#7C5CFF` | Selection, active slider, hero button |
| `Success` | `#3DDC97` | Final sweep, "sorted", wins |
| `Warning` | `#FFB020` | Performance and safety notices |
| `Danger` | `#FF5470` | Destructive actions and errors |

Sim palettes are hue arcs so any two positions look right together: Spectrum
(default), Aurora, Ember, Ocean, Candy, Mono glow, Colour-safe. They are
evaluated through a 512-entry lookup table, because a palette call happens once
per object per frame.

Type: Display 28/34, Title 20/26, Title medium 16/22, Body 15/22, Body small
13/18, Label 12/16. Counters use tabular figures so they do not jiggle.

Layout: 4 dp grid, 16 dp gutters, 8 dp between related controls and 16 dp
between groups, 48 dp minimum touch target, 52 dp primary actions. Radius 12 dp
cards, 24 dp sheets, full pill for chips.

Motion: `fast` 120 ms, `base` 200 ms, `sheet` 280 ms, `slow` 350 ms.

## Controls

Simple mode groups controls onto picture-first cards — Data, Motion, Look, Sound
and, on a physics sim, Laws — and shows only the ones not marked
`advancedOnly`. Advanced mode shows everything with exact numbers and accepts a
formula in any numeric field.

Data-card parameters shape what `setup()` builds, so they take effect when the
slider is released rather than mid-drag. Everything else applies live.

## Accessibility and safety

| Risk | What the engine does | What the person controls |
| --- | --- | --- |
| Photosensitive seizures | Large flashes capped at 3 a second (WCAG general flash threshold) | "Reduce flashing" softens them to a quarter and tightens the cap |
| Loud or harsh sound | Soft limiter, loudness kept level across sims, pitch capped at 4 kHz | Volume, gentle presets, mute per event kind |
| Motion sickness | Shake and punch-in small by default | "Reduce motion" turns them off |
| Colour blindness | Palettes readable by lightness | A colour-safe palette |

## Satisfaction checklist

Every sim, before its round is done:

- [ ] Clear setup, build, payoff and resolve
- [ ] Every event has a matched visual, sound and haptic on the same frame
- [ ] Payoff uses at least two of: slow motion, punch-in, final sweep, chord, glow pulse
- [ ] No stutter at 60 fps on the reference phone
- [ ] Trails or blur on anything moving faster than a screen-width a second
- [ ] The final state is worth a screenshot
- [ ] Loops cleanly, or hands off smoothly to the next clip
- [ ] Inside the flash and loudness limits above
