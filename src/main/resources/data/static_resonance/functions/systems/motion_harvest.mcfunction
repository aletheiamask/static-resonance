# Count nearby moving living entities → score sr_mc on this caster
# Tag is held only for the duration of this function (single-threaded)

scoreboard players set @s sr_mc 0
tag @s add sr_harvest

execute as @e[type=#static_resonance:motion_sources,distance=0.5..5] run function static_resonance:systems/motion_try

execute if score @s sr_mc matches 9.. run scoreboard players set @s sr_mc 8

tag @s remove sr_harvest

execute if score @s sr_mc matches 1.. run particle minecraft:electric_spark ~ ~1 ~ 0.35 0.4 0.35 0.02 4 force @a[distance=..24]
