particle minecraft:dust 0.2 0.75 1.0 1.0 ~ ~1 ~ 0.5 0.5 0.5 0 25 force @a[distance=..32]
playsound minecraft:block.note_block.pling player @s ~ ~ ~ 0.45 1.8
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"Mode: Sense","color":"#4FC3F7","bold":true}
