<div align="center">

<img src="https://i.imgur.com/4Shm5oc.png" alt="CheckHacks" height="60" />

**Catch hackers by their mods. 100% server side, no client mod needed.**

It uses the **Sign Translation Vulnerability** (MC-265322), the same trick DonutSMP uses to catch cheaters. A virtual sign is sent above or below the player and its editor is opened and closed in the same tick, without placing a real sign in the world.

[![Version](https://img.shields.io/badge/version-1.3.2-blue.svg?style=for-the-badge)](https://github.com/Branduzzo/CheckHacks/releases)
[![Platform](https://img.shields.io/badge/platform-Paper%20%7C%20Spigot-orange.svg?style=for-the-badge)](https://papermc.io/)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.x%20%7C%2026.3-green.svg?style=for-the-badge)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/java-21+-red.svg?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/license-MIT-yellow.svg?style=for-the-badge)](LICENSE)

[![GitHub](https://img.shields.io/github/downloads/Branduzzo/CheckHacks/total.svg?style=for-the-badge&logo=github&logoColor=white&label=github&color=00Abfg)](https://github.com/Branduzzo/CheckHacks/releases)
[![Modrinth](https://img.shields.io/modrinth/dt/B2gBml7j?style=for-the-badge&logo=modrinth&logoColor=white&label=modrinth)](https://modrinth.com/project/B2gBml7j)
[![Spigot](https://img.shields.io/spiget/downloads/133154?style=for-the-badge&logo=spigotmc&logoColor=white&label=spigot)](https://www.spigotmc.org/resources/checkhacks.133154/)

[![Discord](https://img.shields.io/badge/Discord-join%20the%20server-5865F2.svg?style=for-the-badge&logo=discord&logoColor=white)](https://branduzzo.it/discord)
[![GitHub Stars](https://img.shields.io/github/stars/Branduzzo/CheckHacks.svg?style=for-the-badge&logo=github)](https://github.com/Branduzzo/CheckHacks/stargazers)
[![PayPal](https://img.shields.io/badge/paypal-support%20me-00457C.svg?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/branduzzo)

[Features](#-features) • [Detected Mods](#-detected-mods) • [Showcase](#-showcase) • [Commands](#%EF%B8%8F-commands) • [Configuration](#%EF%B8%8F-configuration) • [Web Editor](#%EF%B8%8F-web-editor) • [Support Me](#-support-me)

</div>

---

## 🆕 What is new in 1.3.2

- **Invisible sign probe** - the fake sign is placed in air above or below the player, away from the normal view, and the editor is closed in the same tick. This removes the one-tick delay that used to make the GUI visible.
- **Faster scans** - `between-sign-ticks` now defaults to `1` instead of `5`. A full check with DoubleCheck now finishes in a couple of seconds.
- **Minecraft 26.3 and 1.21.x in the same jar** - on 26.x the virtual sign is sent through PacketEvents 2.14; on 1.21.x the same packets are sent through a small NMS/Netty bridge. No real sign block is placed, and the editor is opened and closed in the same tick.
- **PacketEvents is bundled** - you do not need to install it separately. On 26.x it needs the Adventure version shipped with that server; the 1.21.x bridge has no external dependency.
- **Rules are generic** - any mod can have a `command-rules` entry, `result: ANY` matches every result, and rules can be created, edited and deleted from the web editor.
- **Config merger** - new keys and new default detections are added to existing files without touching your values. `command-rules` is user owned and is never re-added.
- **Material 3 web editor** - new Material 3 interface with light and dark themes, a yellow/green CheckHacks palette matching the server prefix, a Rules page and a Settings page that writes `config.yml` and `checkhacks.yml`.
- **`/chalerts` has three states** - `all`, `only-detected` and `off`. Set the default with `alerts.default-mode`.
- **Components V2 fixed** - webhooks now send `?with_components=true` and read the real Discord error, so the HTTP 400 is gone.
- **Faster with many players** - cached reflection, cached sign components, one webhook queue, async database writes and immutable config snapshots.

---

## ❓ So what is CheckHacks?

CheckHacks is a server side anticheat that can tell which mods a player is running without them having to install anything. It works by using the **Sign Translation Vulnerability** (MC-265322).

Here is how it works in practice:

1. You start a check, or it starts automatically when someone joins or your anticheat flags them
2. The plugin picks an air block above or below the player and sends a virtual sign there, without changing the world
3. It writes special translation keys on that sign
4. Their client opens the sign editor and the server closes it in the same tick
5. We read what their client sent back and restore the original client block

Every mod registers its own translation keys, so a vanilla client and a modded client will respond differently. That difference is what tells us exactly what they have installed.

> 👀 Join scans run during terrain loading and stay invisible. In-game scans are invisible in normal conditions because the editor is opened and closed in the same tick; on a heavily lagging client a one-frame flash is still possible.

> 💡 One sign can check up to 3 mods. If you have more, we just send them one after another. You can set the delay yourself.

---

## ✨ Features

### 🔍 Detection

- Fully server side, your players do not need to download anything
- The fake sign is placed in air above or below the player, not in their normal line of sight
- The editor is opened and closed in the same tick, so the sign cannot be read
- Three modes, `METEOR`, `TRANSLATE`, `KEYBIND`, and each mod automatically uses the right one
- If someone has exploit protection, they show up as **PROTECTED** instead of being marked as clean by mistake (not with all mods)
- If someone does not respond at all, they are also marked as **PROTECTED**. They are probably blocking packets
- The **DoubleCheck** system rechecks every `DETECTED` or `PROTECTED` result with a second scan, which cuts down on false positives
- **More detections out of the box** - Trouser Streak, Trouser Streak NewerNewChunks/AnHero, Ui Utils, SeedCrackerX, Simple World Downloader, Glazed, Litematica, Item Scroller, Xaero's Minimap, Baritone, LiquidBounce, Wurst and more are in the table below
- **Fewer false flags** - the response parser handles empty replies and the `key + letter` edge case, and the exploit-preventer logic is checked separately for `METEOR`, `TRANSLATE` and `KEYBIND`. Bedrock, Geyser and Floodgate skipping is also more reliable
- You can add your own custom mods to detect in `checkhacks.yml`
- Bedrock players are skipped automatically if their name starts with your prefix like `.` or `*`

### ⚡ Automation

- Auto check on join, with a delay you can configure and an option to run only on first join
- Same for auto language check, also with a first join only option
- Hooks directly into **Grim, Vulcan, Spartan and Matrix**. If they flag someone, we check that player right away.
- Cooldown per player so you do not get spammed with automatic checks
- You can set different hack lists for normal checks, join checks and anticheat checks
- Run your own commands when someone is **DETECTED**, **PROTECTED** or clean, plus per mod **command-rules** with a result filter
- `command-rules` accepts any mod id and `result: ANY`, `DETECTED`, `NOT_DETECTED`, `PROTECTED` or `SKIPPED`. Global commands get a comma-separated list of every flagged hack
- Command placeholders: `%player%`, `%hack%`, `&hack&`, `{hack}`. With two hacks found, `&hack&` becomes `Meteor, Wurst`; `tempban %player% 7d Hack &hack&` works as expected

### 🔄 Auto Updater

- On startup we check `https://github.com/branduzzo/CheckHacks/releases/latest` in the background. If you are offline we just skip it, nothing gets spammed in console
- If we find a newer `vX.Y.Z`, everyone with `checkhacks.update` will see a message when they join: `CheckHacks 1.3.2 is available! Click here to download.` It is already translated in every `messages/*.yml`
- If they click it, it runs `/checkhacksupdate`, downloads `CheckHacks-X.Y.Z.jar` into `plugins/`, backs up or removes the old jar and says `Download completed! Restart the server`
- You need `checkhacks.update` to see the message and to run the command. It is already included in `checkhacks.*` so server operators have it by default

### ⚡ Performance

- Roughly **3 times faster** than the pre-1.3.0 scheduler: signs are handled more efficiently and `between-sign-ticks` defaults to `1`
- **SQLite with WAL**, a single database connection, cached messages and cached reflection, async webhooks and async database writes
- Immutable config snapshots so a reload cannot change an in-flight check halfway through
- NMS fixes for **1.21.4 and up**, so the legacy bridge stays compatible without extra setup

### 📢 Alerts and Results

- Results go to everyone with `checkhacks.alerts`, and anyone can toggle them with `/chalerts`
- If you started the check but do not have the alerts permission, you still get the result in private so you do not miss it
- Every line tells you the mod and what happened: `DETECTED`, `NOT_DETECTED`, `PROTECTED`, `SKIPPED`
- We also show who started the check and why, like manual check, join check or anticheat flag

### 🌐 Web and Discord

- **SQLite is built in**. Every scan gets saved, you do not need to set anything up
- **Web editor runs right on your server**. You can browse old scans, look up players and start new checks from your browser
- Login is a one time token from `/cheditor`. No passwords to remember
- Two separate **Discord webhooks**, one for hack checks and one for language checks, both with the **Components V2** toggle
- `discord.use-components-v2: true` sends a clean Container with your accent color, Text Display blocks, a Separator and a timestamp footer (`flags: 32768`). Set it to `false` for classic embeds
- We now read Discord's real error response and send `?with_components=true`, so the old HTTP 400 is gone
- Webhook placeholders you can use: `&name&` `&checker&` `&reason&` `&hacks&` `&results&` `&lang&`

### 🎨 Customization

- Configs are split up cleanly: `config.yml` for general settings, `checkhacks.yml` for mods, `checklang.yml` for languages
- `doublecheck: true` runs the second confirmation scan; `detect-flag.anticheats` lets you enable Grim, Vulcan, Spartan and Matrix independently
- Every message is in its own file, you can switch language with one line in the config
- Everything supports **MiniMessage**, your own prefix and **PlaceholderAPI**
- 9 languages included plus all the updater strings: `update-available`, `update-downloading`, `update-complete`, `update-restart-required`, `update-failed`, `update-no-update`

<details>
<summary>🌍 <b>Languages included</b> (click to expand)</summary>

<br>

`en.yml` · `it.yml` · `de.yml` · `es.yml` · `fr.yml` · `pt.yml` · `br.yml` · `ru.yml` · `lolcat.yml` · `uwu.yml`

</details>

---

## 🔍 Detected Mods

| Mod | Mode |
|-----|:----:|
| Meteor Client | `METEOR` |
| LiquidBounce | `TRANSLATE` |
| Freecam | `KEYBIND` |
| Wurst Client (-1.21) | `KEYBIND` |
| Aristois | `TRANSLATE` |
| XRay (Fabric) | `KEYBIND` |
| ChestESP | `KEYBIND` |
| KillAura (Fabric) | `KEYBIND` |
| AutoFish | `KEYBIND` |
| Lumina | `KEYBIND` |
| AutoSwitch | `KEYBIND` |
| BleachHack | `TRANSLATE` |
| Coffee Client | `TRANSLATE` |
| World Downloader | `TRANSLATE` |
| AutoClicker (Fabric) | `TRANSLATE` |
| AntiAFK | `TRANSLATE` |
| Baritone | `TRANSLATE` |
| Auto Clicker (p1k0chu) | `KEYBIND` |
| Trouser Streak | `TRANSLATE` |
| Trouser Streak (NewerNewChunks) | `TRANSLATE` |
| Trouser Streak (AnHero) | `TRANSLATE` |
| Ui Utils | `TRANSLATE` |
| SeedCrackerX | `TRANSLATE` |
| Simple World Downloader | `TRANSLATE` |
| Glazed | `TRANSLATE` |
| Litematica | `TRANSLATE` |
| Item Scroller | `TRANSLATE` |
| Xaeros Minimap | `TRANSLATE` |

> ➕ Want us to add a mod? Join our Discord and open a ticket, we will take care of it.

---

## 🎬 Showcase

[![CheckHacks Showcase - Watch on YouTube](https://img.youtube.com/vi/dAwjMOlm0SQ/maxresdefault.jpg)](https://youtu.be/dAwjMOlm0SQ?t=193)

> ▶️ Click the thumbnail to watch it on YouTube (3:13).

---

## ⌨️ Commands

| Command | What it does |
|---------|--------------|
| `/checkhacks <player>` | Checks every mod you have configured |
| `/checkhacks <player> meteor-client,freecam` | Only checks the mods you list |
| `/checklang <player>` | Tries to figure out their client language using signs |
| `/checklang <player> en_us,it_it` | Only checks those languages |
| `/cheditor` | Gives you a one time link to the web editor |
| `/chalerts` | Cycles alerts: `all` -> `only-detected` -> `off` *(also `/checkalerts`, `/alerts`, or `/chalerts only-detected`)* |
| `/checkhacksupdate` | Gets the latest CheckHacks from GitHub *(needs `checkhacks.update`)* - just click the update message |
| `/chreload` | Reloads all configs, no restart needed |

---

## 🔑 Permissions

| Permission | What it is for |
|------------|---------------|
| `checkhacks.check` | Lets you run hack checks |
| `checkhacks.checklang` | Lets you run language checks |
| `checkhacks.reload` | Lets you reload configs |
| `checkhacks.alerts` | Lets you get alerts and toggle them |
| `checkhacks.editor` | Lets you create web editor tokens |
| `checkhacks.update` | Lets you see update notices and run `/checkhacksupdate` |
| `checkhacks.*` | Gives all of the above |

---

## ⚙️ Configuration

```
plugins/CheckHacks/
├── config.yml          # General, alerts.default-mode, Discord, web editor
├── checkhacks.yml      # Hack checks, your custom mods, detect-flag, command-if-*, command-rules, doublecheck, matrix
├── checklang.yml       # Language checks + discord.use-components-v2
├── messages/
│   ├── en.yml          # English messages plus all the update-* keys
│   ├── it.yml          # Italian
│   └── ...             # de, es, fr, br, ru, lolcat, uwu
└── database.db         # Scan history, it just shows up by itself
```

**Stuff you will actually use**

```yaml
# config.yml and checklang.yml
discord:
  use-components-v2: true # false gives you classic embeds, true gives you the new Components V2 Container
alerts:
  default-mode: "all" # all, only-detected or off

# checkhacks.yml
detect-flag:
  anticheats: { grim: true, vulcan: true, spartan: true, matrix: true }
doublecheck: true
timeout-ticks: 200
between-sign-ticks: 1 # 1 is the fastest; increase it only if your server needs it
command-if-positive: { enabled: false, command: "tempban %player% 14d Cheating &hack&" }
command-rules:
  meteorclient1: { mod: meteorclient, result: DETECTED, command: "tempban %player% 31d Meteor &hack&" }
  freecam1: { mod: freecam, result: ANY, command: "alert %player% freecam" }
# mod can be any hack id, result can be ANY, DETECTED, NOT_DETECTED, PROTECTED or SKIPPED
# you can use %player%, %hack%, &hack&, {hack}, {player} - they all work
```

- `detect-flag.anticheats.matrix: true` enables the Matrix hook; Grim, Vulcan and Spartan have their own toggles next to it
- `doublecheck` repeats any `DETECTED` or `PROTECTED` batch once, which is useful on busy servers
- `discord.use-components-v2` switches between the Components V2 Container and classic embeds
- `command-rules` is fully editable from the web editor, so you do not have to touch YAML for per-mod punishments

---

## 🖥️ Web Editor

The web editor runs right on your server, you do not need to host it somewhere else. 

- 📊 Scroll through every old scan and result
- 👤 Look up players you checked before
- ▶️ Start new checks right from your browser
- 🌍 Check language results too
- 📜 Create, edit and delete command rules without touching YAML
- ⚙️ Change alerts, detection and Discord settings
- 🔐 It is locked behind one time tokens from `/cheditor`, so no shared password

---

## ⚠️ Heads up

Mod developers can patch their clients whenever they want and try to block this trick. If they do, detection for that specific client might stop working for a while. We keep chasing patches, finding bypasses and adding new mods as they come out.

**It is a cat and mouse game, not a one time fix.**

---

## ☕ Support Me

CheckHacks will always be free. If it saved your server from cheaters and you want to say thanks, you can buy me a coffee on PayPal. Every donation keeps this project going and helps me stay ahead of those mod patches.

[![Support me on PayPal](https://img.shields.io/badge/PayPal-buy%20me%20a%20coffee-00457C.svg?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/branduzzo)

---

## 📜 Credits and Support

Made by **Branduzzo**.

Need help, want to request a feature or need a mod added? Come hang out on Discord:

[![Join the Discord](https://img.shields.io/badge/Discord-branduzzo.it%2Fdiscord-5865F2.svg?style=for-the-badge&logo=discord&logoColor=white)](https://branduzzo.it/discord)

---

<div align="center">

**If CheckHacks is helping your server, drop a ⭐ on the repo, it means a lot!**

[![Download on Modrinth](https://img.shields.io/badge/Download-Modrinth-00AF5C.svg?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/project/B2gBml7j)
[![Download on Spigot](https://img.shields.io/badge/Download-Spigot-FF8C00.svg?style=for-the-badge&logo=spigotmc&logoColor=white)](https://www.spigotmc.org/resources/checkhacks.133154/)

</div>
