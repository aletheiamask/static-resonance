# Static Scan — one-shot mark. Radius expand is client-only.
# Objectives created on #minecraft:load (init)

scoreboard players set @s sr_scan 32
sr_internal scan_clear

particle minecraft:sonic_boom ~ ~1 ~ 0 0 0 0 1 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 0.6 0.4 0.6 0.08 30 force @a[distance=..32]
particle minecraft:end_rod ~ ~1 ~ 0.4 0.3 0.4 0.01 12 force @a[distance=..32]
playsound minecraft:block.beacon.activate player @a[distance=..16] ~ ~ ~ 0.45 1.9
playsound minecraft:entity.experience_orb.pickup player @s ~ ~ ~ 0.35 1.7
