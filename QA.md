# Phone checklist

Run this on every build, straight after installing the APK from Releases.
It should take about two minutes.

## Install

- [ ] The APK installs over the previous version without uninstalling
- [ ] The icon and name look right on the home screen

## First impression

- [ ] Something is moving within one second of tapping the icon
- [ ] No splash screen, no spinner, no empty state
- [ ] Sound starts with the motion and is in step with it

## Feed

- [ ] Swipe up moves to another sim, immediately
- [ ] Swipe down goes back
- [ ] The hook line and sim name are readable over the simulation
- [ ] A run that finishes moves on by itself

## Player

- [ ] Tap toggles the overlay; it fades again after a couple of seconds
- [ ] Pause stops the sim and the sound without a click or a cut note
- [ ] The hero button does what its label says
- [ ] Long-press opens the control drawer
- [ ] Pinch zooms, two-finger drag pans, and the sim keeps running

## Gestures

- [ ] Double-tap on the player favourites the sim (the star fills)
- [ ] A two-finger twist rotates the whole simulation and it keeps running
- [ ] "Drag pans the view" in Look switches one-finger drag from the sim to the camera
- [ ] The first launch shows the gesture card once, and never again

## Controls

- [ ] Simple mode shows a handful of big controls, grouped on cards
- [ ] Advanced mode shows every parameter with exact numbers
- [ ] A Data-card slider applies when released, not mid-drag
- [ ] A Motion or Look slider applies live
- [ ] Palette and sound preset chips change the sim at once
- [ ] On a physics sim, the Laws card swaps universes and every sim that reads
      that law follows
- [ ] The Laws card offers only controls the sim in front of you responds to —
      no Laws card at all on a sim that reads none
- [ ] Every control on it does something: drag each one and watch
- [ ] A macro slider ("How much", "Energy", "Chaos") moves several values at once
      and names which ones underneath
- [ ] Opening a card and touching a macro does not jump anything before you drag it
- [ ] Changing a value in Advanced and coming back: the macro slider has moved
      to match, and nudging it does not snap the value back
- [ ] Sticky, Ghosts and Strong charges each visibly change the physics
- [ ] Charge strength and "Like charges attract" change the charges sim: the
      tracers plainly move faster at 8x than at 0.05x
- [ ] Bounciness above 1.0 reads "capped" until Free energy is on
- [ ] Zero-G on the pendulum wave says there is no gravity and the run ends,
      rather than holding a still picture
- [ ] Evolve makes the sim drift on its own; switching it off leaves it where it was
- [ ] A formula typed into an Advanced field survives a slider next to it moving,
      and a macro drag replaces it rather than being ignored
- [ ] Simple mode's Brightness and Busyness change the sound
- [ ] Advanced's Params tab ends with speed, trails, palette, Evolve and Record

## Library

- [ ] Every card shows the sim itself, not a placeholder
- [ ] Scrolling the Library stays smooth while the previews appear
- [ ] Favourites survive force-quitting and reopening the app

## TickTock

- [ ] The hidden clock under the version number opens it
- [ ] One flick moves one clip, not three
- [ ] Every clip reaches a payoff; none is dull or empty
- [ ] Double-tap likes, long-press opens it in the player
- [ ] Muting a format in Settings stops that kind of clip appearing
- [ ] Taste and muted formats survive a restart

## Studio

- [ ] Record moves in the player lands in Studio as draggable keyframes
- [ ] Scrubbing is responsive; a long scrub catches up rather than freezing
- [ ] Keyframe markers sit where their time is, including one at zero
- [ ] A caption added at the playhead shows in the preview
- [ ] Export runs with a progress bar and can be cancelled
- [ ] The finished clip is in the gallery under Movies/Hypnotica, with sound and captions
- [ ] Leaving Studio hands the sim back with the laws and speed you had before

## Performance

- [ ] Settings shows what the benchmark measured for this phone
- [ ] Pendulum wave is smooth, including at a few thousand pendulums
- [ ] Sorting with 100,000 bars is smooth
- [ ] Particle field at 150,000 is smooth; note where it starts to drop frames
- [ ] Game of Life at 512x512 is smooth
- [ ] The phone does not get uncomfortably warm in five minutes

## Juice

- [ ] A still sim (pendulum wave at rest, a finished sort) still breathes — a
      slow drift in and out, small enough that you notice it only if you look
- [ ] The payoff frame shakes a few pixels and settles immediately
- [ ] The camera ends up exactly where it was before the shake
- [ ] Counters do not make the line around them twitch as they change

## Satisfaction

- [ ] Each sim builds to something rather than just looping flatly
- [ ] The payoff is obvious: slow motion, a flash, a chord
- [ ] A finished sort does the final sweep
- [ ] You wanted to watch one more

## Safety

- [ ] Reduce flashing visibly softens the payoff flash, and fewer of them land
- [ ] Reduce motion stops the punch-in, the shake and the camera breath
- [ ] Sound never gets harsh or piercing at the top of a fast run
- [ ] Collisions and crossings can be felt, not only heard

## Dimensions

- [ ] Raising "Dimensions" on the hypercube adds corners *and* a new plane that
      visibly turns; six does not look the same as five
- [ ] Corners stay the same size and keep their near/far colouring at every
      dimension
- [ ] Choosing the spin plane changes which way it turns
- [ ] "Sphere through Flatland": raising the dimension makes the arrival sharper
      — nothing, then a sudden bloom — and the counter reports what the flat
      world sees
- [ ] The walker sim pays off at every dimension, and the dots stay on screen:
      most get home on a line, hardly any in three
- [ ] N-dimensional bouncing pays off when every wall has been hit, and the
      counter counts them

## Laws by hand

- [ ] On N-body gravity: typing `g * m1 * m2 / r^3` into the force law changes
      the orbits, and a bad formula turns the box red without crashing
- [ ] Force falloff at 1/r^1.5 is visibly different from 1/r^2
- [ ] The Gravity slider changes N-body gravity, and the Moon and Zero-G presets
      do too
- [ ] Gravity tilt sends a sim sideways

## Recipes

- [ ] The recipe editor opens, and a parse error is shown inline rather than
      crashing
- [ ] A saved recipe appears in the Library with a working preview
- [ ] A recipe that reads laws gets only those laws on its Laws card

## Settings

- [ ] Reduce flashing, reduce motion, haptics, counters, the volume and the time
      check all survive a force quit
- [ ] Simple or Advanced is remembered too
- [ ] The two hidden buttons under the version number open TickTock and Studio

## Notes

Write anything that failed as a numbered list, with the sim name and what you
did just before it. That list goes straight back into the current round.
