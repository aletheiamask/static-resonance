# Magnetic Lock — no target in range (no charge spent; handled by power)
playsound minecraft:block.note_block.bass player @s ~ ~ ~ 0.5 0.6
execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"No magnetic target","color":"#9E9E9E"}
particle minecraft:smoke ~ ~1 ~ 0.2 0.2 0.2 0.01 6 force @s
