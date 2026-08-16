# Static Scan — cancel / auto-stop cleanup (no team mutations)
# Bound to scan soft-cap so stop does not walk the whole dimension

tag @e[tag=sr_scanned,distance=..128] remove sr_scanned
tag @e[tag=sr_scan_pinged,distance=..128] remove sr_scan_pinged

scoreboard players set @s sr_scan 0
sr_internal scan_clear

particle minecraft:smoke ~ ~1 ~ 0.4 0.3 0.4 0.02 12 force @a[distance=..32]
playsound minecraft:block.beacon.deactivate player @s ~ ~ ~ 0.4 1.6

execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"หยุดสแกน","color":"gray"}
