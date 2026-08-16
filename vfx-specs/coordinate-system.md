# Coordinate System

Use **entity-local vectors**, never only hardcoded world offsets.

## Basis

| Vector | Meaning |
|--------|---------|
| `origin` | Player eye or feet+1.0 (document per effect) |
| `forward` | Look direction (normalized) |
| `right` | Cross(forward, up) |
| `up` | World up or player up |
| `scale` | Uniform or per-axis size |

## Attach points

| Name | Approx |
|------|--------|
| `feet` | entity position |
| `hips` | +0.9 y |
| `chest` | +1.2 y |
| `head` | +1.6 y |
| `hand_main` | arm socket (future player model) |

## Offsets

Express as: `origin + forward * f + right * r + up * u`

Example Pulse center: `chest + forward * 0.2`
