# Static Resonance

Static Resonance is an **Origins** add-on for **Minecraft 1.20.1 Forge**. It adds one playable origin: five electrostatic combat modes, a 0–100 Charge bar, always-on wall climb, and a few passives.

Created by **Pipothai**. YouTube: [Tuna_Sandwich_TOT](https://www.youtube.com/@Tuna_Sandwich_TOT)

## What it adds

- **Dust** — Dust Curtain: look-hit burst (cost 5) or a smoke zone (cost 10)
- **Sense** — Static Scan: one pulse (cost 8). Private four-corner lock marks for the caster only (green / white / red)
- **Control** — Magnetic Lock on a look target (cost 15 on hit) or a standing Control Field (cost 40)
- **Aegis** — Faraday Cage (cost 50, 30 seconds, 120 second cooldown). Emergency raise is free
- **Circuit** — F look-toggles one redstone/`#circuit` block for 8 seconds. No world scan. No Circuit outlines

Hold **R** for the skill wheel (select only). **F** casts. Numbers live in `config/static_resonance-common.toml`. With Cloth Config, Mods → Static Resonance → Config edits that file.

## Passives (always on)

These come with the origin. They are not skills on the wheel.

- **Static Footing** — always resist knockback
- **Charged Stability** — extra knockback resist while Charge is 50+
- **Wall climb** — touch a wall to climb. No Charge
- **Faraday Protocol** — if a hit would leave you below 2 health, Faraday Cage can raise for free. Toggle it from the Aegis sub-panel (hold R → Faraday Cage → left-click)
- **Grounded** (weakness) — water and rain leak Charge. You take 25% more damage while wet

## Why download it

If you already play Origins on Forge 1.20.1 and want a combat origin with a mode wheel instead of a pile of extra keybinds, this is that origin.

## Required before you play

This file does **not** work alone. You need:

- Minecraft **1.20.1**
- **Forge 47.x**
- **[Origins (Forge)](https://modrinth.com/mod/origins-forge) 1.10.x** (Apoli/Calio come inside it)
- This mod on **both** client and server

No extra resource pack. After you create a world, choose the **Static Resonance** origin.

The Origins (Forge) listing on Modrinth is archived; 1.20.1 1.10.x is still the loader this origin targets.

Optional: [Cloth Config](https://modrinth.com/mod/cloth-config) on the client to open Mods → Config. The toml file works without it.

## Not a cheat client

This is an **origin**, not a hacked client. Skills cost Charge. Only the caster sees Scan marks. The **server must also run this mod**.

See-through Scan marks are **off by default**. They are not X-ray for everyone. A server or singleplayer host that wants them must opt in:

```
visuals.seeThroughWalls = true
```

in `config/static_resonance-common.toml` (the dedicated server file is authoritative).

Wall climb is an origin movement skill for this origin only, not a flight hack.

## License

© 2026 Pipothai. **All Rights Reserved.**

You **may** download this mod, play it, and include it in modpacks (keep the name, credit Pipothai, link here).

You **may not** reupload it as your own project, rebrand it, claim authorship, sell it, or ship a modified copy as if it were yours.

## AI assistance

This project was **AI-assisted**. Pipothai directed the origin design, combat rules, and what shipped. Generative AI was used while writing and editing code and this project page. The origin is not an AI-only dump. **No AI-generated images** are used as the icon or on this page.

Please enable Modrinth’s **Contains AI-generated content** disclosure (code + page text).

## ภาษาไทย

ม็อดนี้เพิ่ม origin **Static Resonance** ให้ Origins บน Forge 1.20.1 — ห้าโหมดไฟฟ้าสถิต วงล้อสกิล ประจุ 0–100 ปีนกำแพงได้ มีพาสซีฟติดตัว

**เล่นได้เมื่อมี Origins (Forge) 1.10.x** ใส่ทั้งไคลเอนต์และเซิร์ฟ

กดค้าง R เลือกโหมด F ใช้สกิล ค่าเริ่ม: ฝุ่น 5/10, สแกน 8, ล็อกเป้า 15 / สนาม 40, Faraday 50 / 30 วินาที / คูลดาวน์ 120 วินาที, วงจร 8 (เล็งชิ้นเดียว ไม่สแกนโลก)

พาสซีฟ: เท้าสถิตต้าน knockback ตลอด · ประจุ ≥50 ต้านเพิ่ม · ปีนกำแพงฟรี · Faraday ฉุกเฉินถ้าเลือดจะต่ำกว่า 2 (เปิดจากแผง Aegis) · จุดอ่อน Grounded: น้ำ/ฝนทำให้ประจุรั่ว และรับดาเมจ +25% ตอนเปียก

มาร์คสแกนเป็นสี่มุมล็อกเป้าของคนสแกนเท่านั้น ทะลุกำแพง**ปิดเป็นค่าเริ่ม** — เซิร์ฟเปิดเองด้วย `visuals.seeThroughWalls = true` ไม่ใช่ cheat client

ลิขสิทธิ์สงวน — ดาวน์โหลด เล่น และใส่ในม็อดแพ็กได้ (คงชื่อ เครดิต Pipothai ลิงก์หน้านี้)
ห้ามรีอัปโหลดเป็นโปรเจกต์ตัวเอง / เปลี่ยนแบรนด์ / แอบอ้างว่าเป็นผู้สร้าง / ขาย / ดัดแปลงแล้วปล่อยเป็นของตน

ใช้ AI ช่วยเขียนโค้ดและหน้าโปรเจกต์ ตามคำสั่งของ Pipothai ไม่ใช่โปรเจกต์ที่ AI สร้างทั้งก้อน ไม่มีรูป AI เป็นไอคอน
