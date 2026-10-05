particle minecraft:electric_spark ~ ~1 ~ 0.4 0.5 0.4 0.08 18 force @a[distance=..32]
particle minecraft:end_rod ~ ~1 ~ 0.3 0.4 0.3 0.02 8 force @a[distance=..32]
playsound minecraft:block.respawn_anchor.charge player @s ~ ~ ~ 0.4 1.6
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"translate":"ui.static_resonance.mode_bar.control","color":"#E8F4FF","bold":true}
