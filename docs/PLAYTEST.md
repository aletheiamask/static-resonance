# Playtest — Static Resonance 1.0.26

In-game only. Formulas: `gradlew test`.

**Before you start:** mods folder has **only** `static_resonance-1.0.26.jar`. Fail any case → do not ship until that path is fixed.

Record: case ID, pass/fail, notes (shader on/off, dimension, radius).

---

## S0 — Setup

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S0-1 | Nokrit 1.0.7 | Check `mods/` for this jar only | One Static Resonance jar, version 1.0.26 |
| S0-2 | Same | Note Complementary / Oculus on or off for the session | Written in your notes; later cases say which |

---

## S1 — Boot / origin

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S1-1 | Fresh world or existing | Launch until title / world load | No crash; log has `static_resonance 1.0.26` |
| S1-2 | New player | Open origin chooser | **Static Resonance** listed; text says **F** casts, five modes |
| S1-3 | Origin chosen | Look at HUD | Charge bar visible, starts around **40** |

---

## S2 — Inputs

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S2-1 | In world, origin on | Hold **R** | Five wedges: Aegis, Control, Sense, Dust, Circuit |
| S2-1a | Wheel open | Look behind the ring | One full-screen dim only — **no** two nested grey rectangles |
| S2-2 | Wheel open | **LMB** a wedge | Mode selected; charge **does not** drop |
| S2-3 | Sense selected | Press **F** | Scan casts (not Origins **G**) |
| S2-4 | Wheel closed | Tap **R** repeatedly | Cycle Dust → Sense → Control → Aegis → Circuit → Dust |

---

## S3 — Charge

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S3-1 | Dry land, stand still | Wait regen interval (~35 ticks) | Charge rises by 2 |
| S3-2 | Rain or water | Wait | Charge falls; you take extra damage while wet |
| S3-3 | Animals/mobs walking nearby | Walk near them | Charge ticks up in small steps |

---

## S4 — Sense / scan (1.0.19–1.0.26)

Use **four lock corners** (not hearts, not a full hitbox box). Green = safe, white = players, red = hostiles. Pulse slowly.

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S4-1 | Overworld, Sense, charge ≥ 8 | **F** near a pig, a zombie, and if possible another player | Corners on each; pig **green**, zombie **red**, player **white**; costs **8** once |
| S4-2 | Scan on | **F** again | Marks gone |
| S4-3 | Scan on | Switch mode off Sense | Marks gone |
| S4-4 | Complementary **on** | Scan a mob, look at it, then look at the **sky** | Corners leave with the mob; **nothing stuck to the screen or clouds** |
| S4-5 | Complementary **on** | Turn camera fast | No colored TAA smear pasted on the view |
| S4-6 | Complementary **off**, `seeThroughWalls=false` | Scan a mob, put a wall between you | Corners **hidden** by the wall |
| S4-7 | Oculus installed, shaders **disabled** in menu, toml see-through still false | Same as S4-6 | Walls still occlude (must not force see-through just because Oculus is in mods) |
| S4-8 | `seeThroughWalls=true` **or** Complementary on | Scan, hide behind a wall | Corners still visible through blocks, still locked to the mob |
| S4-9 | Nether, scan a mob **40+ blocks** away | Look at the far mark | Still green/white/red, **not** terrain-fog gray |
| S4-10 | Second account, same world, you have scan on | Other player looks at your marked mobs | They **do not** see your corners |
| S4-11 | Scan on | Logout / disconnect, log back in | No leftover corners; ally list not stale from last session |
| S4-12 | Busy hub or farm, scan | Unload / kill one marked mob, keep looking around | FPS hitch does **not** stay after that mob is gone |
| S4-13 | Scan on, walk | Corners follow interpolated body | No one-tick lag snap vs the mob |

---

## S5 — Dust

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S5-1 | Dust burst selected, look at a mob | **F** | Pays **5**; target blinded/slowed |
| S5-2 | Look at sky / miss | **F** | Pays **0** |
| S5-3 | Dust zone selected | **F** on ground | Pays **10**; smoke zone appears |

---

## S6 — Control

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S6-1 | Target lock, miss | **F** at empty air | Pays **0** |
| S6-2 | Target lock, hit a mob | **F** | Pays **15**; held item frozen / lock applies |
| S6-3 | Field selected, stand still | **F**, wait | Pays **40**; field expands |
| S6-4 | Field expanding | Walk | Field **aborts** |
| S6-5 | Field with several mobs | Other player looks | They **can** see vanilla glow (known; not a Sense private outline) |

---

## S7 — Faraday

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S7-1 | Aegis, charge ≥ 50 | **F** | Pays **50**; HUD counts **seconds** |
| S7-2 | Cage drops | Wait | Cooldown starts; cannot raise again until CD done |
| S7-3 | Mob farm | Stand in cage | Hard wall: no teleport-jitter; mid-ring still shoves |
| S7-4 | Cage up | Shoot vanilla arrows (TACZ if present) in | Projectiles die or freeze in the cage |
| S7-5 | Emergency **ON**, cage **down**, ~2 hearts | Take a lethal hit | Cage raises for free / you live |
| S7-6 | Cage **or** cooldown mid-way | Relog | Timer continues; cannot skip CD |

---

## S8 — Circuit

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S8-1 | Enter Circuit | Select mode only | **No** entity hitch; nothing flips yet |
| S8-2 | Look at a lamp/lever/piston | **F** | That **one** piece flips ~8s; pays **8** |
| S8-3 | Look at air | **F** | Pays **0** |
| S8-4 | Piece on a running clock | **F** that piece | Rest of factory / other players do not hitch for the 8s |
| S8-5 | Toggle a wire, walk until chunk unloads, wait duration | Come back | No extra-expensive leftover on spawn clocks |

---

## S9 — Climb

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S9-1 | Touch a wall | Hold against it | Climb; no charge |
| S9-2 | Dedicated server if you have one | Climb | No rubber-banding |

---

## S10 — Feel / idle

| ID | Setup | Steps | Expected |
|----|--------|--------|----------|
| S10-1 | Origin on, **no** skill | Stand in a busy area | No extra hitch vs other origins |
| S10-2 | After S4-12 | Close scan (**F** or leave Sense) | Overlay gone; frame time back to idle |

---

## After play

Failed ID → fix that path before tagging a release. Do not add a new performance-audit markdown for a single miss.

Copy results into a short note (which IDs passed). S4-4 / S4-5 / S4-9 are the shader + fog regressions this version exists to hold.
