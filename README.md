<div align="center">

<img src="https://i.imgur.com/4Shm5oc.png" alt="CheckHacks" height="80" />

**Catch hackers by their mods. 100% server-side, no client mod needed.**

Uses the **Sign Translation Vulnerability** (MC-265322), the same trick DonutSMP uses to catch cheaters. Signs only pop up for a split second, way too fast to read.

[![Version](https://img.shields.io/badge/version-1.2.1-blue.svg?style=for-the-badge)](https://github.com/Branduzzo/CheckHacks/releases)
[![Platform](https://img.shields.io/badge/platform-Paper%20%7C%20Spigot-orange.svg?style=for-the-badge)](https://papermc.io/)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21+-green.svg?style=for-the-badge)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/java-21+-red.svg?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/license-MIT-yellow.svg?style=for-the-badge)](LICENSE)

[![GitHub](https://img.shields.io/github/downloads/Branduzzo/CheckHacks/total.svg?style=for-the-badge&logo=github&logoColor=white&label=github&color=00Abfg)](https://github.com/Branduzzo/CheckHacks/releases)
[![Modrinth](https://img.shields.io/modrinth/dt/B2gBml7j?style=for-the-badge&logo=modrinth&logoColor=white&label=modrinth)](https://modrinth.com/project/B2gBml7j)
[![Spigot](https://img.shields.io/spiget/downloads/133154?style=for-the-badge&logo=spigotmc&logoColor=white&label=spigot)](https://www.spigotmc.org/resources/checkhacks.133154/)

[![Discord](https://img.shields.io/badge/Discord-join%20the%20server-5865F2.svg?style=for-the-badge&logo=discord&logoColor=white)](https://branduzzo.it/discord)
[![GitHub Stars](https://img.shields.io/github/stars/Branduzzo/CheckHacks.svg?style=for-the-badge&logo=github)](https://github.com/Branduzzo/CheckHacks/stargazers)
[![PayPal](https://img.shields.io/badge/paypal-support%20me-00457C.svg?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/branduzzo)

[Features](#-features) • [Detected Mods](#-detected-mods) • [Commands](#%EF%B8%8F-commands) • [Configuration](#%EF%B8%8F-configuration) • [Web Editor](#%EF%B8%8F-web-editor) • [Support Me](#-support-me)

</div>

---

## ❓ What is CheckHacks?

CheckHacks is a server-side anticheat that figures out which mods a player is running by abusing the **Sign Translation Vulnerability** (MC-265322). The whole check takes a split second. Signs appear and disappear so fast that the player only catches a quick glimpse, and the editor closes before they can read a single line.

### 🔬 How it works

1. A check starts (manually, on join, or from an anticheat flag)
2. The plugin quietly places a few signs near the player
3. It writes special translation keys on them
4. The client opens and closes the sign editor in a split second
5. The plugin reads back what the client resolved, then removes the signs right away

Mods register their own translation keys, so a vanilla client and a modded client answer differently. That's how the plugin knows exactly what's installed.

> 👀 The player might spot a sign flashing for a few instants, but by the time they realize what happened, everything is already gone.

> 💡 One sign can check up to 3 mods. If there are more, signs get sent one after another with a delay you can configure.

---

## ✨ Features

### 🔍 Detection

- Runs fully server-side. No client mod, nothing for the player to install
- Signs stay visible for just a few instants, too fast to be read or screenshotted
- Three detection modes: `METEOR`, `TRANSLATE`, `KEYBIND`. Each mod automatically uses the right one
- Players with exploit protection show up as **PROTECTED** instead of a false negative
- Players who don't answer at all also count as **PROTECTED** (they're probably blocking packets)
- Add your own mods to detect in `checkhacks.yml`
- Bedrock players get skipped automatically if their name starts with your prefix (like `.` or `*`)

### ⚡ Automation

- Auto-check on join, with configurable delay and a first-join-only option
- Auto language check on join, with its own first-join-only option
- Hooks into **Grim**, **Vulcan** and **Spartan**. When they flag someone, a check starts on its own
- Per-player cooldown so automatic checks don't spam
- Different hack lists for normal checks, join checks and anticheat checks
- Run your own commands automatically on **DETECTED**, **PROTECTED** or clean results

### 📢 Alerts & Results

- Results go to everyone with the `checkhacks.alerts` permission, and each player can toggle them with `/chalerts`
- If you ran the check but don't have the alerts permission, you still get the result privately
- Every line shows the mod and what happened: `DETECTED`, `NOT_DETECTED`, `PROTECTED`, `SKIPPED`
- Results include who started the check and why (manual, join check, anticheat flag)

### 🌐 Web & Discord

- Built-in **SQLite database**. Every scan gets saved, no setup needed
- **Web editor** running on your server. Check past scans, browse players, start new checks, all from your browser
- Login works through one-time tokens from `/cheditor`, no passwords to remember
- Two separate **Discord webhooks**: one for hack detections, one for language checks
- Webhook placeholders: `&name&` `&checker&` `&reason&` `&hacks&` `&results&` `&lang&`

### 🎨 Customization

- Configs are split by job: `config.yml` for general settings, `checkhacks.yml` for mods, `checklang.yml` for languages
- Messages live in their own files, switch language with one line in the config
- Every message supports **MiniMessage**, a custom prefix and **PlaceholderAPI**

<details>
<summary>🌍 <b>Included languages</b> (click to expand)</summary>

<br>

`en.yml` · `it.yml` · `de.yml` · `es.yml` · `fr.yml` · `pt.yml` · `ru.yml` · `lolcat.yml` · `uwu.yml`

</details>

---

## 🔍 Detected Mods

| Mod | Mode |
|-----|:----:|
| Meteor Client | `METEOR` |
| LiquidBounce (without EP) | `TRANSLATE` |
| Freecam | `KEYBIND` |
| Wurst Client (1.21-) | `KEYBIND` |
| XRay (Fabric) | `KEYBIND` |
| ChestESP | `KEYBIND` |
| KillAura (Fabric) | `KEYBIND` |
| AutoFish | `KEYBIND` |
| Lumina | `KEYBIND` |
| AutoSwitch | `KEYBIND` |
| BleachHack | `TRANSLATE` |
| Aristois | `TRANSLATE` |
| Coffee Client | `TRANSLATE` |
| World Downloader | `TRANSLATE` |
| AutoClicker (Fabric) | `TRANSLATE` |
| AntiAFK | `TRANSLATE` |
| Auto Clicker (p1k0chu) | `KEYBIND` |

> ➕ Want a mod added? Join the Discord and open a ticket.

---

## ⌨️ Commands

| Command | What it does |
|---------|--------------|
| `/checkhacks <player>` | Checks every configured mod |
| `/checkhacks <player> meteor-client,freecam` | Checks only the mods you list |
| `/checklang <player>` | Figures out the client's language using signs |
| `/checklang <player> en_us,it_it` | Checks only the languages you list |
| `/cheditor` | Gives you a one-time link to the web editor |
| `/chalerts` | Turns detection alerts on or off *(also `/checkalerts` and `/alerts`)* |
| `/chreload` | Reloads all configs, no restart needed |

---

## 🔑 Permissions

| Permission | What it's for |
|------------|---------------|
| `checkhacks.check` | Running hack checks |
| `checkhacks.checklang` | Running language checks |
| `checkhacks.reload` | Reloading the configs |
| `checkhacks.alerts` | Getting and toggling detection alerts |
| `checkhacks.editor` | Making web editor tokens |
| `checkhacks.*` | Everything above |

---

## ⚙️ Configuration

```
plugins/CheckHacks/
├── config.yml          # General settings
├── checkhacks.yml      # Hack checks and custom mods
├── checklang.yml       # Language checks
├── messages/
│   ├── en.yml          # English messages
│   ├── it.yml          # Italian messages
│   └── ...             # de, es, fr, pt, ru, lolcat, uwu
└── database.db         # Scan history, created on its own
```

---

## 🖥️ Web Editor

The web editor runs straight on your server, no external hosting needed.

- 📊 Look through every past scan and result
- 👤 Browse checked players
- ▶️ Start new checks from your browser
- 🌍 See language check results
- 🔐 Locked behind one-time tokens from `/cheditor`

---

## ⚠️ Warning

Mod devs can patch their clients whenever they want and block or spoof this vulnerability. If that happens, detection for that specific client might stop working. I'll do my best to keep up with patches, find workarounds and keep adding new mods.

**It's a cat and mouse game, not a one-time fix.**

---

## ☕ Support Me

CheckHacks is free and always will be. If it saved your server from cheaters and you want to say thanks, you can buy me a coffee on PayPal. Every donation keeps the project alive and helps me stay ahead of mod patches.

[![Support me on PayPal](https://img.shields.io/badge/PayPal-buy%20me%20a%20coffee-00457C.svg?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/branduzzo)

---

## 📜 Credits & Support

Made by **Branduzzo**.

For support, feature requests or mod additions, join the Discord:

[![Join the Discord](https://img.shields.io/badge/Discord-branduzzo.it%2Fdiscord-5865F2.svg?style=for-the-badge&logo=discord&logoColor=white)](https://branduzzo.it/discord)

---

<div align="center">

**If CheckHacks helps your server, drop a ⭐ on the repo!**

[![Download on Modrinth](https://img.shields.io/badge/Download-Modrinth-00AF5C.svg?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/project/B2gBml7j)
[![Download on Spigot](https://img.shields.io/badge/Download-Spigot-FF8C00.svg?style=for-the-badge&logo=spigotmc&logoColor=white)](https://www.spigotmc.org/resources/checkhacks.133154/)

</div>
