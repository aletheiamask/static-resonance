# @s = scanned player target (first contact this scan session)
# Play alert at target position for active scanners → directional stereo
tag @s add sr_scan_pinged

execute at @s as @a[scores={sr_scan=1..}] if entity @s[distance=0.5..] run playsound minecraft:block.note_block.bell player @s ~ ~ ~ 0.7 1.35
execute at @s as @a[scores={sr_scan=1..}] if entity @s[distance=0.5..] run playsound minecraft:block.amethyst_block.chime player @s ~ ~ ~ 0.45 1.6
