## ❓ What is it?

CheckHacks is an innovative server-side anticheat plugin that detects client-side mods and hack clients using the **Sign Translation Vulnerability** (MC-265322), the same technique used by DonutSMP to catch cheaters on their server. No client-side mod is required, and the entire check happens invisibly: the target player never sees a sign appear or disappear. When a check is triggered, the plugin silently places one or more signs near the player, writes special translation keys on them, forces the client to open and close the sign editor, and reads back the resolved text. Since mods register their own translation keys, a vanilla client and a modded client will always return different responses, revealing exactly which mods are installed.

---

## 🧱 Features

* Uses the Sign Translation Vulnerability (MC-265322), works entirely server-side, no client mod needed
* Checks up to 3 mods per sign, multiple signs sent sequentially with configurable delay
* Three detection modes - **METEOR**, **TRANSLATE**, **KEYBIND**, each mod uses the correct one automatically
* Players with exploit protection are marked as **PROTECTED** instead of false negatives
* Players who don't respond are marked as **PROTECTED** (likely blocking packets)
* Easily add custom mods to detect by adding entries in `checkhacks.yml`
* `/checkhacks <player>` - check all configured mods
* `/checkhacks <player> meteor-client,freecam` - check only specific mods
* `/chreload` - reload all configs without restarting the server
* `/chalerts` (aliases: `/checkalerts`, `/alerts`) - toggle hack detection alerts on/off per player
* `/checklang <player>` - detect the language of a player's Minecraft client using signs
* `/checklang <player> en_us,it_it` - check against specific languages only
* `/cheditor` - open the web editor directly from in-game with a one-time token link
* Auto-check on player join with configurable delay and optional **first-join-only** mode
* Auto language check on player join with its own configurable **first-join-only** mode
* Anticheat integration: triggers automatic checks when **Grim**, **Vulcan** or **Spartan** flags a player
* Configurable cooldown between automatic checks per player to avoid spam
* Separate hack lists for **default checks**, **join checks** and **anticheat-triggered checks**
* Optional commands triggered automatically on **DETECTED**, **PROTECTED** or clean results
* Bedrock player detection: players whose name starts with a configured prefix (e.g. `.` or `*`) are automatically skipped
* Results broadcast to all players with the `checkhacks.alerts` permission, individually toggleable with `/chalerts`
* Results also sent privately to the player who ran the check if they don't have the alerts permission
* Each result line shows the mod name and its individual outcome: **DETECTED**, **NOT_DETECTED**, **PROTECTED**, **SKIPPED**
* Shows checker name and reason (manual check, join check, anticheat flag) in every result
* Discord webhook for hack detection results and a separate one for language check results
* Webhook placeholders: `&name&`, `&checker&`, `&reason&`, `&hacks&`, `&results&`, `&lang&`
* **SQLite database** built-in - all scan results are saved automatically, no external setup needed
* **Web editor** runs directly on your server - view all scans, browse players, run new checks and see lang results from a browser
* Web editor uses time-limited tokens generated with `/cheditor`, no password needed
* Configs are split by function: `config.yml` for global settings, `checkhacks.yml` for hack checks, `checklang.yml` for language checks
* Plugin messages in separate language files (`messages/en.yml`, `it.yml`, `de.yml`, `es.yml`, `fr.yml`, `pt.yml`, `ru.yml`, `lolcat.yml`, `uwu.yml`) - switchable with a single config line
* Every message fully editable with **MiniMessage** formatting, configurable prefix and **PlaceholderAPI** support
* Permissions: `checkhacks.check`, `checkhacks.reload`, `checkhacks.alerts`, `checkhacks.checklang`, `checkhacks.editor`, `checkhacks.*`

---

## 🔍 Detected Mods

| Mod | Mode |
|-----|------|
| Meteor Client | METEOR |
| LiquidBounce (without EP) | TRANSLATE |
| Freecam | KEYBIND |
| Wurst Client (1.21-) | KEYBIND |
| XRay (Fabric) | KEYBIND |
| ChestESP | KEYBIND |
| KillAura (Fabric) | KEYBIND |
| AutoFish | KEYBIND |
| Lumina | KEYBIND |
| AutoSwitch | KEYBIND |
| BleachHack | TRANSLATE |
| Aristois | TRANSLATE |
| Coffee Client | TRANSLATE |
| World Downloader | TRANSLATE |
| AutoClicker (Fabric) | TRANSLATE |
| AntiAFK | TRANSLATE |
| Auto Clicker (p1k0chu) | KEYBIND |
| SeedCrackerX (1.2.1+) | TRANSLATE |

---

## ⚠️ Warning

Mod developers can patch their mods at any time to block or spoof the Sign Translation Vulnerability, potentially making detection unreliable for specific clients. I will do my best to stay ahead of any patches, find bypasses, and continuously add support for new mods of all kinds. This is an ongoing effort, not a one-time solution.
If you have any doubts about how detection works, feel free to DM the Creator on Discord at **@branduzzo.** (with the dot).

---

## 📜 Credits

* **Branduzzo**  - Creator and developer of original plugin
