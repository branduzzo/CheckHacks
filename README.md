<div align="center">

<img src="https://i.imgur.com/4Shm5oc.png" alt="CheckHacks" height="60" />

**Catch hackers by their mods. 100% server side, no client mod needed.**

It uses the **Sign Translation Vulnerability** (MC-265322), the same trick DonutSMP uses to catch cheaters. Signs pop up for just a split second, too fast to actually read.

[![Version](https://img.shields.io/badge/version-1.3.0-blue.svg?style=for-the-badge)](https://github.com/Branduzzo/CheckHacks/releases)
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

## 🆕 What is new in 1.3.0

This is a pretty big update, so here is a quick rundown of what has changed:

- **10 new detections** - we added Trouser Streak, Ui Utils, SeedCrackerX, Simple World Downloader, Glazed, Litematica, Item Scroller, Xaeros Minimap and a few more
- **Auto updater** - the plugin now checks GitHub when the server starts, but only if you have internet. If there is a newer version, anyone with `checkhacks.update` will get a message when they join. You just click it, it runs `/checkhacksupdate`, downloads the new jar, replaces the old one and asks you to restart. That is all
- **Matrix support** - we already supported Grim, Vulcan and Spartan, and now Matrix is included as well. You can enable or disable each one under `detect-flag.anticheats.matrix`
- **DoubleCheck** - if someone gets flagged as `DETECTED` or `PROTECTED`, we run a second confirmation scan right after. It really helps cut down on false positives
- **About 3 times faster** - we optimized the Folia scheduler, signs are now handled more efficiently and we lowered `between-sign-ticks`. A full scan finishes in roughly a third of the time it used to
- **Fewer false flags** - we hardened the whole `evaluateResponse` logic, including empty replies, the `key + letter` edge case, and the `METEOR` vs `TRANSLATE` vs `KEYBIND` exploit preventer check. Bedrock, Geyser and Floodgate skipping is also more reliable now
- **Better webhooks** - you can now enable Discord **Components V2** with `discord.use-components-v2: true`. It sends a clean Container with your accent color, text blocks, a separator and a timestamp. If you prefer the old style, just set it to `false` and you will get the classic embeds
- **Smarter rules** - `command-rules` lets you set a command per mod and per result. We also added `&hack&` so you can write something like `tempban %player% 7d Hack %hack%` and it will expand to `Meteor, Wurst` if both were detected. It works with `%hack%`, `&hack&`, `{hack}`, and for global commands you get a comma separated list of all flagged hacks
- **General optimizations** - single SQLite connection with `WAL`, cached messages, async webhooks, cached reflection and NMS fixes for 1.21.4 and up. It just runs smoother and uses less resources

---

## ❓ So what is CheckHacks?

CheckHacks is a server side anticheat that can tell which mods a player is running without them having to install anything. It works by using the **Sign Translation Vulnerability** (MC-265322).

Here is how it works in practice:

1. You start a check, or it starts automatically when someone joins or your anticheat flags them
2. The plugin quietly places a couple of signs near them
3. It writes special translation keys on those signs
4. Their client opens the sign editor for a split second and then closes it
5. We read what their client sent back and immediately remove the signs

Every mod registers its own translation keys, so a vanilla client and a modded client will respond differently. That difference is what tells us exactly what they have installed.

> 👀 The player might see a sign flicker for a moment, but by the time they notice, it is already gone. It is too fast to read or screenshot.

> 💡 One sign can check up to 3 mods. If you have more, we just send them one after another. You can set the delay yourself.

---

## ✨ Features

### 🔍 Detection

- Fully server side, your players do not need to download anything
- Signs are visible for just a couple of ticks, so they cannot be read
- Three modes, `METEOR`, `TRANSLATE`, `KEYBIND`, and each mod automatically uses the right one
- If someone has exploit protection, they show up as **PROTECTED** instead of being marked as clean by mistake
- If someone does not respond at all, they are also marked as **PROTECTED**. They are probably blocking packets
- The **DoubleCheck** system rechecks every `DETECTED` or `PROTECTED` result to avoid false positives
- You can add your own custom mods to detect in `checkhacks.yml`
- Bedrock players are skipped automatically if their name starts with your prefix like `.` or `*`

### ⚡ Automation

- Auto check on join, with a delay you can configure and an option to run only on first join
- Same for auto language check, also with a first join only option
- Hooks directly into **Grim, Vulcan, Spartan and Matrix**. If they flag someone, we check that player right away
- Cooldown per player so you do not get spammed with automatic checks
- You can set different hack lists for normal checks, join checks and anticheat checks
- Run your own commands when someone is **DETECTED**, **PROTECTED** or clean, plus per mod **command-rules** with a result filter
- Simple placeholders for commands: `%player%`, `%hack%`, `&hack&`, `{hack}`. If two hacks are found you will get `Meteor, Wurst` automatically

### 🔄 Auto Updater

- On startup we check `https://github.com/branduzzo/CheckHacks/releases/latest` in the background. If you are offline we just skip it, nothing gets spammed in console
- If we find a newer `vX.Y.Z`, everyone with `checkhacks.update` will see a message when they join: `CheckHacks 1.3.1 is available! Click here to download.` It is already translated in every `messages/*.yml`
- If they click it, it runs `/checkhacksupdate`, downloads `CheckHacks-X.Y.Z.jar` into `plugins/`, backs up or removes the old jar and says `Download completed! Restart the server`
- You need `checkhacks.update` to see the message and to run the command. It is already included in `checkhacks.*` so server operators have it by default

### 📢 Alerts and Results

- Results go to everyone with `checkhacks.alerts`, and anyone can toggle them with `/chalerts`
- If you started the check but do not have the alerts permission, you still get the result in private so you do not miss it
- Every line tells you the mod and what happened: `DETECTED`, `NOT_DETECTED`, `PROTECTED`, `SKIPPED`
- We also show who started the check and why, like manual check, join check or anticheat flag

### 🌐 Web and Discord

- **SQLite is built in**. Every scan gets saved, you do not need to set anything up
- **Web editor runs right on your server**. You can browse old scans, look up players and start new checks from your browser
- Login is a one time token from `/cheditor`. No passwords to remember
- Two separate **Discord webhooks**, one for hack checks and one for language checks, both now with the **Components V2** toggle
- Webhook placeholders you can use: `&name&` `&checker&` `&reason&` `&hacks&` `&results&` `&lang&`
- With Components V2 we send `flags: 32768` Container with your `accent_color`, Text Display blocks, a Separator and a timestamp footer

### 🎨 Customization

- Configs are split up cleanly: `config.yml` for general settings, `checkhacks.yml` for mods, `checklang.yml` for languages
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
| Freecam | `KEYBIND` |
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

## ⌨️ Commands

| Command | What it does |
|---------|--------------|
| `/checkhacks <player>` | Checks every mod you have configured |
| `/checkhacks <player> meteor-client,freecam` | Only checks the mods you list |
| `/checklang <player>` | Tries to figure out their client language using signs |
| `/checklang <player> en_us,it_it` | Only checks those languages |
| `/cheditor` | Gives you a one time link to the web editor |
| `/chalerts` | Toggles detection alerts on or off *(you can also use `/checkalerts` or `/alerts`)* |
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
├── config.yml          # General stuff + discord.use-components-v2 + prefix and language
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

# checkhacks.yml
detect-flag:
  anticheats: { grim: true, vulcan: true, spartan: true, matrix: true }
doublecheck: true
timeout-ticks: 200
between-sign-ticks: 5 # lower it if you want that faster scan speed
command-if-positive: { enabled: false, command: "tempban %player% 14d Cheating &hack&" }
command-rules:
  meteorclient1: { mod: meteorclient, result: DETECTED, command: "tempban %player% 31d Meteor &hack&" }
# you can use %player%, %hack%, &hack&, {hack}, {player} - they all work
```

---

## 🖥️ Web Editor

The web editor runs right on your server, you do not need to host it somewhere else.

- 📊 Scroll through every old scan and result
- 👤 Look up players you checked before
- ▶️ Start new checks right from your browser
- 🌍 Check language results too
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
