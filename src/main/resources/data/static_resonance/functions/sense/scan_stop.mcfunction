# Static Scan — cancel / auto-stop cleanup (no team mutations)
# Marks live in Java; leftover entity tags are not written anymore.

scoreboard players set @s sr_scan 0
sr_internal scan_clear

particle minecraft:wax_off ~ ~1 ~ 0.4 0.3 0.4 0.02 10 force @a[distance=..32]
particle minecraft:electric_spark ~ ~1 ~ 0.3 0.25 0.3 0.04 8 force @a[distance=..32]
playsound minecraft:block.beacon.deactivate player @s ~ ~ ~ 0.4 1.6

execute unless score @s sr_faraday matches 1.. run title @s actionbar {"text":"หยุดสแกน","color":"gray"}
