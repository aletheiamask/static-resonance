# @s = player. Interval 5 ticks from action_over_time.

# Cooldown tick-down when cage off
execute if score @s sr_faraday matches 0 if score @s sr_faraday_cd matches 1.. run scoreboard players remove @s sr_faraday_cd 5
execute if score @s sr_faraday_cd matches ..-1 run scoreboard players set @s sr_faraday_cd 0

# Active cage body
execute if score @s sr_faraday matches 1.. run function static_resonance:faraday/tick_active
