particle minecraft:dust 0.94 0.97 1.0 1.0 ~ ~1 ~ 0.4 0.4 0.4 0 16 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 0.35 0.35 0.35 0.08 12 force @a[distance=..32]
playsound minecraft:block.redstone_torch.burnout player @s ~ ~ ~ 0.35 1.6
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"translate":"ui.static_resonance.mode_bar.circuit","color":"#E8F4FF","bold":true}
