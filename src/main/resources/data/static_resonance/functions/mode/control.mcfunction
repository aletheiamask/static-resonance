particle minecraft:witch ~ ~1 ~ 0.4 0.5 0.4 0.02 18 force @a[distance=..32]
particle minecraft:enchanted_hit ~ ~1 ~ 0.3 0.4 0.3 0.1 12 force @a[distance=..32]
playsound minecraft:block.respawn_anchor.charge player @s ~ ~ ~ 0.4 1.6
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"Mode: Control","color":"#B388FF","bold":true}
