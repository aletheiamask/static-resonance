# Changelog

## 1.0.28

Fixes only. Same skills, costs, and keys. Client and server must both update (network protocol 11).

**EN**

- `visuals.seeThroughWalls` is now decided by the server and sent with each scan; a player's own config file no longer turns it on.
- Cloth Config "reset" for see-through walls goes back to the real default (off).
- Mode-switch action bar is translated (Thai included).
- Emergency cage chat shows the configured `faraday.durationTicks` instead of a fixed "30.0s", and is translated.
- Stale descriptions for Dust style and Scan radius match current behaviour.

**TH**

- `visuals.seeThroughWalls` เซิร์ฟเป็นคนตัดสินและส่งมากับการสแกน ไฟล์คอนฟิกในเครื่องผู้เล่นเปิดเองไม่ได้แล้ว
- ปุ่ม reset ใน Cloth Config ของสแกนทะลุกำแพงกลับเป็นค่าเริ่มต้นจริง (ปิด)
- ข้อความตอนเปลี่ยนโหมดแปลแล้ว มีภาษาไทย
- ข้อความกรงฉุกเฉินแสดงเวลาตาม `faraday.durationTicks` แทน "30.0s" ตายตัว และแปลแล้ว
- คำอธิบาย Dust style กับ Scan radius ที่ค้างจากเวอร์ชันเก่าตรงกับของจริงแล้ว

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

## 1.0.27

White static VFX pass. Same skills, costs, and keys. Faraday Cage FX unchanged.

**EN**

- Dust Curtain is charged white grit (ash, cloud, sparks, ground ring) instead of sand.
- Magnetic Lock corona and Control Field edge are ice-white sparks, not witch purple.
- Static Scan keeps green / white / red lock corners and adds a white sonar ring on open.
- Circuit F now discharges into the looked-at block; held pieces tick sparse sparks.
- Mode-switch particles and radial titles share the ice-white charge palette.
- Origin icon is the character portrait (replaces lightning rod).

**TH**

- ม่านฝุ่นเป็นผงประจุขาว (เถ้า / เมฆ / สปาร์ก / วงที่พื้น) เลิกทราย
- ล็อกแม่เหล็กกับขอบสนามเป็นสปาร์กขาวน้ำแข็ง ไม่ใช่ม่วงแม่มด
- สแกนยังใช้มุมเขียว / ขาว / แดง และมีวงโซนาร์ขาวตอนเปิด
- วงจร F ปล่อยประจุเข้าบล็อกที่เล็ง ชิ้นที่ค้างมีสปาร์กเบาๆ
- พาติเคิลสลับโหมดกับชื่อในวงล้อใช้จานสีประจุขาวน้ำแข็ง
- ไอคอนออริจิ้นเป็นรูปตัวละคร แทนสายล่อฟ้า

Requires Minecraft 1.20.1, Forge 47.x, Origins (Forge) 1.10+.

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
