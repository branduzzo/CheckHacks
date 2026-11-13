# test-bot

A Mineflayer bot for checking Veneer against a local offline-mode server.

```
npm ci
node bot.js [--name Bot1] [--host 127.0.0.1] [--port 25565] [--version 1.21.4] [--brand vanilla] [--cached <pack uuids>]
```

- `--brand` sets the client brand the bot sends (spoof detection).
- `--cached` makes the bot answer the listed resource packs as already downloaded (RPTrack).

`tools/run-test.sh` builds the plugin, starts `run-server/` with it and connects the bot.
