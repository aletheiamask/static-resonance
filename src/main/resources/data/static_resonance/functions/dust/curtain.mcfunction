# Dust Curtain — crowd control cloud (vanilla stand-in for 3D charged dust)
# MC 1.20.1 particle syntax
particle minecraft:block sand ~ ~1 ~ 2.2 0.8 2.2 0.05 40 force @a[distance=..32]
particle minecraft:cloud ~ ~1 ~ 1.8 0.6 1.8 0.02 24 force @a[distance=..32]
particle minecraft:crit ~ ~1 ~ 1.5 0.4 1.5 0.01 10 force @a[distance=..32]

playsound minecraft:block.sand.break player @a[distance=..16] ~ ~ ~ 1.0 0.7
playsound minecraft:entity.phantom.flap player @a[distance=..16] ~ ~ ~ 0.35 0.5

execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"Dust Curtain","color":"#C4A35A"}
