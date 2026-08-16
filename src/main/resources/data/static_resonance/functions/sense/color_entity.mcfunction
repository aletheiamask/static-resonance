# Legacy first-contact helper. Live pulses use sr_internal scan_pulse instead.
# Outline is client-only on the scanner. No public glowing, no HP dust.

tag @s add sr_scanned
sr_internal scan_mark

# Directional contact ping (once per player per scan session)
execute if entity @s[type=minecraft:player,tag=!sr_scan_pinged] run function static_resonance:sense/player_ping
