playsound minecraft:block.note_block.bass player @s ~ ~ ~ 0.6 0.5
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"Insufficient Charge","color":"red"}
particle minecraft:smoke ~ ~1 ~ 0.2 0.2 0.2 0.01 8 force @s
