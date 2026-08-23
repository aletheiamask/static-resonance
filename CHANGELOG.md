# Changelog

## 1.0.26

Public drop after 1.0.18. Same skills, costs, and keys. More stable on the client (scan + HUD).

**EN**

- Static Scan marks are four lock corners, drawn after the world so Oculus + Complementary no longer stick boxes to the sky or smear them on the screen.
- Scan color no longer turns grey from world fog (Nether / long range).
- Idle clients skip unused scan work. Ally list clears on logout.
- Hold-R skill wheel no longer shows two nested grey rectangles.

**TH**

- มาร์คสแกนเป็นสี่มุมล็อกเป้า วาดหลังโลกแล้ว กัน Oculus + Complementary ฉายกล่องขึ้นฟ้าหรือป้ายติดจอ
- สีสแกนไม่กลายเป็นเทาจากหมอกโลก (เนเธอร์ / ระยะไกล)
- ตอนไม่ได้สแกนไม่ทำงานเกิน รายชื่อพันธมิตรเคลียร์ตอนออกเซิร์ฟ
- กดค้าง R ไม่มีสี่เหลี่ยมเทาซ้อนสองชั้น

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

## 1.0.25

**EN**

- Scan lock corners skip leftover world fog after composite (Nether / long range stay the mark color).
- Idle clients do not copy camera matrices or rebuild the scan cache. Shader-pack detection is cached and no longer forces see-through just because Oculus is installed.
- Ally list clears on logout.

**TH**

- สี่มุมสแกนไม่โดนหมอกโลกหลังเชดเดอร์ (เนเธอร์/ระยะไกลยังเป็นสีมาร์ค)
- ตอนไม่ได้สแกนไม่จับ matrix / ไม่รีบิลด์แคช ถามเชดเดอร์แบบแคช ไม่บังคับทะลุกำแพงแค่เพราะมี Oculus
- รายชื่อพันธมิตรเคลียร์ตอนออกเซิร์ฟ

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

## 1.0.20

**EN**

- Sense marks are four camera-facing lock corners instead of a full hitbox wireframe. Same private overlay, same through-walls path, fewer lines.
- Marks breathe with a slow alpha pulse.

**TH**

- มาร์คสแกนเป็นสี่มุมล็อกเป้าหันเข้ากล้อง เลิกกล่องฮิตบ็อกซ์เต็ม ยังเห็นคนเดียว ยังทะลุกำแพง เส้นน้อยลง
- มาร์คพัลส์สว่าง-จางเบาๆ

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

## 1.0.19

**EN**

- Sense scan boxes draw after the world (and shader composite), not during translucent blocks. Stops Oculus + Complementary from projecting private outlines into the sky / smearing them onto the screen with TAA.
- See-through walls is a depth-test flag on that overlay. Scan no longer uses a custom `RenderType.lines()` that shader packs intercept.
- When an Iris/Oculus shader pack is active, scene depth is ignored so the default (`seeThroughWalls = false`) still shows boxes.
- View and projection are snapshotted at `AFTER_ENTITIES`. If that snapshot is missing, the overlay skips the frame instead of using a composite matrix.
- Boxes behind the camera are culled so inverted line geometry cannot paste onto the viewport.
- Scan boxes interpolate with the camera partial tick.

**TH**

- กล่องสแกนวาดหลังโลกและเชดเดอร์จบ ไม่ใช่ตอนบล็อกโปร่งแสง กัน Oculus + Complementary ฉายเส้นขึ้นฟ้า / ป้ายติดจอจาก TAA
- ทะลุกำแพงเป็นแค่ปิด depth test บน overlay นั้น ไม่ใช้ `RenderType.lines()` ที่แพ็คเชดเดอร์ดัก
- ถ้าเปิดแพ็คเชดเดอร์ Iris/Oculus จะไม่ใช้ depth ของซีน ค่าเริ่ม `seeThroughWalls = false` ยังเห็นกล่อง
- จับ view/projection ตอน `AFTER_ENTITIES` ถ้าไม่มีของเฟรมนั้นจะไม่วาด ไม่ใช้ matrix ของ composite
- กล่องหลังกล้องถูกตัด ทิ้งเส้นที่พลิกติดจอ
- กล่องตามตำแหน่งเอนทิตี้แบบ partial tick

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

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
