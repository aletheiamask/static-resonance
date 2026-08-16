effect give @s minecraft:resistance 6 4 true
effect give @s minecraft:fire_resistance 6 0 true
# Projectile intercept is Java every tick (FaradayService) — 5-tick functions leak arrows.
function static_resonance:faraday/particles
