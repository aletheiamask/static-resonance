# Playtest (before a version bump)

In-game only. Formula checks are `gradlew test`. Numbers below are **defaults**.

## Boot

- [ ] Forge 1.20.1 + Origins + this jar load without errors
- [ ] Origin **Static Resonance** appears; chooser text says **F** casts and five modes
- [ ] Charge bar visible, starts around 40

## Inputs

- [ ] Hold **R**: five wedges (Aegis, Control, Sense, Dust, Circuit)
- [ ] **LMB** on a wedge selects mode and does **not** spend charge
- [ ] **F** casts the selected mode (not Origins G)
- [ ] Tap **R** cycles Dust → Sense → Control → Aegis → Circuit → Dust

## Charge

- [ ] Dry: charge rises on the regen interval
- [ ] Water/rain: charge falls; wet damage is higher
- [ ] Nearby moving animals/mobs add charge in small steps

## Skills

- [ ] Dust burst: pays 5, blinds/slows the looked-at living; miss spends 0
- [ ] Dust zone: pays 10, plants a smoke zone
- [ ] Sense: pays 8 once; private colored boxes; F again stops
- [ ] Control target: miss spends **0**; hit spends 15 and freezes the held item
- [ ] Control field: pays 40, expands while standing still, move aborts
- [ ] Faraday: pays 50, HUD counts **seconds**, cooldown after drop
- [ ] Circuit: entering the mode does **not** hitch entities; F on a looked piece flips it (8); miss spends 0

## Feel / regression

- [ ] Faraday in a mob farm: bodies on the hard wall do not teleport-jitter; mid-ring still shoves
- [ ] Vanilla arrows (and TACZ if present) die or freeze in the cage
- [ ] Emergency saves a lethal hit **with no cage up** (emergency toggle ON)
- [ ] Relog mid-cage and mid-cooldown: timer continues, cannot skip CD
- [ ] Scan in a busy hub/farm, then unload one marked mob: FPS does not stay hitchy
- [ ] Circuit in a dense redstone base: selecting the mode does not freeze mobs; F on a lamp/piston/lever flips that one piece for 8s
- [ ] Circuit-toggle a piston or wire that is part of a running clock: the rest of the factory and other players do not hitch for the 8s hold
- [ ] Circuit-toggle a wire, walk until the chunk unloads, wait the toggle duration: spawn redstone clocks are not extra-expensive
- [ ] Wall climb on a dedicated server: no rubber-banding

## After play

If a box fails, fix that path before tagging a release. Do not add a new performance-audit markdown for a single miss.
