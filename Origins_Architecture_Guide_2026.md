# Origins Architecture Guide (2026)

## Goals

-   Scalable
-   Reusable
-   Modular
-   Git-friendly
-   DRY
-   Separation of Concerns

## Layer Architecture

1.  origins/

-   Origin definitions only
-   Name, icon, description, power list

2.  powers/

-   Business logic
-   Organize by feature:
    -   passive/
    -   active/
    -   combat/
    -   movement/
    -   defense/
    -   weakness/

3.  systems/ Shared gameplay systems:

-   charge
-   mana
-   stamina
-   cooldown
-   combo
-   electricity

4.  actions/ Reusable actions:

-   damage
-   particle
-   sound
-   teleport
-   spawn
-   utility

5.  conditions/ Reusable checks:

-   has_charge
-   is_running
-   is_wet
-   is_day
-   holding_weapon

6.  predicates/ Minecraft predicates.

7.  resources/ Shared resource values:

-   charge
-   mana
-   rage
-   energy

8.  damage_types/ Custom damage:

-   electric
-   fire
-   ice
-   poison
-   holy

9.  functions/ Long mcfunctions:

-   combat/
-   particle/
-   utility/
-   debug/

10. tags/ Shared tags:

-   swords
-   conductive_blocks
-   undead

11. assets/

-   textures
-   particles
-   sounds
-   lang
-   gui

## Feature Module Example

static/ - origin.json - powers/ - particles/ - functions/ - sounds/ -
textures/ - lang/

## Shared Library

Shared reusable content: - actions - conditions - resources - damage -
tags - functions

## Dependency

Minecraft ↓ Shared Library ↓ Core Systems ↓ Feature Modules ↓ Origins

## Naming Convention

feature/category/name

Examples: - static/active/discharge - static/passive/generate_charge -
dragon/active/fireball

Avoid: - power1 - newpower - finalskill

## Best Practices

-   Origins only reference powers.
-   One power = one responsibility.
-   Reuse systems instead of duplicating logic.
-   Keep conditions reusable.
-   Use tags instead of hardcoding item lists.
-   Keep long logic inside mcfunctions.
-   Build common gameplay systems before creating many Origins.
