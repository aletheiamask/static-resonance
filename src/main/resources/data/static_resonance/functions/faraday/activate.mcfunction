# Cage ON VFX — duration score is set by FaradayService from config.
# @s = player
tag @s add sr_faraday

effect give @s minecraft:resistance 30 4 true
effect give @s minecraft:fire_resistance 30 0 true

particle minecraft:flash ~ ~1.1 ~ 0 0 0 0 1 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 1.8 1.0 1.8 0.4 48 force @a[distance=..32]
particle minecraft:end_rod ~ ~1 ~ 1.4 0.9 1.4 0.05 20 force @a[distance=..32]
particle minecraft:soul_fire_flame ~ ~1 ~ 1.2 0.8 1.2 0.02 12 force @a[distance=..32]
playsound minecraft:entity.lightning_bolt.thunder player @a[distance=..28] ~ ~ ~ 0.7 1.65
playsound minecraft:block.beacon.activate player @a[distance=..20] ~ ~ ~ 0.85 1.4
playsound minecraft:block.respawn_anchor.charge player @a[distance=..18] ~ ~ ~ 0.5 1.8
