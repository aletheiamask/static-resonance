# Sci-fi emergency banner (after activate). @s = player
title @s times 8 50 20
title @s title {"text":"⚠ FARADAY PROTOCOL","color":"#E0F7FF","bold":true}
title @s subtitle {"text":"// EMERGENCY CONTAINMENT ENGAGED //","color":"#4FC3F7","italic":true}
playsound minecraft:block.beacon.power_select player @s ~ ~ ~ 1 0.7
playsound minecraft:entity.elder_guardian.curse player @a[distance=..16] ~ ~ ~ 0.35 1.6
