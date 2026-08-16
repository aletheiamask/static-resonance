# Catch new projectiles in radius, tick existing holds near this cage
execute at @s as @e[type=#static_resonance:projectiles,distance=..4.8,tag=!sr_faraday_held] run function static_resonance:faraday/catch_projectile
execute at @s as @e[type=#static_resonance:projectiles,tag=sr_faraday_held,distance=..10] run function static_resonance:faraday/tick_held_projectile
