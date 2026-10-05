particle minecraft:electric_spark ~ ~1 ~ 0.6 0.6 0.6 0.15 30 force @a[distance=..32]
particle minecraft:end_rod ~ ~1 ~ 0.3 0.4 0.3 0.02 8 force @a[distance=..32]
playsound minecraft:entity.lightning_bolt.thunder player @s ~ ~ ~ 0.25 1.7
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"translate":"ui.static_resonance.mode_bar.aegis","color":"#E0F7FF","bold":true}
