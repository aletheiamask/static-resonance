# @s = projectile
tag @s add sr_faraday_held
scoreboard players set @s sr_faraday_held 35
data modify entity @s NoGravity set value 1b
data modify entity @s Motion set value [0.0d, 0.0d, 0.0d]
particle minecraft:electric_spark ~ ~ ~ 0.15 0.15 0.15 0.05 8 force @a[distance=..24]
