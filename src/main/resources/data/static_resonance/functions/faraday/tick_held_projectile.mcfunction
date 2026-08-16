# @s = held projectile (NoGravity is set once in catch_projectile)
scoreboard players remove @s sr_faraday_held 5
data modify entity @s Motion set value [0.0d, 0.0d, 0.0d]
particle minecraft:electric_spark ~ ~ ~ 0.08 0.08 0.08 0.01 2 force @a[distance=..24]
execute if score @s sr_faraday_held matches ..0 run function static_resonance:faraday/release_projectile
