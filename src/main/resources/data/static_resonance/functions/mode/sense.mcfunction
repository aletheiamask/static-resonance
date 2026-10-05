particle minecraft:dust 0.94 0.97 1.0 1.0 ~ ~1 ~ 0.5 0.5 0.5 0 18 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 0.35 0.3 0.35 0.06 10 force @a[distance=..32]
playsound minecraft:block.note_block.pling player @s ~ ~ ~ 0.45 1.8
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"translate":"ui.static_resonance.mode_bar.sense","color":"#E8F4FF","bold":true}
