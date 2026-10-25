# Test report — Static Resonance 1.0.26

**Date:** 2026-08-23  
**Host:** automated Gradle + static source checks (no Minecraft client boot)

## Verdict

Automated suite **passed**. In-game `docs/PLAYTEST.md` was **not executed** in this environment.

## JUnit (`gradlew.bat test --offline`)

BUILD SUCCESSFUL. **14 tests, 0 failures, 0 errors, 0 skipped.**

| Class | Tests | Result |
|-------|------:|--------|
| `ChargeBucketsTest` | 1 | pass |
| `ExpireClockTest` | 2 | pass |
| `GameplayDefaultsTest` | 1 | pass |
| `MarkPruneTest` | 5 | pass (includes FIFO cap 128) |
| `PacketLimitsTest` | 5 | pass (reject n>max, n<0; clamp write; accept 128) |

XML: `build/test-results/test/TEST-*.xml`

## Build

- `mod_version=1.0.26`
- Jar: `build/libs/static_resonance-1.0.26.jar`

## Static invariants (1.0.26 source)

| Check | Result |
|-------|--------|
| Four-corner overlay (`ScanBoxRenderer.corners`) | pass |
| `FogRenderer.setupNoFog()` on draw | pass |
| No `SeeThroughRender.java` | pass |
| Snapshot matrices only if `!MARKED.isEmpty()` | pass |
| Skip `rebuildCache` when `MARKED` empty | pass |
| `ShaderPacks` 20-frame cache; fail → false | pass |
| `AllyListClient` `LoggingOut` clears | pass |
| Radial HUD: one full-screen scrim, no nested `sw/8` / `sw/5` fills | pass |

## Test instance install (this session)

| Check | Result |
|-------|--------|
| S0-1 jar | **pass** — `mods/static_resonance-1.0.26.jar` only |
| S0-2 shaders | Complementary Reimagined r5.2.2 present; Oculus 1.8.0 in mods |
| Launch client | **blocked** — Modrinth App has no CLI to start the instance. This agent cannot click **Play** or see the game window. |

## Playtest.md (in-game) — not executed

Visual/input cases still need a human after Play: `docs/PLAYTEST.md` S1–S10. Priority: S2-1a (no nested grey rectangles), S4-4, S4-5, S4-9.
