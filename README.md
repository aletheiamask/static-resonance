<div align="center">

<img src="docs/images/icon.png" width="160" alt="Pixel portrait of Pipothai's character: pink hair, violet eyes, a small Pikachu hair clip">

# ⚡ Static Resonance

**The Origin of Pipothai's own character, shared so anyone who likes it can use it.**<br>
Five electrostatic modes for Origins on Minecraft 1.20.1 Forge.

[![Modrinth](https://img.shields.io/modrinth/dt/static-resonance?style=for-the-badge&logo=modrinth&label=Modrinth&color=8fe3cf&labelColor=5b3a29)](https://modrinth.com/mod/static-resonance)
[![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-ff8fb1?style=for-the-badge&labelColor=5b3a29)](https://modrinth.com/mod/static-resonance/versions)
[![Forge 47.x](https://img.shields.io/badge/Forge-47.x-b8a4ff?style=for-the-badge&labelColor=5b3a29)](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
[![Requires Origins](https://img.shields.io/badge/requires-Origins%20(Forge)-ffe58f?style=for-the-badge&labelColor=5b3a29)](https://modrinth.com/mod/origins-forge)

[Download on Modrinth](https://modrinth.com/mod/static-resonance) · [Wiki](https://tarcoparoni.ropmuang.workers.dev/static-resonance/wiki) · [Discord](https://discord.gg/mtQm4trmuv) · [YouTube](https://www.youtube.com/@Tuna_Sandwich_TOT)

</div>

One origin with five modes, a Charge bar from 0 to 100, free wall climbing and a few passives. I made it for fun as my own character's power, and it's here for anyone who wants to play it.

## 🍬 How to play

**Hold R** opens the skill wheel (it only selects, never casts). **F** casts the current mode.

| Mode | Skill | Charge | What it does |
|---|---|---:|---|
| Dust | Dust Curtain | 5 / 10 | Burst on the target you look at, or a smoke zone |
| Sense | Static Scan | 8 | One pulse that marks nearby things with private corners only you see (green / white / red) |
| Control | Magnetic Lock | 15 on hit / 40 field | Lock a target; a miss costs nothing. The field grows to 30 blocks while you stand still |
| Aegis | Faraday Cage | 50 | 30 s cage, 120 s cooldown. The emergency raise is free |
| Circuit | Circuit Flip | 8 | Flips the redstone piece you look at for 8 s |

In the wheel, left-click Dust, Sense, Control or Aegis to open its options (styles, scan filters, emergency raise). Tapping R cycles modes without the wheel.

### Passives

- **Static Footing:** knockback resistance.
- **Charged Stability:** extra knockback resistance at Charge 50 or more.
- **Wall climb:** always on and free.
- **Faraday Protocol:** if a hit would drop you below 2 HP, the cage can raise for free (toggle it in the Aegis options).
- **Grounded:** water and rain leak Charge, and you take +25% damage while wet.

Particles are white charge and lightning. Scan see-through is off by default and the server decides it (`visuals.seeThroughWalls`). This is not a cheat client.

## 🧁 Install

1. Minecraft **1.20.1** with **Forge 47.x**.
2. Install **[Origins (Forge)](https://modrinth.com/mod/origins-forge) 1.10+** on the client and the server.
3. Drop `static_resonance-1.0.28.jar` into `mods/` on both sides. Client and server must run matching versions; 1.0.28 can't connect to 1.0.27 or older.
4. Optional: [Cloth Config](https://modrinth.com/mod/cloth-config) lets you edit the numbers in game (Mods → Static Resonance → Config).
5. Make a world and pick **Static Resonance** in the origin screen.

Release jars from 1.0.28 on are signed by Tacoparoni Studio. Check one with `jarsigner -verify static_resonance-1.0.28.jar`.

## 🍭 Build from source

Needs JDK 17.

```bat
gradlew.bat build
gradlew.bat test
gradlew.bat runClient
```

The jar lands in `build/libs/`. `test` runs the formula tests without booting Minecraft.

Origins (Forge) comes from CurseMaven (`curse.maven:origins-forge-474438:5311982`). If that fails, put the Origins jar for 1.20.1 into `run/mods`. Apoli and Calio ship inside Origins as jar-in-jar, so `libs/` holds extracted copies for compiling only; see [`libs/README.md`](libs/README.md).

Gameplay is a hybrid: Origins/Apoli handles mode gates and resource bars, and Java services spend Charge and apply effects so the bar can't desync. The wheel and the scan outlines are Java.

```
src/main/java/com/staticresonance/
  mode/      spend/apply (Dust, Faraday, Control, Scan, Circuit, Climb)
  logic/     pure formulas with unit tests
  client/    skill wheel, outlines
  network/   cast, mode and option packets
src/main/resources/data/static_resonance/
  origins/   origin definition
  powers/    resources, passives, weakness
  functions/ one-shot effects
docs/        skill catalog (source of truth), playtest, architecture
vfx-specs/   wheel and effect designs
```

Start with [`docs/README.md`](docs/README.md). If a design doc disagrees with the skill catalog, the catalog wins.

## 🍫 ภาษาไทย

นี่คือพลังออริจินของตัวละครโคลเอ้ นำมาแจกให้คนที่ชอบได้ใช้กัน

- Origin ไฟฟ้าสถิต 5 โหมด ประจุ 0–100 ปีนกำแพงได้
- กดค้าง R เลือกโหมด · F ใช้สกิล
- ต้องมี Origins (Forge) 1.10 ขึ้นไป ทั้งไคลเอนต์และเซิร์ฟ
- พาสซีฟ: ต้าน knockback · ประจุตั้งแต่ 50 ต้านเพิ่ม · ปีนกำแพงฟรี · Faraday ฉุกเฉินเมื่อเลือดจะต่ำกว่า 2 · น้ำ/ฝนทำให้ประจุรั่ว

## 📜 License

© 2026 Pipothai. All Rights Reserved. See [`LICENSE`](LICENSE).

You can download it, play it and put it in modpacks (keep the name and credit Pipothai). Please don't reupload it as your own, rebrand it or sell it. The source is public so people can read it and learn from it; it doesn't come with a license to publish modified copies.

ลิขสิทธิ์สงวน เล่นและใส่แพ็กได้ ห้ามรีอัปโหลดเป็นของตัวเอง

AI helped with the code and page text under Pipothai's direction. No AI images.<br>
Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
