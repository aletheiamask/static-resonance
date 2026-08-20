scoreboard players set @s sr_faraday 0
tag @s remove sr_faraday
# Cooldown ticks are set by FaradayService from config.

effect clear @s minecraft:resistance
effect clear @s minecraft:fire_resistance

particle minecraft:flash ~ ~1 ~ 0 0 0 0 1 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 2.2 1.2 2.2 0.5 32 force @a[distance=..32]
particle minecraft:end_rod ~ ~1 ~ 1.6 1.0 1.6 0.12 12 force @a[distance=..32]
playsound minecraft:block.beacon.deactivate player @a[distance=..24] ~ ~ ~ 0.7 1.2
playsound minecraft:block.glass.break player @a[distance=..16] ~ ~ ~ 0.35 0.6

title @s actionbar {"text":"Faraday Cage — grid offline","color":"#90A4AE"}
