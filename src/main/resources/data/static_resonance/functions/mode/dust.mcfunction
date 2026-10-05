particle minecraft:dust 0.94 0.97 1.0 1.0 ~ ~1 ~ 0.45 0.4 0.45 0 16 force @a[distance=..32]
particle minecraft:cloud ~ ~1 ~ 0.4 0.3 0.4 0.01 8 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 0.35 0.35 0.35 0.06 10 force @a[distance=..32]
playsound minecraft:block.redstone_torch.burnout player @s ~ ~ ~ 0.4 1.4
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"translate":"ui.static_resonance.mode_bar.dust","color":"#E8F4FF","bold":true}
