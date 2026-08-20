# Changelog

## 1.0.18

Stable public drop. Replaces the previously listed build.

**EN**

- Circuit: F look-toggles one `#circuit` piece for 8 seconds. No world scan, no Circuit outlines. Miss spends nothing.
- In-game Config (Mods → Static Resonance → Config) when Cloth Config is present. Dedicated-server toml stays authoritative.
- Catalog and README match live defaults: Dust 5/10, Scan 8 (radius 32 default, world toml may differ), Control 15/40, Faraday 50 / 30s / 120s cooldown, Circuit 8.
- Charge 0–100. Hold R selects a mode; F casts. Wall climb is always on.
- Sense see-through boxes default **off**. Set `visuals.seeThroughWalls = true` in the server/common toml to opt in (Modrinth: not a cheat client; server-side opt-in).

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

**TH**

- วงจร: F เล็งอุปกรณ์เรดสโตนชิ้นเดียวสลับ 8 วินาที ไม่สแกนโลก ไม่มีเส้นวงจร พลาดไม่เสียประจุ
- คอนฟิกในเกม (ม็อด → Static Resonance → Config) ถ้ามี Cloth Config ไฟล์เซิร์ฟยังเป็นตัวจริง
- ตัวเลขตรงเกม: ฝุ่น 5/10, สแกน 8 (รัศมีเริ่ม 32, toml โลกอาจต่าง), ล็อกเป้า 15 / สนาม 40, Faraday 50 / 30 วินาที / คูลดาวน์ 120 วินาที, วงจร 8
- ประจุ 0–100 กดค้าง R เลือกโหมด F ใช้สกิล ปีนกำแพงได้ตลอด
- กล่องสแกนทะลุกำแพง**ปิดเป็นค่าเริ่ม** เปิดด้วย `visuals.seeThroughWalls = true` ใน toml ของเซิร์ฟ (ไม่ใช่ cheat client)

ต้องการ Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+
