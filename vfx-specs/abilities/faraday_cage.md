# Ability: Faraday Cage

| Field | Value |
|-------|-------|
| Mode | Aegis / Overcharge (3) |
| Trigger | **F** while mode==3 (`FaradayService.tryActivate(MANUAL)`) · or emergency auto |
| Charge cost | **50** manual (`GameplayDefaults.FARADAY_MANUAL_COST`). Emergency raise is free. Charge never goes negative |
| Duration | **30s** (600 ticks), snapshotted on raise |
| Shared CD | **120s** (2400 ticks) after cage ends |
| Stand-in | Java aura (`tickCageFx` / `tickAura`) + `functions/faraday/activate` / `deactivate` / `emergency_fx` |

## Emergency

- Radial sub toggle → `ToggleFaradayEmergencyPacket`
- Java `LivingDamageEvent` HIGHEST: if a hit would leave health + absorption below **2**, raise cage (no spend, ignores cooldown), cap this hit
- Origins `prevent_death` is a last-ditch net

## Timeline

| t | Event |
|--:|-------|
| 0.00 | Spend 50 (manual) · flash + thunder |
| 0–30s | Resistance / fire resist · catch projectiles · repel living (r=10 / hard 5 / mid 7) |
| idle | Soft Java aura — not a hex lattice |
| end | Deactivate FX · 120s re-arm CD |

## Emitters (idle)

Java sparks around the caster. Open/close keep flash + thunder. Future 3D cage can replace the idle aura.

## Sound

Thunder + beacon activate · emergency adds sting

## UI

Emergency arm toggle: radial **sub-panel** (LMB on Faraday) · green ON / black OFF
