# UI: Radial Mode Menu

## Status

**Implemented** (client overlay + C2S packets). Nested sub-wheels for skill options.

## UX

1. **Hold R** → open radial (after ~120ms)
2. Segments map to **live G skills**:

```
        ⚡ Faraday Cage   (Aegis / Overcharge)
              TOP

🌪 Dust Curtain              🧲 Magnetic Lock
  LEFT (Dust)                  RIGHT (Control)

              BOTTOM
        👁 Static Scan       (Sense)
```

3. **While open (main)**
   - Mouse is **released** (free cursor)
   - Selection uses aim vector (pointer + scroll)
   - **Left-click** on Faraday or Control → open **sub-wheel**
   - **Release R** → confirm hovered mode (deadzone = cancel) — **only way to close**
   - Insufficient charge → wedge **grey** (Faraday never greys — can borrow)
   - Skill **symbol** on each wedge (`*` `o` `+` `#`)
4. **Sub-wheel**
   - **Left-click** option wedges: toggle / select (green = ON, grey = OFF)
   - **Center** or **RMB** = back to main (menu stays open while R held)
   - **Release R** = close menu only (does **not** change mode)
5. **Tap R** (< ~120ms) → cycle modes
6. Faraday side-panel removed (options live on sub-wheel)

### Faraday sub-wheel
- Full ring: **Emergency** ON (green) / OFF (black) — LMB toggles
- Center: Back

### Control sub-wheel
- Left: **Single Target** (mutex, green when selected)
- Right: **Control Field** (mutex)
- Center: Back

## Visual (Minecraft-style)

| State | Appearance |
|-------|------------|
| Main unselected | Near-black fill |
| Main hovered | Near-white fill |
| Sub ON / active | Green `#2E7D32` → hover `#66BB6A` |
| Sub OFF | Black `#0A0A0A` |
| Chrome | Hotbar light/dark rings, inventory dim |

## Network

| Packet | Purpose |
|--------|---------|
| `SetModePacket` | Select combat mode |
| `ToggleFaradayEmergencyPacket` | Arm/disarm emergency cage |
| `SetControlStylePacket` | 0 = target, 1 = field |

Protocol version: **3**
