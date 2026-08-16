# Static Scan pulse — sr_scan = current radius (grows until stop / no charge)
# Origin heartbeat + rim torus at the current band. Outline marks are Java-batched.

execute unless score @s sr_faraday matches 1.. run title @s actionbar [{"text":"สแกน ","color":"#4FC3F7"},{"score":{"name":"@s","objective":"sr_scan"}},{"text":" บล็อก · F หยุด","color":"gray"}]

# Field origin (nearby viewers only)
particle minecraft:dust 0.2 0.75 1.0 1.2 ~ ~0.25 ~ 1.2 0.05 1.2 0 10 force @a[distance=..24]

# Rim — 8 headings at the current band radius (not a filled disk)
execute if score @s sr_scan matches 1..4 run function static_resonance:sense/scan_rim_4
execute if score @s sr_scan matches 5..8 run function static_resonance:sense/scan_rim_8
execute if score @s sr_scan matches 9..16 run function static_resonance:sense/scan_rim_16
execute if score @s sr_scan matches 17..32 run function static_resonance:sense/scan_rim_32
execute if score @s sr_scan matches 33..64 run function static_resonance:sense/scan_rim_64
execute if score @s sr_scan matches 65..128 run function static_resonance:sense/scan_rim_128

# Per-scanner outline set + one S2C batch. Does not use shared sr_scanned.
sr_internal scan_pulse
