scoreboard players remove @s sr_faraday 5
execute if score @s sr_faraday matches ..0 run function static_resonance:faraday/deactivate
execute if score @s sr_faraday matches 1.. run function static_resonance:faraday/tick_live
