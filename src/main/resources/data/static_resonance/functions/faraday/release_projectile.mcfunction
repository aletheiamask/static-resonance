# @s = projectile
tag @s remove sr_faraday_held
scoreboard players reset @s sr_faraday_held
data modify entity @s NoGravity set value 0b
data modify entity @s Motion set value [0.0d, -0.15d, 0.0d]
particle minecraft:smoke ~ ~ ~ 0.1 0.1 0.1 0.01 4 force @a[distance=..24]
