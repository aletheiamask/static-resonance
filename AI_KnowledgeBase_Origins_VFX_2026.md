# AI Knowledge Base: Minecraft Origins + VFX Architecture (2026)
origins https://origins.readthedocs.io/en/latest/guides/data/define_origin/
MCreator https://github.com/MCreator/MCreator
Effekseer https://github.com/effekseer/Effekseer


# Purpose

This document is written for AI consumption and human developers.
Focus: - Origins - Apoli - Minecraft Engine - VFX - Effect
Architecture - AI-assisted Design

# Core Principle

Origins is NOT an effect engine.

Origins → controls gameplay logic

Minecraft → executes gameplay systems

VFX → visual feedback

Never mix these responsibilities.

------------------------------------------------------------------------

# Layer Architecture

Minecraft Engine ↓ Vanilla Systems ↓ Shared Systems ↓ Feature Modules ↓
Origins ↓ Visual Effects

------------------------------------------------------------------------

# Responsibility

Origins - Powers - Conditions - Actions - Resources

Minecraft - Entity - Block - Damage - Attributes - Status Effects -
Commands

VFX - Particle - Beam - Trail - Mesh - Light - Animation - Sound

------------------------------------------------------------------------

# Effect Pipeline

Input ↓ Check Conditions ↓ Consume Resources ↓ Play Animation ↓ Spawn
VFX ↓ Play Sound ↓ Deal Damage ↓ Apply Effects ↓ Cleanup

------------------------------------------------------------------------

# Timeline Example

0.00 Spawn

0.10 Charge

0.25 Flash

0.40 Beam

0.60 Damage

0.80 Explosion

1.00 Destroy

------------------------------------------------------------------------

# VFX Components

-   Particle
-   Beam
-   Trail
-   Ring
-   Shockwave
-   Mesh
-   Decal
-   Light
-   Camera Shake
-   Sound
-   Screen FX

------------------------------------------------------------------------

# Every Effect Should Define

Name Description Trigger Duration Cooldown Timeline Emitter Particle
Sound Animation Damage Hit Detection Cleanup

------------------------------------------------------------------------

# Coordinate System

Position Rotation Scale Offset Forward Right Up

Use vectors instead of hardcoded positions.

------------------------------------------------------------------------

# Hit Detection

Methods: - Bounding Box - Area Of Effect - Raycast - Projectile - Entity
Collision

Never create duplicate hit logic.

------------------------------------------------------------------------

# Resource System

Use Resources/Scoreboards for: - Mana - Charge - Energy - Rage - Heat -
Combo

Do NOT use Status Effects as numeric storage.

------------------------------------------------------------------------

# Status Effects

Use for: - Buff - Debuff - Temporary State - Timers

Avoid using as permanent gameplay variables.

------------------------------------------------------------------------

# AI Workflow

User Idea ↓ Analyze ↓ Split into: Gameplay Resources Conditions Timeline
Animation VFX Damage Optimization ↓ Generate JSON Generate mcfunction
Generate Documentation

------------------------------------------------------------------------

# Folder Structure

origins/ powers/ systems/ actions/ conditions/ resources/ damage_types/
functions/ particles/ sounds/ tags/ assets/ debug/

------------------------------------------------------------------------

# Naming

feature/category/name

Examples:

static/active/discharge

dragon/passive/fly

Avoid: power1 test newskill

------------------------------------------------------------------------

# Best Practices

-   One Power = One Responsibility
-   Reuse Conditions
-   Reuse Actions
-   Centralize Systems
-   Modular Design
-   Feature-based Structure
-   DRY
-   Separation of Concerns
-   Performance First

------------------------------------------------------------------------

# AI Recommendation

AI should work as:

System Architect Gameplay Designer State Machine Designer Timeline
Designer VFX Designer JSON Generator Documentation Generator

Instead of only writing JSON.
