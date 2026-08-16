particle minecraft:block sand ~ ~1 ~ 0.5 0.4 0.5 0.02 20 force @a[distance=..32]
particle minecraft:cloud ~ ~1 ~ 0.4 0.3 0.4 0.01 10 force @a[distance=..32]
playsound minecraft:block.sand.place player @s ~ ~ ~ 0.5 1.2
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"Mode: Dust","color":"#C4A35A","bold":true}
