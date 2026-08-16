# AS a nearby living entity — if moving, credit the sole sr_harvest player (no nearest)
# Scratch uses fake players on sr_tmp

execute store result score #mx sr_tmp run data get entity @s Motion[0] 100
execute store result score #my sr_tmp run data get entity @s Motion[1] 100
execute store result score #mz sr_tmp run data get entity @s Motion[2] 100

scoreboard players set #moving sr_tmp 0
execute if score #mx sr_tmp matches 3.. run scoreboard players set #moving sr_tmp 1
execute if score #mx sr_tmp matches ..-3 run scoreboard players set #moving sr_tmp 1
execute if score #my sr_tmp matches 3.. run scoreboard players set #moving sr_tmp 1
execute if score #my sr_tmp matches ..-3 run scoreboard players set #moving sr_tmp 1
execute if score #mz sr_tmp matches 3.. run scoreboard players set #moving sr_tmp 1
execute if score #mz sr_tmp matches ..-3 run scoreboard players set #moving sr_tmp 1

execute if score #moving sr_tmp matches 1 run scoreboard players add @a[tag=sr_harvest,limit=1] sr_mc 1
