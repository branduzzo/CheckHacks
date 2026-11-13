const mineflayer = require('mineflayer');

const args = process.argv.slice(2);
function getArg(name, def) {
  const idx = args.indexOf(name);
  if (idx !== -1 && idx + 1 < args.length) return args[idx + 1];
  return def;
}

const username = getArg('--name', 'FlayerBot_' + Math.floor(Math.random() * 1000));
const host = getArg('--host', '127.0.0.1');
const port = parseInt(getArg('--port', '25510'));
const version = getArg('--version', false); // false = auto
const brand = getArg('--brand', 'vanilla');

// Set of pack hashes or uuids we want to simulate as "already cached" in our client
const cachedPacks = new Set((getArg('--cached', '')).split(',').filter(Boolean));

console.log(`[Bot] Connecting ${username} to ${host}:${port} (brand: ${brand})...`);

const bot = mineflayer.createBot({
  host,
  port,
  username,
  version: version || undefined,
  brandCustom: brand,
  hideErrors: false
});

bot.on('login', () => {
  console.log(`[Bot ${username}] Successfully logged in!`);
});

bot.on('spawn', () => {
  console.log(`[Bot ${username}] Spawned in the world.`);
});

bot.on('message', (msg) => {
  console.log(`[Chat -> ${username}]: ${msg.toString()}`);
});

// Handle resource pack probing & downloading
bot._client.on('add_resource_pack', (data) => {
  const uuid = data.uuid;
  const url = data.url;
  console.log(`[RPTrack -> ${username}] Received pack probe: ${url} (uuid: ${uuid})`);

  // First send ACCEPTED (3)
  bot._client.write('resource_pack_receive', {
    uuid,
    result: 3 // ACCEPTED
  });

  // Decide if this pack is simulated as cached (0 = SUCCESSFULLY_LOADED) or not (2 = FAILED_DOWNLOAD)
  setTimeout(() => {
    let success = false;
    if (url.includes('/hide.zip') || url.includes('/fp/')) {
      // Actually downloadable server packs (the hide pack or assigned packs)
      success = true;
    } else if (cachedPacks.has(uuid) || cachedPacks.has('all')) {
      success = true;
    }

    bot._client.write('resource_pack_receive', {
      uuid,
      result: success ? 0 : 2 // 0: SUCCESSFULLY_LOADED, 2: FAILED_DOWNLOAD
    });
  }, 50);
});

bot.on('kicked', (reason) => {
  console.log(`[Bot ${username}] Kicked:`, reason);
});

bot.on('error', (err) => {
  console.log(`[Bot ${username}] Error:`, err);
});

bot.on('end', () => {
  console.log(`[Bot ${username}] Disconnected.`);
});
