      // ============================================================================
      // 1. I18N & TOKEN AUTHENTICATION COMPATIBILITY (REQUIRED PLACEHOLDER)
      // ============================================================================
      const I18N = window.VENEER_I18N || {};

      const DEFAULT_I18N = {
        'stats.scans': 'Total Scans',
        'stats.detections': 'Detections',
        'stats.players': 'Known Players',
        'stats.online': 'Online Now',
        'stats.links': 'Alt Links',
        'nav.overview': 'Overview',
        'nav.history': 'History',
        'nav.graph': 'Alt Graph',
        'sidebar.online_players': 'Online Players',
        'action.run_hack': 'Run Hack Check',
        'action.run_lang': 'Run Lang Check',
        'panel.client_details': 'Client Telemetry & Fingerprint',
        'panel.mod_matrix': 'Mod Detection',
        'panel.alts': 'Alts & Linked Accounts',
        'panel.ips': 'Network ASN & IP Telemetry',
        'flag.fake_player': 'FakePlayer / Headless Bot',
        'flag.exploit_preventer': 'ExploitPreventer (Translations Blocked)',
        'flag.version_mismatch': 'Version Mismatch ({0} vs {1})',
        'flag.liquidbounce': 'LiquidBounce Fingerprint Match',
      };

      function t(k, ...args) {
        let str = (I18N && I18N[k]) || DEFAULT_I18N[k] || k;
        args.forEach((arg, idx) => {
          str = str.replace(new RegExp('\\{' + idx + '\\}', 'g'), String(arg));
          str = str.replace(/%[sd]/, String(arg));
        });
        return str;
      }

      // ============================================================================
      // 2. DETERMINISTIC INLINE SVG AVATAR FALLBACK (ZERO BROKEN IMAGES OFFLINE)
      // ============================================================================
      // Image errors do not bubble, so this listens on the way down; the policy forbids onerror attributes
      document.addEventListener('error', (e) => {
        const img = e.target;
        if (img.tagName === 'IMG' && img.dataset.fallback && img.src !== img.dataset.fallback) img.src = img.dataset.fallback;
      }, true);

      function createFallbackAvatarDataUri(name) {
        let hash = 0;
        const s = String(name || 'Steve');
        for (let i = 0; i < s.length; i++) {
          hash = (hash * 31 + s.charCodeAt(i)) | 0;
        }
        const palettes = [
          ['#1e293b', '#3b82f6', '#93c5fd', '#0f172a'],
          ['#1f2937', '#10b981', '#6ee7b7', '#064e3b'],
          ['#31102f', '#ef4444', '#fca5a5', '#450a0a'],
          ['#134e4a', '#0ea5a4', '#99f6e4', '#0f172a'],
          ['#27272a', '#f59e0b', '#fcd34d', '#451a03'],
        ];
        const pal = palettes[Math.abs(hash) % palettes.length];
        let rects = '';
        for (let y = 0; y < 8; y++) {
          for (let x = 0; x < 4; x++) {
            const bit = (Math.abs(hash >> ((y * 4 + x) % 16)) + x * 7 + y * 13) % 4;
            const color = pal[bit];
            rects += '<rect x="' + x * 8 + '" y="' + y * 8 + '" width="8" height="8" fill="' + color + '"/>';
            rects += '<rect x="' + (7 - x) * 8 + '" y="' + y * 8 + '" width="8" height="8" fill="' + color + '"/>';
          }
        }
        const svg =
          '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64" shape-rendering="crispEdges">' +
          rects +
          '</svg>';
        return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
      }

      // ============================================================================
      // 3. INTERACTIVE MOCK FALLBACK STORE (FOR FILE:// OR STANDALONE MODE)
      // ============================================================================
      const MOCK_STORE = {
        operator: {
          player_name: 'Console_SecOps',
          permissions: ['veneer.admin', 'veneer.scan.run', 'veneer.scan.delete', 'veneer.links.view'],
        },
        stats: {
          scans: 1240,
          detections: 87,
          players: 450,
          online: 18,
          links: 34,
        },
        nextScanId: 105,
        players: {
          Steve: {
            name: 'Steve',
            uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
            online: true,
            ping: 42,
            known: true,
            first_seen: 1730000000000,
            last_seen: 1730800000000,
            joins: 35,
            client: {
              live: true,
              brand: 'fabric',
              version_real: '1.21.4',
              version_claimed: '1.20.1',
              version_mismatch: true,
              locale: 'ru_ru',
              channels: ['xaeroworldmap:main', 'litematica:main'],
              channel_count: 2,
              fake_player: false,
              exploit_preventer: true,
            },
            mods: [{ name: "Xaero's WorldMap", last_seen: 1730800000000, via: 'channel' }],
            fingerprint: {
              liquidbounce: false,
              pack_hash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
              last_seen: 1730800000000,
              keys: ['key.xaero.open_map', 'key.litematica.open_gui', 'key.freecam.toggle'],
            },
            ips: [
              {
                ip: '194.87.12.34',
                asn: 'AS49453',
                country: 'RU',
                provider: 'Selectel',
                first_seen: 1730000000000,
                last_seen: 1730800000000,
              },
              {
                ip: '185.15.58.192',
                asn: 'AS200350',
                country: 'RU',
                provider: 'Yandex Cloud',
                first_seen: 1729500000000,
                last_seen: 1730200000000,
              },
            ],
            alts: [
              {
                uuid: '999a79f4-1111-2222-3333-fca90e38aaf5',
                name: 'SteveAlt',
                pct: 94,
                types: ['ip', 'fingerprint'],
              },
              {
                uuid: '771b22c1-8899-4455-a1b2-ccdd11223344',
                name: 'xX_DarkPvP_Xx',
                pct: 82,
                types: ['fingerprint'],
              },
            ],
            scans: [
              {
                id: 101,
                player: 'Steve',
                scan_type: 'hack',
                created_at: 1730800000000,
                results: [
                  { name: "Xaero's Minimap", status: 'DETECTED', line: 'xaero.minimap' },
                  { name: 'Litematica', status: 'PROTECTED', line: 'key.litematica.open_gui' },
                  { name: 'Baritone', status: 'NOT_DETECTED', line: 'baritone.active' },
                  { name: 'Meteor Client', status: 'NOT_DETECTED', line: 'key.meteor-client.open-gui' },
                  { name: 'Freecam (Mod)', status: 'SUSPECTED', line: 'key.freecam.toggle' },
                ],
              },
              {
                id: 98,
                player: 'Steve',
                scan_type: 'lang',
                created_at: 1730750000000,
                results: [
                  { name: "Xaero's WorldMap", status: 'DETECTED', line: 'gui.xaero_world_map' },
                  { name: 'ReplayMod', status: 'SKIPPED', line: 'replaymod.gui.settings' },
                  { name: 'Sodium', status: 'NOT_DETECTED', line: 'sodium.options.title' },
                ],
              },
            ],
          },
          SteveAlt: {
            name: 'SteveAlt',
            uuid: '999a79f4-1111-2222-3333-fca90e38aaf5',
            online: true,
            ping: 46,
            known: true,
            first_seen: 1730100000000,
            last_seen: 1730800500000,
            joins: 12,
            client: {
              live: true,
              brand: 'fabric',
              version_real: '1.21.4',
              version_claimed: '1.21.4',
              version_mismatch: false,
              locale: 'ru_ru',
              channels: ['meteor:client', 'xaerominimap:main', 'fabric:registry/sync'],
              channel_count: 3,
              fake_player: true,
              exploit_preventer: true,
            },
            mods: [
              { name: 'Meteor Client', last_seen: 1730800500000, via: 'channel' },
              { name: "Xaero's Minimap", last_seen: 1730800500000, via: 'channel' },
            ],
            fingerprint: {
              liquidbounce: true,
              pack_hash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
              last_seen: 1730800500000,
              keys: ['key.meteor-client.open-gui', 'baritone.active', 'key.wurst.zoom'],
            },
            ips: [
              {
                ip: '194.87.12.34',
                asn: 'AS49453',
                country: 'RU',
                provider: 'Selectel',
                first_seen: 1730100000000,
                last_seen: 1730800500000,
              },
            ],
            alts: [
              {
                uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
                name: 'Steve',
                pct: 94,
                types: ['ip', 'fingerprint'],
              },
              {
                uuid: '771b22c1-8899-4455-a1b2-ccdd11223344',
                name: 'xX_DarkPvP_Xx',
                pct: 89,
                types: ['ip', 'fingerprint'],
              },
            ],
            scans: [
              {
                id: 102,
                player: 'SteveAlt',
                scan_type: 'hack',
                created_at: 1730800500000,
                results: [
                  { name: 'Meteor Client', status: 'DETECTED', line: 'key.meteor-client.open-gui' },
                  { name: 'Baritone', status: 'DETECTED', line: 'baritone.active' },
                  { name: 'Wurst Client', status: 'PROTECTED', line: 'key.wurst.zoom' },
                ],
              },
            ],
          },
          xX_DarkPvP_Xx: {
            name: 'xX_DarkPvP_Xx',
            uuid: '771b22c1-8899-4455-a1b2-ccdd11223344',
            online: true,
            ping: 118,
            known: true,
            first_seen: 1729900000000,
            last_seen: 1730800900000,
            joins: 84,
            client: {
              live: true,
              brand: 'lunarclient:v2.16.4',
              version_real: '1.21.1',
              version_claimed: '1.20.4',
              version_mismatch: true,
              locale: 'en_us',
              channels: ['lunarclient:pm', 'badlion:mods', 'voicechat:main'],
              channel_count: 3,
              fake_player: false,
              exploit_preventer: false,
            },
            mods: [{ name: 'Simple Voice Chat', last_seen: 1730800900000, via: 'channel' }],
            fingerprint: {
              liquidbounce: true,
              pack_hash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
              last_seen: 1730800900000,
              keys: ['liquidbounce.module.killaura', 'key.hitboxes.expand'],
            },
            ips: [
              {
                ip: '95.216.14.88',
                asn: 'AS24940',
                country: 'DE',
                provider: 'Hetzner Online GmbH',
                first_seen: 1729900000000,
                last_seen: 1730800900000,
              },
            ],
            alts: [
              {
                uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
                name: 'Steve',
                pct: 82,
                types: ['fingerprint'],
              },
              {
                uuid: '999a79f4-1111-2222-3333-fca90e38aaf5',
                name: 'SteveAlt',
                pct: 89,
                types: ['ip', 'fingerprint'],
              },
            ],
            scans: [
              {
                id: 103,
                player: 'xX_DarkPvP_Xx',
                scan_type: 'hack',
                created_at: 1730800900000,
                results: [
                  { name: 'LiquidBounce NextGen', status: 'DETECTED', line: 'liquidbounce.module.killaura' },
                  { name: 'Hitboxes+', status: 'SUSPECTED', line: 'key.hitboxes.expand' },
                  { name: 'ReplayMod', status: 'NOT_DETECTED', line: 'replaymod.gui' },
                ],
              },
            ],
          },
          Alex_Builder: {
            name: 'Alex_Builder',
            uuid: '3b91a100-5512-409e-991a-001122334455',
            online: true,
            ping: 19,
            known: true,
            first_seen: 1728000000000,
            last_seen: 1730801100000,
            joins: 214,
            client: {
              live: true,
              brand: 'vanilla',
              version_real: '1.21.4',
              version_claimed: '1.21.4',
              version_mismatch: false,
              locale: 'en_us',
              channels: ['minecraft:brand'],
              channel_count: 1,
              fake_player: false,
              exploit_preventer: false,
            },
            mods: [],
            fingerprint: {
              liquidbounce: false,
              pack_hash: '8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4',
              last_seen: 1730801100000,
              keys: ['key.attack', 'key.use', 'key.inventory'],
            },
            ips: [
              {
                ip: '81.19.72.104',
                asn: 'AS8402',
                country: 'RU',
                provider: 'VimpelCom',
                first_seen: 1728000000000,
                last_seen: 1730801100000,
              },
            ],
            alts: [],
            scans: [
              {
                id: 100,
                player: 'Alex_Builder',
                scan_type: 'lang',
                created_at: 1730700000000,
                results: [
                  { name: "Xaero's Minimap", status: 'NOT_DETECTED', line: 'xaero.minimap' },
                  { name: 'Litematica', status: 'NOT_DETECTED', line: 'key.litematica.open_gui' },
                  { name: 'Baritone', status: 'NOT_DETECTED', line: 'baritone.active' },
                ],
              },
            ],
          },
          KillauraBot_09: {
            name: 'KillauraBot_09',
            uuid: 'f1993011-dead-beef-cafe-998877665544',
            online: true,
            ping: 4,
            known: true,
            first_seen: 1730790000000,
            last_seen: 1730801200000,
            joins: 3,
            client: {
              live: true,
              brand: 'fabric',
              version_real: '1.21.4',
              version_claimed: '1.19.4',
              version_mismatch: true,
              locale: 'en_us',
              channels: ['baritone:main', 'meteor:client'],
              channel_count: 2,
              fake_player: true,
              exploit_preventer: true,
            },
            mods: [{ name: 'Baritone Automation', last_seen: 1730801200000, via: 'channel' }],
            fingerprint: {
              liquidbounce: true,
              pack_hash: '0000000000000000000000000000000000000000000000000000000000000000',
              last_seen: 1730801200000,
              keys: ['baritone.active', 'key.meteor-client.open-gui'],
            },
            ips: [
              {
                ip: '45.142.212.61',
                asn: 'AS209605',
                country: 'NL',
                provider: 'HostKey B.V.',
                first_seen: 1730790000000,
                last_seen: 1730801200000,
              },
            ],
            alts: [
              {
                uuid: 'e8821011-dead-beef-cafe-998877665500',
                name: 'KillauraBot_08',
                pct: 98,
                types: ['ip', 'fingerprint'],
              },
            ],
            scans: [
              {
                id: 104,
                player: 'KillauraBot_09',
                scan_type: 'hack',
                created_at: 1730801200000,
                results: [
                  { name: 'Baritone', status: 'DETECTED', line: 'baritone.active' },
                  { name: 'Meteor Client', status: 'DETECTED', line: 'key.meteor-client.open-gui' },
                  { name: 'ExploitPreventer Shield', status: 'PROTECTED', line: 'translation.shield.block' },
                ],
              },
            ],
          },
          KillauraBot_08: {
            name: 'KillauraBot_08',
            uuid: 'e8821011-dead-beef-cafe-998877665500',
            online: false,
            ping: 0,
            known: true,
            first_seen: 1730700000000,
            last_seen: 1730785000000,
            joins: 5,
            client: {
              live: false,
              brand: 'fabric',
              version_real: '1.21.4',
              version_claimed: '1.19.4',
              version_mismatch: true,
              locale: 'en_us',
              channels: ['baritone:main'],
              channel_count: 1,
              fake_player: true,
              exploit_preventer: true,
            },
            mods: [{ name: 'Baritone Automation', last_seen: 1730785000000, via: 'channel' }],
            fingerprint: {
              liquidbounce: true,
              pack_hash: '0000000000000000000000000000000000000000000000000000000000000000',
              last_seen: 1730785000000,
              keys: ['baritone.active'],
            },
            ips: [
              {
                ip: '45.142.212.61',
                asn: 'AS209605',
                country: 'NL',
                provider: 'HostKey B.V.',
                first_seen: 1730700000000,
                last_seen: 1730785000000,
              },
            ],
            alts: [
              {
                uuid: 'f1993011-dead-beef-cafe-998877665544',
                name: 'KillauraBot_09',
                pct: 98,
                types: ['ip', 'fingerprint'],
              },
            ],
            scans: [
              {
                id: 95,
                player: 'KillauraBot_08',
                scan_type: 'hack',
                created_at: 1730785000000,
                results: [{ name: 'Baritone', status: 'DETECTED', line: 'baritone.active' }],
              },
            ],
          },
        },
        links: {
          nodes: [
            { id: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Steve', detected: false, links: 2, ips: ['194.87.12.34'], last_seen: 1730800000000 },
            { id: '999a79f4-1111-2222-3333-fca90e38aaf5', name: 'SteveAlt', detected: true, links: 2, ips: ['194.87.12.34'], last_seen: 1730790000000 },
            { id: '853c80ef-3c37-49fd-aa49-938b674adae6', name: 'Alex', detected: false, links: 1, ips: ['185.15.58.192'], last_seen: 1730780000000 },
            { id: '771b22c1-8899-4455-a1b2-ccdd11223344', name: 'DarkPvP', detected: true, links: 2, ips: ['194.87.12.34'], last_seen: 1730700000000 }
          ],
          edges: [
            { source: '069a79f4-44e9-4726-a5be-fca90e38aaf5', target: '999a79f4-1111-2222-3333-fca90e38aaf5', type: 'ip', label: '194.87.12.34', p: 0.94, provider: 'Selectel', country: 'RU' },
            { source: '069a79f4-44e9-4726-a5be-fca90e38aaf5', target: '999a79f4-1111-2222-3333-fca90e38aaf5', type: 'rptrack', label: '18 packs', p: 0.88 },
            { source: '069a79f4-44e9-4726-a5be-fca90e38aaf5', target: '853c80ef-3c37-49fd-aa49-938b674adae6', type: 'client', label: 'fabric, 2 channels', p: 0.35 },
            { source: '999a79f4-1111-2222-3333-fca90e38aaf5', target: '771b22c1-8899-4455-a1b2-ccdd11223344', type: 'ip', label: '194.87.12.34', p: 0.92, provider: 'Selectel', country: 'RU' }
          ]
        },
      };

      function mockFallbackHandler(path, options = {}) {
        const method = (options.method || 'GET').toUpperCase();
        const urlObj = new URL(path, 'http://localhost');
        const pathname = urlObj.pathname;

        if (method === 'GET' && pathname === '/api/validate') {
          return MOCK_STORE.operator;
        }
        if (method === 'GET' && pathname === '/api/stats') {
          return MOCK_STORE.stats;
        }
        if (method === 'GET' && pathname === '/api/players/online') {
          return Object.values(MOCK_STORE.players)
            .filter((p) => p.online)
            .map((p) => {
              const latestScan = (p.scans || [])[0];
              return {
                name: p.name,
                uuid: p.uuid,
                ping: p.ping,
                last_check: latestScan ? latestScan.created_at : p.last_seen,
              };
            });
        }
        if (method === 'GET' && pathname === '/api/players/search') {
          const q = (urlObj.searchParams.get('q') || '').toLowerCase();
          return Object.values(MOCK_STORE.players)
            .filter((p) => p.name.toLowerCase().includes(q) || p.uuid.toLowerCase().includes(q))
            .map((p) => ({ name: p.name, uuid: p.uuid, online: p.online, ping: p.ping }));
        }
        if (method === 'GET' && pathname.startsWith('/api/player/')) {
          const name = decodeURIComponent(pathname.replace('/api/player/', ''));
          return (
            MOCK_STORE.players[name] ||
            Object.values(MOCK_STORE.players).find((p) => p.name.toLowerCase() === name.toLowerCase()) ||
            MOCK_STORE.players.Steve
          );
        }
        if (method === 'GET' && pathname === '/api/scans') {
          const all = [];
          Object.values(MOCK_STORE.players).forEach((p) => {
            (p.scans || []).forEach((s) => all.push({ ...s, player: p.name, uuid: p.uuid }));
          });
          all.sort((a, b) => b.created_at - a.created_at);
          return all;
        }
        if (method === 'POST' && pathname === '/api/scan/run') {
          const body = options.body ? JSON.parse(options.body) : { player: 'Steve', type: 'hack' };
          const target =
            MOCK_STORE.players[body.player] ||
            Object.values(MOCK_STORE.players).find(
              (p) => p.name.toLowerCase() === String(body.player || '').toLowerCase()
            ) ||
            MOCK_STORE.players.Steve;
          const scan = {
            id: MOCK_STORE.nextScanId++,
            player: target.name,
            scan_type: body.type === 'lang' ? 'lang' : 'hack',
            created_at: Date.now(),
            results:
              body.type === 'lang'
                ? [
                    { name: "Xaero's WorldMap", status: 'DETECTED', line: 'gui.xaero_world_map' },
                    { name: 'Litematica', status: 'PROTECTED', line: 'litematica.gui.button.config_menu' },
                    { name: 'MiniHUD', status: 'SUSPECTED', line: 'minihud.gui.title.configs' },
                    { name: 'Sodium', status: 'NOT_DETECTED', line: 'sodium.options.buttons.donate' },
                  ]
                : [
                    { name: "Xaero's Minimap", status: 'DETECTED', line: 'xaero.minimap' },
                    { name: 'Litematica', status: 'PROTECTED', line: 'key.litematica.open_gui' },
                    { name: 'Dojo / Grim Bypass', status: 'SUSPECTED', line: 'key.grim.blink_queue' },
                    { name: 'Baritone', status: target.client.fake_player ? 'DETECTED' : 'NOT_DETECTED', line: 'baritone.active' },
                    { name: 'ThunderHack Recode', status: 'NOT_DETECTED', line: 'key.thunderhack.clickgui' },
                  ],
          };
          target.scans.unshift(scan);
          MOCK_STORE.stats.scans += 1;
          MOCK_STORE.stats.detections += scan.results.filter((r) => r.status === 'DETECTED').length;
          return { ok: true, scan };
        }
        if (method === 'DELETE' && pathname.startsWith('/api/scan/')) {
          const id = Number(pathname.replace('/api/scan/', ''));
          Object.values(MOCK_STORE.players).forEach((p) => {
            p.scans = (p.scans || []).filter((s) => s.id !== id);
          });
          return { ok: true, deleted_id: id };
        }
        if (method === 'GET' && pathname === '/api/links') {
          return MOCK_STORE.links;
        }
        if (method === 'GET' && pathname.startsWith('/api/links/')) {
          const uid = decodeURIComponent(pathname.replace('/api/links/', ''));
          const p = Object.values(MOCK_STORE.players).find(x => x.uuid === uid || x.name === uid) || MOCK_STORE.players.Steve;
          return {
            ips: p.ips || [{ ip: '194.87.12.34', asn: 'AS49453', country: 'RU', provider: 'Selectel' }],
            mods: p.mods || [{ name: "Xaero's WorldMap", last_seen: Date.now() - 3600000, via: 'channel' }],
            fingerprint: { liquidbounce: false, last_seen: Date.now() - 3600000, mask: 0x1f }
          };
        }
        return {};
      }

      const IS_MOCK = window.location.protocol === 'file:';

      // The server's field names, translated once here into the ones the renderers (and the mock store) use.
      function formatProbe(r) {
        const parts = [];
        if (r.probe_key) parts.push(r.probe_key + (r.probe_answer != null ? ' → ' + (r.probe_answer || '(empty)') : ''));
        if (r.probe_channels) parts.push('channels: ' + r.probe_channels);
        return parts.join(' | ');
      }

      function adaptScan(scan) {
        const isLang = scan.type === 'lang';
        return {
          ...scan,
          player: scan.target_name,
          scan_type: scan.type,
          created_at: scan.timestamp,
          results: (scan.results || []).map((r) =>
            isLang
              ? { name: r.language, status: 'LANG', line: r.response }
              : {
                  name: r.hack_name,
                  status: r.result,
                  line: formatProbe(r),
                  answer: r.probe_answer,
                  channels: r.probe_channels,
                }
          ),
        };
      }

      function adaptPlayer(player) {
        const fp = player.fingerprint;
        return {
          ...player,
          scans: (player.scans || []).map(adaptScan),
          alts: (player.alts || []).map((a) => ({ ...a, pct: Math.round(Number(a.pct) * 100) })),
          fingerprint: fp ? { ...fp, pack_hash: fp.bits } : fp,
        };
      }

      function adaptStats(stats) {
        return {
          scans: Number(stats.hack_scans || 0) + Number(stats.lang_scans || 0),
          detections: stats.detected_players,
          players: stats.accounts,
          online: stats.online,
        };
      }

      function adaptResponse(path, data) {
        if (path.startsWith('/api/player/')) return adaptPlayer(data);
        if (path === '/api/stats') return adaptStats(data);
        if (path.startsWith('/api/scans') && Array.isArray(data)) return data.map(adaptScan);
        return data;
      }

      async function apiRequest(path, options = {}) {
        if (IS_MOCK) {
          return mockFallbackHandler(path, options);
        }
        try {
          const res = await fetch(path, {
            ...options,
            headers: {
              'Content-Type': 'application/json',
              ...(options.headers || {}),
            },
          });
          if (res.status === 401) {
            handleSessionExpired();
            throw new Error('Session expired');
          }
          const contentType = res.headers.get('content-type') || '';
          if (!res.ok) {
            let errMsg = 'HTTP ' + res.status;
            if (contentType.includes('application/json')) {
              try {
                const j = await res.json();
                if (j.error) errMsg = j.error;
              } catch (_) {}
            }
            throw new Error(errMsg);
          }
          return adaptResponse(path, contentType.includes('application/json') ? await res.json() : {});
        } catch (_err) {
          if (_err instanceof TypeError) showStatusBanner('Cannot reach the server. The data shown may be out of date.', 'danger');
          throw _err;
        }
      }

      function handleSessionExpired() {
        // Disconnected or expired: the server now shows the sign-in instructions instead of the panel
        window.location.reload();
      }

      // ============================================================================
      // 4. APPLICATION STATE & HELPERS
      // ============================================================================
      // How serious a mod is: 1 cheat (red), 2 forbidden (yellow), 3 tolerated (green). Filled from /api/validate.
      let MOD_LEVELS = {};
      function modLevel(m) {
        if (m && m.level) return m.level;
        const key = String((m && (m.id || m.name)) || m || '').toLowerCase();
        return MOD_LEVELS[key] || (m && m.name && MOD_LEVELS[m.name.toLowerCase()]) || 2;
      }
      const LEVEL_NAMES = {1: 'cheat', 2: 'forbidden mod', 3: 'tolerated mod'};
      function modBadge(m, title) {
        const lvl = modLevel(m);
        return '<span class="badge-DETECTED mod-lvl-' + lvl + '" title="' + escapeHtml(t(LEVEL_NAMES[lvl]) + (title ? ' — ' + title : '')) + '">' + escapeHtml(m.name) + '</span>';
      }
      // Graph heads: the most serious state wins
      const NODE_STATES = [
        ['banned', 0x9ca3af, n => n.banned],
        ['cheats', 0xef4444, n => n.level === 1],
        ['forbidden mods', 0xeab308, n => n.level === 2],
        ['spoof flag', 0xf97316, n => n.spoof],
        ['clean', 0x22c55e, n => n.scanned],
        ['not scanned', 0x3b82f6, () => true],
      ];
      function nodeState(n) { return NODE_STATES.find(s => s[2](n)); }
      function nodeColour(n) { return nodeState(n)[1]; }

      const STATE = {
        activeView: 'overview',
        selectedPlayerName: '',
        currentPlayer: null,
        onlinePlayers: [],
        sidebarFilter: '',
        modFilter: 'ALL',
        historyScans: [],
        historyFilter: 'ALL',
        historyLimit: 50,
        historyMore: false,
        graphData: { nodes: [], edges: [] },
        graphMinPct: 0,
      };

      function formatTimestamp(ms) {
        if (!ms) return '—';
        const d = new Date(ms);
        const yyyy = d.getFullYear();
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');
        const hh = String(d.getHours()).padStart(2, '0');
        const min = String(d.getMinutes()).padStart(2, '0');
        return yyyy + '-' + mm + '-' + dd + ' ' + hh + ':' + min;
      }

      function formatShortCheckTime(ms) {
        if (!ms) return 'Unchecked';
        const d = new Date(ms);
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');
        const hh = String(d.getHours()).padStart(2, '0');
        const min = String(d.getMinutes()).padStart(2, '0');
        return mm + '-' + dd + ' ' + hh + ':' + min;
      }

      function formatCompactUuid(uuid) {
        const s = String(uuid || '');
        if (s.length <= 16) return s;
        return s.slice(0, 8) + '...' + s.slice(-6);
      }

      function escapeHtml(str) {
        return String(str ?? '')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
      }

      let bannerTimer = 0;
      function showStatusBanner(msg) {
        const banner = document.getElementById('statusFeedbackBanner');
        const text = document.getElementById('statusFeedbackText');
        if (banner && text) {
          text.textContent = msg;
          banner.classList.add('visible');
          clearTimeout(bannerTimer);
          bannerTimer = setTimeout(() => banner.classList.remove('visible'), 10000);
        }
      }

      function buildCombinedModMatrix(player) {
        const map = new Map();
        const scans = Array.isArray(player.scans) ? [...player.scans] : [];
        scans.sort((a, b) => a.created_at - b.created_at);
        scans.forEach((scan) => {
          (scan.results || []).forEach((r) => {
            if (r.status === 'LANG') return;
            map.set(r.name.toLowerCase(), {
              name: r.name,
              status: r.status,
              line: r.line,
              channels: r.channels,
              via: scan.scan_type,
              timestamp: scan.created_at,
            });
          });
        });
        (player.mods || []).forEach((m) => {
          const k = m.name.toLowerCase();
          if (!map.has(k)) {
            map.set(k, {
              name: m.name,
              status: 'DETECTED',
              line: m.via ? 'channel:' + m.via : '',
              via: m.via ? 'channel' : 'hack',
              timestamp: m.last_seen || player.last_seen,
            });
          }
        });
        if (player.fingerprint) {
          map.set('liquidbounce fingerprint', {
            name: 'LiquidBounce Fingerprint',
            status: player.fingerprint.liquidbounce ? 'DETECTED' : 'NOT_DETECTED',
            line: player.fingerprint.pack_hash
              ? 'pack_hash:' + player.fingerprint.pack_hash.slice(0, 12)
              : 'rptrack.liquidbounce',
            via: 'fingerprint',
            timestamp: player.fingerprint.last_seen || player.last_seen,
          });
        }
        const order = { DETECTED: 0, SUSPECTED: 1, PROTECTED: 2, SKIPPED: 3, NOT_DETECTED: 4 };
        return Array.from(map.values()).sort(
          (a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9) || a.name.localeCompare(b.name)
        );
      }

      function calculateRisk(player) {
        let score = 0;
        if (player.client?.fake_player) score += 45;
        if (player.client?.exploit_preventer) score += 20;
        if (player.client?.version_mismatch) score += 20;
        if (player.fingerprint?.liquidbounce) score += 50;
        const matrix = buildCombinedModMatrix(player);
        score += matrix.filter((m) => m.status === 'DETECTED').length * 25;
        score += matrix.filter((m) => m.status === 'PROTECTED').length * 10;
        score = Math.min(100, score);
        const level = score >= 60 ? 'CRITICAL' : score >= 25 ? 'ELEVATED' : 'NOMINAL';
        return { score, level };
      }

      const MOD_HINTS = {
        DETECTED: 'The client resolved the key of this mod, so the mod is installed.',
        NOT_DETECTED: 'The client returned the fallback text, so it does not know this key.',
        PROTECTED: 'The client returned the raw key instead of a value: something hides it.',
        SUSPECTED: 'The channel of this mod is registered, but its key does not resolve: likely an Exploit Preventer.',
        SKIPPED: 'Not asked: the scan stopped early (Exploit Preventer or a client that does not resolve signs).',
        TIMEOUT: 'The client did not answer the sign in time, for example because of lag.',
      };
      const VERDICT_HINTS = {
        exploit_preventer: {
          DETECTED: 'The client returned raw keys, or a mod channel is registered while its key does not resolve.',
          NOT_DETECTED: 'The client resolved the control key and no mod channel contradicted a key.',
          UNKNOWN: 'No mod scan with a usable answer since this player joined.',
        },
        fake_player: {
          DETECTED: 'The control line came back empty and the client registered no plugin channels.',
          NOT_DETECTED: 'The client resolved the control key on the probe sign.',
          UNKNOWN: 'No mod scan with a usable answer since this player joined, or the client has channels but did not resolve signs.',
        },
        language: {
          DETECTED: 'The language sign matched a configured language.',
          NOT_DETECTED: 'The client answered, but the text is blocked or matches no configured language.',
          UNKNOWN: 'No language check since this player joined.',
        },
        protocol: {
          DETECTED: 'The real client version does not fit the protocol it connects with.',
          NOT_DETECTED: 'The real client version fits the protocol it connects with.',
          UNKNOWN: 'No version check gave a usable answer since this player joined.',
        },
      };

      function verdictMarkup(check, verdict, detail) {
        const v = verdict || 'UNKNOWN';
        const cls = v === 'DETECTED' ? (check === 'exploit_preventer' ? 'warning' : 'danger') : v === 'NOT_DETECTED' ? 'success' : 'neutral';
        const label = v === 'DETECTED' ? 'Detected' : v === 'NOT_DETECTED' ? 'Not detected' : 'Unknown';
        const hint = t(VERDICT_HINTS[check][v]);
        return '<span class="sec-pill ' + cls + '" data-hint="1" title="' + escapeHtml(hint) + '">' + escapeHtml(t(label)) + '</span>' +
          (detail ? ' <span class="mono">' + escapeHtml(detail) + '</span>' : '');
      }

      function renderChannelGroups(channels) {
        if (!channels.length) return '<span class="mono" style="color: var(--text-muted);">None</span>';
        const groups = new Map();
        channels.forEach((c) => {
          const at = c.indexOf(':');
          const group = at > 0 ? c.slice(0, at) : t('other');
          if (!groups.has(group)) groups.set(group, []);
          groups.get(group).push(c);
        });
        return [...groups.entries()]
          .sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]))
          .map(([name, items]) =>
            '<details class="channel-group"><summary><span class="mono">' + escapeHtml(name) + '</span>' +
            '<span class="channel-group-count">' + items.length + '</span></summary><div class="channel-group-items">' +
            items.map((c) => '<span class="channel-token">' + escapeHtml(c) + '</span>').join('') +
            '</div></details>')
          .join('');
      }

      function lastLanguage(player) {
        const scan = (player.scans || []).find((x) => x.scan_type === 'lang' && x.results && x.results[0] && x.results[0].name !== 'Version' && x.results[0].name !== 'Unknown');
        return scan ? scan.results[0].name : null;
      }

      function statusPillMarkup(status) {
        const map = {
          DETECTED: { cls: 'danger', label: 'DETECTED' },
          PROTECTED: { cls: 'warning', label: 'PROTECTED' },
          SUSPECTED: { cls: 'suspected', label: 'SUSPECTED' },
          NOT_DETECTED: { cls: 'success', label: 'NOT_DETECTED' },
          SKIPPED: { cls: 'neutral', label: 'SKIPPED' },
          TIMEOUT: { cls: 'neutral', label: 'TIMEOUT' },
        };
        const cfg = map[status] || { cls: 'neutral', label: status };
        const hint = MOD_HINTS[status];
        return '<span class="sec-pill ' + cfg.cls + '"' + (hint ? ' data-hint="1" title="' + escapeHtml(t(hint)) + '"' : '') + '>' + escapeHtml(cfg.label) + '</span>';
      }

      // ============================================================================
      // 5. RENDER FUNCTIONS
      // ============================================================================
      function fmtCount(n) {
        return n == null ? '—' : Number(n).toLocaleString();
      }

      function renderStats(stats) {
        const elScans = document.getElementById('statScans');
        const elDet = document.getElementById('statDetections');
        const elPlayers = document.getElementById('statPlayers');
        const elOnline = document.getElementById('statOnline');
        const elLinks = document.getElementById('statLinks');
        if (elScans) elScans.textContent = fmtCount(stats.scans);
        if (elDet) elDet.textContent = fmtCount(stats.detections);
        if (elPlayers) elPlayers.textContent = fmtCount(stats.players);
        if (elOnline) elOnline.textContent = fmtCount(stats.online);
        if (elLinks) elLinks.textContent = fmtCount(stats.links);
      }

      function renderOnlineSidebar() {
        const listEl = document.getElementById('onlinePlayersList');
        const countEl = document.getElementById('sidebarOnlineCount');
        const q = STATE.sidebarFilter.trim().toLowerCase();

        const filtered = STATE.onlinePlayers.filter(
          (p) => !q || p.name.toLowerCase().includes(q) || (p.uuid && p.uuid.toLowerCase().includes(q))
        );

        countEl.textContent = filtered.length + ' active';

        if (filtered.length === 0) {
          listEl.innerHTML = '<div class="empty-box">No matching online players</div>';
          return;
        }

        listEl.innerHTML = filtered
          .map((p) => {
            const isSelected = p.name.toLowerCase() === STATE.selectedPlayerName.toLowerCase();
            const pingCls = p.ping < 60 ? 'good' : p.ping < 110 ? 'warn' : '';
            const fullRecord = IS_MOCK
              ? MOCK_STORE.players[p.name]
              : STATE.currentPlayer && STATE.currentPlayer.name.toLowerCase() === p.name.toLowerCase()
                ? STATE.currentPlayer
                : null;
            const riskObj = fullRecord ? calculateRisk(fullRecord) : null;
            const riskCls = !riskObj
              ? ''
              : riskObj.level === 'CRITICAL' ? 'crit' : riskObj.level === 'ELEVATED' ? 'warn' : 'clean';
            const fallbackUri = createFallbackAvatarDataUri(p.name);
            const latestScanTime =
              p.last_check ||
              (fullRecord && fullRecord.scans && fullRecord.scans[0] && fullRecord.scans[0].created_at) ||
              (fullRecord && fullRecord.last_seen);

            return (
              '<button type="button" class="player-row-btn ' +
              (isSelected ? 'selected' : '') +
              '" data-select-player="' +
              escapeHtml(p.name) +
              '" aria-label="Inspect player ' +
              escapeHtml(p.name) +
              '">' +
              '<div class="player-row-left">' +
              '<img class="player-mini-avatar" src="https://mc-heads.net/avatar/' +
              encodeURIComponent(p.name) +
              '/64" alt="' +
              escapeHtml(p.name) +
              '" referrerpolicy="no-referrer" data-fallback="' +
              fallbackUri +
              '" />' +
              '<div class="player-row-meta">' +
              '<span class="player-row-name">' +
              escapeHtml(p.name) +
              '</span>' +
              (latestScanTime
                ? '<span class="player-row-sub mono" title="Last check timestamp">Check: ' +
                  escapeHtml(formatShortCheckTime(latestScanTime)) +
                  '</span>'
                : '') +
              '</div>' +
              '</div>' +
              '<div class="player-row-right">' +
              '<span class="ping-tag ' +
              pingCls +
              '">' +
              Number(p.ping || 0) +
              'ms</span>' +
              (riskObj ? '<span class="risk-micro-label ' + riskCls + '">' + riskObj.level + '</span>' : '') +
              '</div>' +
              '</button>'
            );
          })
          .join('');
      }

      function renderPlayerDossier(player) {
        if (!player) return;
        STATE.currentPlayer = player;
        STATE.selectedPlayerName = player.name;

        const avatarImg = document.getElementById('dossierAvatarImg');
        const fallbackUri = createFallbackAvatarDataUri(player.name);
        avatarImg.onerror = function () {
          if (this.src !== fallbackUri) {
            this.src = fallbackUri;
          }
        };
        avatarImg.src = 'https://mc-heads.net/avatar/' + encodeURIComponent(player.name) + '/64';
        avatarImg.alt = player.name + ' Avatar';

        document.getElementById('dossierPlayerName').textContent = player.name;
        const onlineBadge = document.getElementById('dossierOnlineBadge');
        onlineBadge.textContent = player.online ? 'ONLINE' : 'OFFLINE';
        onlineBadge.className = 'sec-pill ' + (player.online ? 'success' : 'neutral');

        const risk = calculateRisk(player);
        const riskBadge = document.getElementById('dossierRiskBadge');
        riskBadge.textContent = 'RISK: ' + risk.level;
        riskBadge.className =
          'sec-pill ' +
          (risk.level === 'CRITICAL' ? 'danger' : risk.level === 'ELEVATED' ? 'warning' : 'success');

        document.getElementById('dossierUuidText').textContent = player.uuid;
        document.getElementById('dossierFirstSeen').textContent = formatTimestamp(player.first_seen);
        document.getElementById('dossierLastSeen').textContent = formatTimestamp(player.last_seen);
        document.getElementById('dossierJoins').textContent = String(player.joins || 0);

        // Security Flag Pills
        const flagsStrip = document.getElementById('dossierFlagsStrip');
        const pills = ['<span class="flags-strip-label">Security Heuristics</span>'];

        if (player.client?.fake_player) {
          pills.push(
            '<span class="sec-pill danger">' +
              '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="12" cy="5" r="2"/><path d="M12 7v4"/></svg>' +
              escapeHtml(t('flag.fake_player')) +
              '</span>'
          );
        }

        if (player.client?.exploit_preventer) {
          pills.push(
            '<span class="sec-pill warning">' +
              '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>' +
              escapeHtml(t('flag.exploit_preventer')) +
              '</span>'
          );
        }

        if (player.client?.version_mismatch) {
          pills.push(
            '<span class="sec-pill warning">' +
              '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/></svg>' +
              escapeHtml(
                t('flag.version_mismatch', 'Real ' + player.client.version_real, 'Handshake ' + player.client.version_claimed)
              ) +
              '</span>'
          );
        }

        if (player.fingerprint?.liquidbounce) {
          pills.push('<span class="sec-pill danger">' + escapeHtml(t('flag.liquidbounce')) + '</span>');
        }

        if (pills.length === 1) pills.push('<span class="sec-pill neutral">None</span>');
        flagsStrip.innerHTML = pills.join('');

        // Client Details Card
        const client = player.client || {};
        const verdicts = client.verdicts || {};
        const storedLanguage = client.language || lastLanguage(player);
        document.getElementById('clientBrandVal').innerHTML = client.brand
          ? '<span class="mono">' + escapeHtml(client.brand) + '</span>'
          : '<span class="sec-pill neutral" data-hint="1" title="' + escapeHtml(t('The client did not send a brand.')) + '">' + escapeHtml(t('Unknown')) + '</span>';
        const versionText = client.version_mismatch
          ? 'Real: ' + client.version_real + ' / Claimed: ' + client.version_claimed
          : client.version_real;
        const protocol = client.version_mismatch ? 'DETECTED' : client.version_real ? 'NOT_DETECTED' : 'UNKNOWN';
        document.getElementById('clientVersionVal').innerHTML =
          '<span class="sec-pill ' + (protocol === 'DETECTED' ? 'warning' : protocol === 'NOT_DETECTED' ? 'success' : 'neutral') +
          '" data-hint="1" title="' + escapeHtml(t(VERDICT_HINTS.protocol[protocol])) + '">' +
          escapeHtml(t(protocol === 'DETECTED' ? 'Mismatch' : protocol === 'NOT_DETECTED' ? 'Match' : 'Unknown')) + '</span>' +
          (versionText ? ' <span class="mono">' + escapeHtml(versionText) + '</span>' : '');
        document.getElementById('clientLocaleVal').textContent = client.locale || '—';
        document.getElementById('clientLanguageVal').innerHTML = storedLanguage
          ? '<span class="mono">' + escapeHtml(storedLanguage) + '</span>'
          : verdictMarkup('language', verdicts.language);
        document.getElementById('clientFakePlayerVal').innerHTML = verdictMarkup('fake_player', verdicts.fake_player);
        document.getElementById('clientExploitPreventerVal').innerHTML = verdictMarkup('exploit_preventer', verdicts.exploit_preventer);

        const channels = client.channels || [];
        document.getElementById('clientChannelCount').textContent = String(client.channel_count ?? channels.length);
        document.getElementById('clientChannelsBox').innerHTML = renderChannelGroups(channels);
        document.getElementById('clientPackHashVal').textContent = player.fingerprint?.pack_hash || '—';

        // RPTrack Keys
        const rpKeys = player.fingerprint?.keys || player.fingerprint?.rptrack_keys || player.rptrack_keys || [];
        document.getElementById('clientRpTrackCount').textContent = String(player.fingerprint?.packs ?? rpKeys.length);
        document.getElementById('clientRpTrackKeysBox').innerHTML =
          rpKeys.length > 0
            ? rpKeys.map((k) => '<span class="channel-token">' + escapeHtml(k) + '</span>').join('')
            : '<span class="mono" style="color: var(--text-muted);">None captured</span>';

        renderModMatrixTable(player);
        renderAltsTable(player);
        renderIpsTable(player);
        renderOnlineSidebar();
      }

      function renderModMatrixTable(player) {
        const tbody = document.getElementById('modMatrixTableBody');
        const allItems = buildCombinedModMatrix(player);
        const filtered =
          STATE.modFilter === 'ALL' ? allItems : allItems.filter((item) => item.status === STATE.modFilter);

        if (filtered.length === 0) {
          tbody.innerHTML =
            '<tr><td colspan="5" class="empty-box">No mod signatures match filter (' +
            escapeHtml(STATE.modFilter) +
            '). Click "Run Hack Check" or "Run Lang Check" to probe client.</td></tr>';
          return;
        }

        tbody.innerHTML = filtered
          .map(
            (item) =>
              '<tr>' +
              '<td style="font-weight: 600;">' +
              (item.status === 'DETECTED' ? modBadge(item) : escapeHtml(item.name)) +
              '</td>' +
              '<td class="mono" style="color: var(--text-secondary); font-size: 11px;">' +
              escapeHtml(item.line) +
              '</td>' +
              '<td class="mono" style="color: var(--text-secondary); font-size: 11px; white-space: nowrap;">' +
              escapeHtml(formatShortCheckTime(item.timestamp || player.last_seen)) +
              '</td>' +
              '<td class="mono" style="text-transform: uppercase; font-size: 10.5px; color: var(--text-muted);">' +
              escapeHtml(item.via || 'hack') +
              '</td>' +
              '<td>' +
              statusPillMarkup(item.status) +
              '</td>' +
              '</tr>'
          )
          .join('');
      }

      function renderAltsTable(player) {
        const tbody = document.getElementById('altsTableBody');
        const alts = player.alts || [];
        document.getElementById('altsCountLabel').textContent =
          alts.length + (alts.length === 1 ? ' linked account' : ' linked accounts');

        if (alts.length === 0) {
          tbody.innerHTML =
            '<tr><td colspan="5" class="empty-box">No linked alternate accounts detected for this player.</td></tr>';
          return;
        }

        tbody.innerHTML = alts
          .map((alt) => {
            const barColor =
              alt.pct >= 90 ? 'var(--danger-fg)' : alt.pct >= 75 ? 'var(--warning-fg)' : 'var(--accent-primary)';
            return (
              '<tr>' +
              '<td>' +
              '<button type="button" class="table-link-btn" data-select-player="' +
              escapeHtml(alt.name) +
              '">' +
              escapeHtml(alt.name) +
              '</button>' +
              '</td>' +
              '<td class="mono" style="color: var(--text-secondary); font-size: 11px; white-space: nowrap;" title="' +
              escapeHtml(alt.uuid) +
              '">' +
              escapeHtml(formatCompactUuid(alt.uuid)) +
              '</td>' +
              '<td>' +
              '<div class="conf-meter">' +
              '<span class="mono" style="font-weight: 600;">' +
              Number(alt.pct) +
              '%</span>' +
              '<div class="conf-track"><div class="conf-fill" style="width: ' +
              Math.min(100, Number(alt.pct)) +
              '%; background: ' +
              barColor +
              ';"></div></div>' +
              '</div>' +
              '</td>' +
              '<td style="white-space: nowrap;">' +
              (alt.types || [])
                .map((tp) => '<span class="channel-token" style="margin-right:3px;">' + escapeHtml(tp) + '</span>')
                .join('') +
              '</td>' +
              '<td>' +
              '<button type="button" class="btn btn-secondary" style="padding: 3px 8px; font-size: 10.5px;" data-select-player="' +
              escapeHtml(alt.name) +
              '">Inspect</button>' +
              ((alt.types || []).includes('manual')
                ? ' <button type="button" class="btn btn-danger-ghost" style="padding: 3px 8px; font-size: 10.5px;" data-unlink-alt="' +
                  escapeHtml(alt.uuid) +
                  '" data-unlink-with="' +
                  escapeHtml(player.uuid) +
                  '">Unlink</button>'
                : '') +
              '</td>' +
              '</tr>'
            );
          })
          .join('');
      }

      function renderIpsTable(player) {
        const tbody = document.getElementById('ipsTableBody');
        const ips = player.ips || [];
        document.getElementById('ipsCountLabel').textContent =
          ips.length + (ips.length === 1 ? ' endpoint' : ' endpoints');

        if (ips.length === 0) {
          tbody.innerHTML = '<tr><td colspan="5" class="empty-box">No IP history recorded.</td></tr>';
          return;
        }

        tbody.innerHTML = ips
          .map(
            (row) =>
              '<tr>' +
              '<td class="mono" style="font-weight: 600; white-space: nowrap;">' +
              escapeHtml(row.ip) +
              '</td>' +
              '<td class="mono" style="color: var(--accent-primary); white-space: nowrap;">' +
              escapeHtml(row.asn) +
              '</td>' +
              '<td class="mono">' +
              escapeHtml(row.country) +
              '</td>' +
              '<td>' +
              escapeHtml(row.provider || row.org) +
              '</td>' +
              '<td class="mono" style="color: var(--text-secondary); font-size: 11px; white-space: nowrap;">' +
              formatTimestamp(row.last_seen) +
              '</td>' +
              '</tr>'
          )
          .join('');
      }

      function renderHistoryView() {
        const tbody = document.getElementById('historyTableBody');
        const totalEl = document.getElementById('historyTotalLabel');
        const filtered =
          STATE.historyFilter === 'ALL'
            ? STATE.historyScans
            : STATE.historyScans.filter((s) => s.scan_type === STATE.historyFilter);

        totalEl.textContent = 'Showing ' + filtered.length + ' scans';
        const moreBtn = document.getElementById('historyMoreBtn');
        if (moreBtn) moreBtn.style.display = STATE.historyMore ? '' : 'none';

        if (filtered.length === 0) {
          tbody.innerHTML = '<tr><td colspan="7" class="empty-box">No scan records match current filter.</td></tr>';
          return;
        }

        tbody.innerHTML = filtered
          .map((scan) => {
            const results = scan.results || [];
            const detCount = results.filter((r) => r.status === 'DETECTED').length;
            const protCount = results.filter((r) => r.status === 'PROTECTED').length;
            const cleanCount = results.filter((r) => r.status === 'NOT_DETECTED').length;
            const primaryFindings = results
              .filter((r) => r.status === 'DETECTED' || r.status === 'PROTECTED' || r.status === 'SUSPECTED' || (r.status === 'LANG' && r.line))
              .slice(0, 3)
              .map((r) => escapeHtml(r.name) + ' (' + escapeHtml(r.status === 'LANG' ? r.line : r.status) + ')')
              .join('<br>');

            return (
              '<tr>' +
              '<td class="mono" style="font-weight: 600;">#' +
              Number(scan.id) +
              '</td>' +
              '<td>' +
              '<button type="button" class="table-link-btn" data-select-player="' +
              escapeHtml(scan.player || '—') +
              '">' +
              escapeHtml(scan.player || '—') +
              '</button>' +
              '</td>' +
              '<td><span class="channel-token" style="text-transform: uppercase;">' +
              escapeHtml(scan.scan_type) +
              '</span></td>' +
              '<td class="mono" style="color: var(--text-secondary); font-size: 11px; white-space: nowrap;">' +
              formatTimestamp(scan.created_at).replace(' ', '<br>') +
              '</td>' +
              '<td class="mono" style="font-size: 11px; white-space: nowrap;">' +
              '<span style="color: var(--danger-fg); font-weight:600;">' +
              detCount +
              ' Detected</span><br>' +
              '<span style="color: var(--warning-fg);">' +
              protCount +
              ' Protected</span><br>' +
              '<span style="color: var(--success-fg);">' +
              cleanCount +
              ' Clean</span>' +
              '</td>' +
              '<td style="color: var(--text-secondary); font-size: 11.5px;">' +
              (primaryFindings || (scan.scan_type === 'lang' ? '—' : 'All signatures clean')) +
              '</td>' +
              '<td>' +
              '<button type="button" class="btn btn-danger-ghost" data-delete-scan="' +
              Number(scan.id) +
              '" aria-label="Delete Scan #' +
              Number(scan.id) +
              '">Delete</button>' +
              '</td>' +
              '</tr>'
            );
          })
          .join('');
      }

      // ============================================================================
      // 6. 3D FORCE-DIRECTED ALT GRAPH ENGINE (THREE.JS + 3D-FORCE-GRAPH)
      // ============================================================================
      let graph3d = null, graphRaf = 0, graphRun = 0;

      function stopGraph() {
        if (graphRaf) cancelAnimationFrame(graphRaf);
        graphRaf = 0;
        if (!graph3d) return;
        const g = graph3d;
        graph3d = null;
        try {
          g.scene().traverse(o => {
            if (o.geometry) o.geometry.dispose();
            [].concat(o.material || []).forEach(m => { if (m.map) m.map.dispose(); m.dispose(); });
          });
          if (typeof g._destructor === 'function') g._destructor();
          const r = g.renderer();
          if (r && r.dispose) {
            r.dispose();
            if (r.forceContextLoss) r.forceContextLoss();
          }
          const c = g.controls();
          if (c && c.dispose) c.dispose();
        } catch (e) { console.warn('[links] stopGraph', e); }
      }

      const FACES = [
        [16, 8], [0, 8], [8, 0], [16, 0], [8, 8], [24, 8]
      ];

      function textSprite(text, height, colour) {
        if (typeof THREE === 'undefined') return { visible: false };
        const c = document.createElement('canvas'), g = c.getContext('2d');
        const font = '600 48px system-ui, -apple-system, Segoe UI, sans-serif';
        g.font = font;
        c.width = Math.ceil(g.measureText(text).width) + 20; c.height = 64;
        g.font = font; g.textAlign = 'center'; g.textBaseline = 'middle';
        g.lineWidth = 8; g.strokeStyle = 'rgba(0,0,0,.85)'; g.lineJoin = 'round';
        g.strokeText(text, c.width / 2, 33);
        g.fillStyle = '#ffffff'; g.fillText(text, c.width / 2, 33);
        const t = new THREE.CanvasTexture(c);
        t.colorSpace = THREE.SRGBColorSpace; t.minFilter = THREE.LinearFilter; t.generateMipmaps = false;
        const sprite = new THREE.Sprite(new THREE.SpriteMaterial({map: t, color: colour, transparent: true, depthWrite: false}));
        sprite.scale.set(height * c.width / c.height, height, 1);
        sprite.raycast = () => {};
        return sprite;
      }

      function skinCube(n, size) {
        if (typeof THREE === 'undefined') return null;
        const edge = size, colour = nodeColour(n);
        const group = new THREE.Group();
        const canvases = FACES.map(() => { const c = document.createElement('canvas'); c.width = c.height = 8; return c; });
        const mats = canvases.map(c => {
          const t = new THREE.CanvasTexture(c);
          t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.generateMipmaps = false;
          t.colorSpace = THREE.SRGBColorSpace;
          return new THREE.MeshBasicMaterial({map: t, color: 0xffffff});
        });
        const drawFallback = () => {
          // A plain head with its face on the front only (index 4 is +z), not on all six sides
          canvases.forEach((c, i) => {
            const g = c.getContext('2d');
            g.fillStyle = '#' + nodeColour(n).toString(16).padStart(6, '0');
            g.fillRect(0, 0, 8, 8);
            if (i === 4) {
              g.fillStyle = '#1e293b';
              g.fillRect(1, 3, 2, 2);
              g.fillRect(5, 3, 2, 2);
            }
            mats[i].map.needsUpdate = true;
          });
        };
        {
          const img = new Image();
          img.crossOrigin = 'anonymous';
          img.onerror = drawFallback;
          img.onload = () => {
            const probe = document.createElement('canvas'); probe.width = 32; probe.height = 16;
            const pg = probe.getContext('2d'); pg.drawImage(img, 32, 0, 32, 16, 0, 0, 32, 16);
            const px = pg.getImageData(0, 0, 32, 16).data;
            let hat = false; for (let i = 3; i < px.length; i += 4) if (px[i] < 128) { hat = true; break; }
            canvases.forEach((c, i) => {
              const g = c.getContext('2d'); g.imageSmoothingEnabled = false;
              g.drawImage(img, FACES[i][0], FACES[i][1], 8, 8, 0, 0, 8, 8);
              if (hat) g.drawImage(img, FACES[i][0] + 32, FACES[i][1], 8, 8, 0, 0, 8, 8);
              mats[i].map.needsUpdate = true;
            });
          };
          // Stored skin first; without one (offline-mode servers store none) the service the avatars already use
          img.src = n.skin ? 'data:image/png;base64,' + n.skin : 'https://mc-heads.net/skin/' + encodeURIComponent(n.name);
        }
        const cube = new THREE.Mesh(new THREE.BoxGeometry(edge, edge, edge), mats);
        group.add(cube);
        cube.raycast = () => {};
        const hit = new THREE.Mesh(new THREE.BoxGeometry(edge, edge, edge), new THREE.MeshBasicMaterial({visible: false}));
        group.add(hit);
        n._hit = hit;
        const frame = new THREE.Mesh(new THREE.BoxGeometry(edge * 1.12, edge * 1.12, edge * 1.12),
          new THREE.MeshBasicMaterial({color: colour, side: THREE.BackSide}));
        frame.raycast = () => {};
        group.add(frame);
        const label = textSprite(n.name, 6.5, n.online ? 0x4ade80 : 0xc9d1d9);
        label.center.set(0.5, 1 + edge * 0.866 / 6.5);
        group.add(label);
        n._cube = cube; n._frame = frame; n._label = label;
        return group;
      }

      function provider(r) {
        return [r.org || r.provider, r.asn ? 'AS' + r.asn : '', r.country].filter(Boolean).join(' · ');
      }

      function describeEdge(e) {
        if (e.type === 'rptrack' || e.type === 'fingerprint') return t('fingerprint {0}', t('{0} packs', parseInt(e.label) || 5));
        if (e.type === 'client') return t('client {0}', e.label);
        if (e.type === 'email') return t('same e-mail (AuthMe)');
        if (e.type === 'name') return t('similar name {0}', e.label);
        if (e.type === 'batch') return t('bot batch {0}', e.label);
        if (e.type === 'manual') return t('manual link');
        let s = t('address {0}', e.label === '(hidden)' ? t('(hidden)') : (e.label || 'shared IP'));
        if (e.org !== undefined || e.provider !== undefined) s += ' (' + provider(e) + ')';
        if (e.weak) s += ' — ' + t('VPN / hosting, counts less');
        return s;
      }

      async function showLinks(focus) {
        const box = document.getElementById('graph-box');
        if (!box) return;
        const run = ++graphRun;
        if (typeof ForceGraph3D === 'undefined') {
          box.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--text-muted);font-size:13px;">Loading 3D Force Graph library (3d-force-graph.min.js)...</div>';
          return;
        }
        box.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--text-muted);font-size:13px;">' + t('Loading Alt Graph...') + '</div>';

        let data;
        try {
          data = await apiRequest('/api/links');
        } catch(e) {
          box.innerHTML = '<div style="padding:20px;color:var(--danger-fg);">' + escapeHtml(e.message) + '</div>';
          return;
        }
        // The panel was left, or the graph reopened, while this request was on its way
        if (run !== graphRun || STATE.activeView !== 'graph') return;
        if (!data || !data.nodes || !data.nodes.length) {
          box.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--text-muted);font-size:13px;">' + t('No linked accounts yet. Links appear when accounts join from the same address, share an RPTrack fingerprint or play with the same uncommon client.') + '</div>';
          return;
        }

        let hl = '', searchScale = 1, hover = null, selected = null, fitted = false, ownMoveUntil = 0, lastCam = null, lastLog = 0, viewSpan = 120;
        const matches = n => (n.name || '').toLowerCase().includes(hl) || (n.ips || []).some(ip => ip.startsWith(hl));
        const dbgBox = document.createElement('pre'); dbgBox.className = 'dbg-log'; dbgBox.style.display = 'none';
        const dbgOn = () => document.getElementById('g-dbg') && document.getElementById('g-dbg').checked;
        const dbg = msg => {
          if (!dbgOn()) return;
          const tNow = new Date().toISOString().slice(11, 23);
          console.log('[links] ' + msg);
          dbgBox.textContent = (dbgBox.textContent + tNow + ' ' + msg + '\n').split('\n').slice(-16).join('\n');
          dbgBox.scrollTop = dbgBox.scrollHeight;
        };
        const v3 = p => '(' + Math.round(p.x) + ', ' + Math.round(p.y) + ', ' + Math.round(p.z) + ')';
        const moveCamera = (pos, look, ms, why) => {
          if (!graph3d) return;
          const from = graph3d.camera().position;
          dbg('camera ' + why + ': ' + v3(from) + ' -> ' + v3(pos) + ' looking at ' + v3(look) + ', ' + ms + ' ms');
          ownMoveUntil = Date.now() + ms + 100;
          graph3d.cameraPosition(pos, look, ms);
        };

        const card = document.createElement('div'); card.className = 'node-card'; card.style.display = 'none';
        const info = document.createElement('div'); info.className = 'graph-info';

        // "Only this group": the accounts reachable from this one; opened from a player page it starts on
        let groupRoot = focus || null;
        const gGrpEarly = document.getElementById('g-grp');
        if (gGrpEarly) gGrpEarly.checked = !!focus;

        const build = () => {
          const gIp = document.getElementById('g-ip');
          const gRp = document.getElementById('g-rp');
          const gCl = document.getElementById('g-cl');
          const gEm = document.getElementById('g-em');
          const gNm = document.getElementById('g-nm');
          const gMan = document.getElementById('g-manual');
          const gMin = document.getElementById('g-min');
          const gDays = document.getElementById('g-days');
          const g2d = document.getElementById('g-2d');

          const use = {
            ip: gIp ? gIp.checked : true,
            rptrack: gRp ? gRp.checked : true,
            fingerprint: gRp ? gRp.checked : true,
            client: gCl ? gCl.checked : true,
            email: gEm ? gEm.checked : true,
            name: gNm ? gNm.checked : true,
            batch: gNm ? gNm.checked : true,
            manual: gMan ? gMan.checked : true
          };
          const minPct = gMin ? +gMin.value / 100 : 0;
          const days = gDays ? +gDays.value : 0;
          const recent = new Set(data.nodes.filter(n => !days || !n.last_seen || Date.now() - n.last_seen <= days * 86400000).map(n => n.id));

          const pairs = new Map();
          (data.edges || []).filter(e => use[e.type] && recent.has(e.source) && recent.has(e.target)).forEach(e => {
            const key = e.source < e.target ? e.source + '|' + e.target : e.target + '|' + e.source;
            const l = pairs.get(key) || {source: e.source, target: e.target, miss: 1, types: new Set(), why: []};
            const prob = e.p !== undefined ? e.p : (e.pct ? e.pct / 100 : 0.5);
            l.miss *= 1 - prob;
            l.types.add(e.type);
            l.why.push(describeEdge(e));
            pairs.set(key, l);
          });
          const links = [...pairs.values()].map(l => ({...l, pct: 1 - l.miss})).filter(l => l.pct >= minPct);
          const deg = {};
          links.forEach(l => { deg[l.source] = (deg[l.source]||0) + 1; deg[l.target] = (deg[l.target]||0) + 1; });
          let nodes = data.nodes.filter(n => deg[n.id] || recent.has(n.id)).map(n => ({...n, links: deg[n.id] || 0}));
          const gGrp = document.getElementById('g-grp');
          const root = gGrp && gGrp.checked && groupRoot ? nodes.find(n => n.id === groupRoot || n.name === groupRoot) : null;
          if (root) {
            const next = {};
            links.forEach(l => { (next[l.source] = next[l.source] || []).push(l.target); (next[l.target] = next[l.target] || []).push(l.source); });
            const keep = new Set([root.id]), queue = [root.id];
            while (queue.length) (next[queue.shift()] || []).forEach(o => { if (!keep.has(o)) { keep.add(o); queue.push(o); } });
            nodes = nodes.filter(n => keep.has(n.id));
            for (let i = links.length - 1; i >= 0; i--) if (!keep.has(links[i].source)) links.splice(i, 1);
            focus = root.id;
          } else if (gGrp && gGrp.checked) gGrp.checked = false;

          stopGraph();
          box.innerHTML = '';
          box.appendChild(card);
          box.appendChild(info);
          box.appendChild(dbgBox);

          selected = null; hover = null; fitted = false;
          let framed = false, cameraMovedAt = 0;
          const flatView = !!(g2d && g2d.checked);
          const faceM = new THREE.Matrix4(), faceQ = new THREE.Quaternion(), faceUp = new THREE.Vector3(0, 1, 0), faceFrom = new THREE.Vector3();
          card.style.display = 'none';

          graph3d = ForceGraph3D({controlType: 'orbit'})(box)
            .width(box.clientWidth).height(box.clientHeight)
            .backgroundColor('#05080d')
            .showNavInfo(false)
            .numDimensions(g2d && g2d.checked ? 2 : 3)
            .nodeThreeObject(n => skinCube(n, 9 + Math.min(n.links, 8) * 1.5))
            .nodeLabel(n => escapeHtml(n.name) + ' (' + (n.links === 1 ? '1 link' : n.links + ' links') + ') — ' + escapeHtml(t(nodeState(n)[0])) + (n.online ? ', ' + escapeHtml(t('online')) : ''))
            .linkColor(l => l.types.has('manual') ? '#f85149' : l.types.has('rptrack') || l.types.has('fingerprint') ? '#3fb950' : l.types.has('email') ? '#f59e0b' : l.types.has('ip') ? '#8b949e' : l.types.has('batch') ? '#f472b6' : l.types.has('name') ? '#c084fc' : '#2dd4bf')
            .linkOpacity(0.7)
            .linkWidth(l => 0.4 + l.pct * 2.2)
            .linkLabel(l => Math.round(l.pct * 100) + '% — ' + l.why.map(escapeHtml).join(', '))
            .linkThreeObjectExtend(true)
            .linkThreeObject(l => {
              const tSp = textSprite(Math.round(l.pct * 100) + '%', 6, 0x8b949e);
              tSp.visible = false; l._label = tSp; return tSp;
            })
            .linkPositionUpdate((tSp, {start, end}) => {
              if (tSp.position) {
                tSp.position.set((start.x + end.x) / 2, (start.y + end.y) / 2, ((start.z || 0) + (end.z || 0)) / 2);
              }
            })
            .onNodeClick((n, ev) => { dbg('click on node ' + n.name); select(n); })
            .onBackgroundClick(() => { dbg('click on background'); if (selected) select(null); })
            .onNodeHover(n => { hover = n ? n.id : null; })
            .warmupTicks(120)
            .cooldownTicks(Infinity)
            .cooldownTime(6000)
            .d3VelocityDecay(0.55)
            .onEngineStop(() => {
              if (fitted || selected) return;
              fitted = true;
              fit(600, 'fit after layout');
            })
            .graphData({nodes, links});
          // Tighter than the library's defaults (-30, 30), with room left for the biggest heads
          graph3d.d3Force('charge').strength(-24);
          graph3d.d3Force('link').distance(28);
          // The user took the camera: the end of the layout must not pull it back
          const controls = graph3d.controls();
          controls.addEventListener('start', () => { fitted = true; });
          const flat = !!(g2d && g2d.checked);
          if (flat) {
            // A flat map is dragged, not turned
            controls.enableRotate = false;
            controls.screenSpacePanning = true;
            controls.mouseButtons.LEFT = THREE.MOUSE.PAN;
            controls.touches.ONE = THREE.TOUCH.PAN;
          }
          // The library's zoom shrinks with the distance to the centre, so it nearly stops close up: fixed steps
          // instead, and the centre moves on ahead of the camera rather than stopping it
          controls.enableZoom = false;
          graph3d.renderer().domElement.addEventListener('wheel', ev => {
            ev.preventDefault();
            fitted = true;
            const cam = graph3d.camera(), centre = controls.target, MIN = 15;
            const px = ev.deltaMode === 1 ? ev.deltaY * 33 : ev.deltaY;
            const notches = Math.sign(px) * Math.min(3, Math.abs(px) / 100);
            let move = -notches * Math.max(8, viewSpan * 0.05);
            const dir = centre.clone().sub(cam.position).normalize();
            if (flat) move = Math.min(move, cam.position.z - MIN);
            const dist = cam.position.distanceTo(centre);
            cam.position.addScaledVector(dir, move);
            if (dist - move < MIN) centre.addScaledVector(dir, move);
            controls.update();
          }, {passive: false});
          info.innerHTML = '<span>' + escapeHtml(t('{0} accounts, {1} links', nodes.length, links.length)) + '</span><span class="legend">' +
            [['#3fb950', 'same fingerprint'], ['#f59e0b', 'same e-mail'], ['#8b949e', 'same address'], ['#2dd4bf', 'same client'], ['#c084fc', 'similar name'], ['#f472b6', 'bot batch'], ['#f85149', 'manual link']]
              .map(([c, s]) => '<span style="color:' + c + '">&#9632;</span> ' + escapeHtml(t(s))).join(' &nbsp;') + '</span>';
          // The graph takes over the box's content, so the overlays go back on top of it
          box.appendChild(card);
          box.appendChild(info);
          box.appendChild(dbgBox);

          const select = async n => {
            if (n && n === selected) n = null;
            selected = n;
            if (n) groupRoot = n.id;
            if (!n) { card.style.display = 'none'; fit(700, 'back to overview'); return; }
            const d = n.x === undefined ? null : n;
            if (d) moveCamera(g2d && g2d.checked ? {x: n.x, y: n.y, z: 95} : {x: n.x + 50, y: n.y + 30, z: (n.z || 0) + 75}, {x: n.x, y: n.y, z: n.z || 0}, 700, 'zoom to ' + n.name);
            card.style.display = '';
            card.innerHTML = '<div class="loading">' + t('Loading...') + '</div>';
            let det;
            try {
              det = await apiRequest('/api/links/' + encodeURIComponent(n.id));
            } catch(e) {
              card.innerHTML = '<div class="error-msg">' + escapeHtml(e.message) + '</div>';
              return;
            }
            if (selected !== n) return;
            const alts = links.filter(l => (l.source.id || l.source) === n.id || (l.target.id || l.target) === n.id)
              .map(l => ({o: (l.source.id || l.source) === n.id ? l.target : l.source, pct: l.pct, why: l.why, types: l.types}))
              .sort((a, b) => b.pct - a.pct || b.why.length - a.why.length || (a.o.name || '').localeCompare(b.o.name || ''));
            const fp = det ? det.fingerprint : null;
            const cheats = (det && det.mods ? det.mods.slice() : []);
            if (fp && fp.liquidbounce && !cheats.some(m => m.name.toLowerCase() === 'liquidbounce'))
              cheats.push({name: 'LiquidBounce', level: 1, last_seen: fp.last_seen, via: t('resource-pack fingerprint')});
            cheats.sort((a, b) => modLevel(a) - modLevel(b));
            const serious = cheats.some(m => modLevel(m) < 3);
            const states = [n.banned && t('banned'), n.spoof && t('spoof flag'), n.online && t('online')].filter(Boolean);
            card.innerHTML =
              '<h3>' + escapeHtml(n.name) + '<button title="Close" id="closeNodeCardBtn">&times;</button></h3>' +
              '<div style="color:var(--text-secondary);font-size:11.5px;">' + (serious ? '<span style="color:var(--danger-fg);font-weight:600;">' + t('mods detected') + '</span>' : t('no mods detected')) +
              (states.length ? ' · ' + states.map(escapeHtml).join(', ') : '') + '</div>' +
              (cheats.length ? '<div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:6px;">' + cheats.map(m => modBadge(m, new Date(Number(m.last_seen)).toLocaleString())).join('') + '</div>' : '') +
              '<h4>' + t('Addresses') + '</h4>' +
              ((det && det.ips && det.ips.length) ? det.ips.map(r => '<div class="row"><span>' + escapeHtml(r.ip) + '</span><span>' + escapeHtml(provider(r) || '') + '</span></div>').join('') : '<div style="color:var(--text-muted);font-size:11px;">' + t('None captured') + '</div>') +
              (fp ? '<h4>' + t('RPTrack') + '</h4><div class="row"><span>' + t('Seen') + '</span><span>' + timeAgo(fp.last_seen) + '</span></div>' +
                (fp.mask !== undefined ? '<div class="row"><span>' + t('Bitmask') + '</span><span class="bits">' + (Number(fp.mask).toString(2).padStart(16, '0')) + '</span></div>' : '') : '') +
              '<h4>' + t('Linked Accounts ({0})', alts.length) + '</h4>' +
              (alts.length ? alts.map(a => '<div class="row alt" data-focus="' + escapeHtml(a.o.id || a.o.name) + '"><span style="font-weight:600;">' + escapeHtml(a.o.name || a.o.id) + '</span><span>' + Math.round(a.pct * 100) + '%</span>' + (a.types && a.types.has('manual') ? '<button type="button" class="btn btn-danger-ghost" data-unlink="' + escapeHtml(a.o.id || a.o.name) + '" style="padding:1px 6px;font-size:10px;margin-left:6px;" title="' + escapeHtml(t('Remove the manual link')) + '">&times;</button>' : '') + '</div><div style="color:var(--text-muted);font-size:10px;padding-bottom:3px;">' + a.why.map(escapeHtml).join(', ') + '</div>').join('') : '<div style="color:var(--text-muted);font-size:11px;">' + t('No other accounts') + '</div>') +
              '<div style="margin-top:12px;padding-top:10px;border-top:1px solid rgba(33,44,61,0.5);"><button type="button" class="btn btn-primary" style="width:100%;justify-content:center;" id="cardOpenDossierBtn">' + t('Open Security Dossier') + '</button></div>';

            card.querySelector('#closeNodeCardBtn').onclick = () => select(null);
            card.querySelectorAll('.alt[data-focus]').forEach(el => {
              el.onclick = () => {
                const fId = el.getAttribute('data-focus');
                const targetNode = nodes.find(x => x.id === fId || x.name === fId);
                if (targetNode) select(targetNode);
              };
            });
            card.querySelectorAll('[data-unlink]').forEach(el => {
              el.onclick = async ev => {
                ev.stopPropagation();
                const other = el.getAttribute('data-unlink');
                try {
                  await apiRequest('/api/link?a=' + encodeURIComponent(n.id) + '&b=' + encodeURIComponent(other), { method: 'DELETE' });
                  // Drop the pair locally rather than re-reading the whole graph
                  data.edges = (data.edges || []).filter(e => !(e.type === 'manual' &&
                    ((e.source === n.id && e.target === other) || (e.target === n.id && e.source === other))));
                  showStatusBanner(t('Manual link removed.'));
                  build();
                } catch (err) {
                  showStatusBanner(t('Could not remove the link: {0}', err.message || 'Error'), 'danger');
                }
              };
            });
            const dossierBtn = card.querySelector('#cardOpenDossierBtn');
            if (dossierBtn) {
              dossierBtn.onclick = () => {
                loadPlayerDossier(n.name || n.id, true);
              };
            }
          };

          const fit = (ms, why) => {
            if (!graph3d) return;
            // Before the first layout tick nodes have no position yet; onEngineStop fits again
            const vis = nodes.filter(n => Number.isFinite(n.x) && Number.isFinite(n.y));
            if (!vis.length) return;
            let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, minZ = Infinity, maxZ = -Infinity;
            vis.forEach(n => {
              minX = Math.min(minX, n.x); maxX = Math.max(maxX, n.x);
              minY = Math.min(minY, n.y); maxY = Math.max(maxY, n.y);
              minZ = Math.min(minZ, n.z || 0); maxZ = Math.max(maxZ, n.z || 0);
            });
            const span = Math.max(maxX - minX, maxY - minY, maxZ - minZ, 60);
            viewSpan = span;
            const dist = span * 1.5 + 30;
            const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
            moveCamera({x: cx, y: cy, z: cz + dist}, {x: cx, y: cy, z: cz}, ms, why);
          };

          const place = () => {
            if (!graph3d) return;
            // As soon as the first layout is there: a camera moved earlier would stay at the library's far default
            if (!framed && nodes.some(n => Number.isFinite(n.x))) { framed = true; if (!fitted && !focus) fit(0, 'first layout'); }
            if (focus) {
              const target = nodes.find(n => n.id === focus || n.name === focus);
              if (!target) focus = null;
              else if (Number.isFinite(target.x)) { focus = null; fitted = true; select(target); }
            }
            const now = performance.now();
            // The heads turn to the viewer a moment after the camera stops, not while it moves
            const viewCam = graph3d.camera();
            if (!faceFrom.equals(viewCam.position)) { faceFrom.copy(viewCam.position); cameraMovedAt = now; }
            const settled = now - cameraMovedAt > 1000;
            // Upright on the screen, whatever the angle: the camera's up, not the world's
            faceUp.set(0, 1, 0).applyQuaternion(viewCam.quaternion);
            const dt = (now - (lastLog || now)) / 1000;
            lastLog = now;

            links.forEach(l => {
              if (l._label) {
                const sId = l.source.id || l.source;
                const tId = l.target.id || l.target;
                const active = hover && (sId === hover || tId === hover);
                const near = selected && (sId === selected.id || tId === selected.id);
                l._label.visible = !!(active || near); // three.js hides only on exactly false
              }
            });

            nodes.forEach(n => {
              const hit = hl && matches(n);
              if (n._label) {
                n._label.material.color.set(hit ? 0xd29922 : 0xc9d1d9);
                n._label.material.opacity = hl && !hit ? 0.3 : 1;
              }
              const obj = n.__threeObj;
              const want = Math.max(selected === n ? 1.8 : 1, hit ? searchScale : 1);
              if (obj) { const k = obj.scale.x + (want - obj.scale.x) * 0.15; obj.scale.setScalar(k); }
              if (n._hit) n._hit.quaternion.set(0, 0, 0, 1);
              if (n._cube) {
                const r = n._cube.rotation;
                if (selected === n) r.y += dt * 0.7;
                else if (settled && obj) {
                  // A flat map is seen head-on, so there the heads simply stay straight
                  if (flatView) faceQ.identity();
                  else faceQ.setFromRotationMatrix(faceM.lookAt(viewCam.position, obj.position, faceUp));
                  n._cube.quaternion.slerp(faceQ, Math.min(1, dt * 4));
                }
              }
              if (n._frame && n._cube) n._frame.quaternion.copy(n._cube.quaternion);
              if (n._frame) n._frame.material.color.set(hit ? 0xfacc15 : nodeColour(n));
            });

            const cp = graph3d.camera().position;
            if (lastCam && Date.now() > ownMoveUntil && Date.now() - lastLog > 250 &&
                Math.hypot(cp.x - lastCam.x, cp.y - lastCam.y, cp.z - lastCam.z) > 0.5) {
              dbg('camera moved by controls: ' + v3(lastCam) + ' -> ' + v3(cp));
            }
            lastCam = {x: cp.x, y: cp.y, z: cp.z};
            graphRaf = requestAnimationFrame(place);
          };
          place();
          fit(0, 'initial');
        };

        const gIp = document.getElementById('g-ip');
        const gRp = document.getElementById('g-rp');
        const gCl = document.getElementById('g-cl');
        const gEm = document.getElementById('g-em');
        const gNm = document.getElementById('g-nm');
        const gMan = document.getElementById('g-manual');
        const gDays = document.getElementById('g-days');
        const g2d = document.getElementById('g-2d');
        const gMin = document.getElementById('g-min');
        const gSearch = document.getElementById('g-search');

        [gIp, gRp, gCl, gEm, gNm, gMan, gDays, g2d].forEach(el => { if (el) el.onchange = build; });
        const gGrp = document.getElementById('g-grp');
        if (gGrp) gGrp.onchange = () => {
          // Narrows around the selected account; with none picked yet there is no group to show
          if (gGrp.checked) groupRoot = selected ? selected.id : groupRoot;
          if (gGrp.checked && !groupRoot) { gGrp.checked = false; showStatusBanner(t('Pick an account first')); return; }
          build();
        };
        if (gMin) {
          gMin.oninput = () => { document.getElementById('g-min-v').textContent = gMin.value + '%'; };
          gMin.onchange = build;
        }
        if (gSearch) {
          gSearch.oninput = e => {
            hl = e.target.value.trim().toLowerCase();
            const all = graph3d ? graph3d.graphData().nodes : [];
            const matching = hl ? all.filter(matches).length : 0;
            searchScale = matching ? Math.min(2.2, 1 + 0.35 * Math.log2(all.length / matching)) : 1;
            dbg('search "' + hl + '": ' + matching + ' of ' + all.length + ' match, heads x' + searchScale.toFixed(2));
          };
        }
        build();
      }

// ============================================================================
      // 7. CONTROLLERS & WORKFLOWS
      // ============================================================================
      function switchView(viewName, focusUuid = null) {
        if (STATE.activeView === 'graph' && viewName !== 'graph') {
          stopGraph();
        }
        STATE.activeView = viewName;
        document.querySelectorAll('.nav-tab').forEach((btn) => {
          btn.classList.toggle('active', btn.dataset.view === viewName);
        });
        document.getElementById('viewOverview').classList.toggle('active', viewName === 'overview');
        document.getElementById('viewHistory').classList.toggle('active', viewName === 'history');
        document.getElementById('viewGraph').classList.toggle('active', viewName === 'graph');

        if (viewName === 'history') {
          refreshHistoryScans();
        } else if (viewName === 'graph') {
          setTimeout(() => {
            showLinks(focusUuid);
          }, 30);
        }
      }

      async function loadPlayerDossier(playerName, switchToOverview = true) {
        const data = await apiRequest('/api/player/' + encodeURIComponent(playerName));
        if (data && data.name) {
          renderPlayerDossier(data);
          if (switchToOverview && STATE.activeView !== 'overview') {
            switchView('overview');
          }
        }
      }

      async function refreshStatsAndOnline() {
        const [stats, online] = await Promise.all([
          apiRequest('/api/stats'),
          apiRequest('/api/players/online'),
        ]);
        if (stats) renderStats(stats);
        if (Array.isArray(online)) {
          STATE.onlinePlayers = online;
          renderOnlineSidebar();
        }
      }

      async function refreshHistoryScans() {
        // One more than shown tells whether older scans exist; the server gives at most 500 at once
        const scans = await apiRequest('/api/scans?limit=' + Math.min(500, STATE.historyLimit + 1) + '&offset=0');
        if (Array.isArray(scans)) {
          STATE.historyMore = scans.length > STATE.historyLimit && STATE.historyLimit < 500;
          STATE.historyScans = scans.slice(0, STATE.historyLimit);
          renderHistoryView();
        }
      }

      async function triggerPlayerScan(scanType) {
        const player = STATE.selectedPlayerName;
        if (!player) return;
        const btnId = { lang: 'runLangCheckBtn', version: 'runVersionCheckBtn', all: 'runAllCheckBtn' }[scanType] || 'runHackCheckBtn';
        const btn = document.getElementById(btnId);
        if (btn) btn.disabled = true;

        const previousLatestScanId = STATE.currentPlayer?.scans?.[0]?.id || 0;

        try {
          await apiRequest('/api/scan/run', {
            method: 'POST',
            body: JSON.stringify({ player, type: scanType }),
          });
          showStatusBanner(
            'Started ' + scanType.toUpperCase() + ' check on ' + player + '. Probing sign packets in background...',
            'warning'
          );
          let attempts = 0;
          const pollInterval = setInterval(async () => {
            attempts++;
            await loadPlayerDossier(player, false).catch(() => {});
            const currentLatestScanId = STATE.currentPlayer?.scans?.[0]?.id || 0;
            if (currentLatestScanId > previousLatestScanId || attempts >= 15) {
              clearInterval(pollInterval);
              if (btn) btn.disabled = false;
              if (currentLatestScanId > previousLatestScanId) {
                await Promise.all([refreshStatsAndOnline(), refreshHistoryScans()]);
                showStatusBanner(
                  'Completed ' + scanType.toUpperCase() + ' probe on ' + player + ' (Report #' + currentLatestScanId + ')',
                  'success'
                );
              } else {
                showStatusBanner('No result from ' + player + ' yet. The scan may still finish; check History.', 'warning');
              }
            }
          }, 2000);
        } catch (_err) {
          showStatusBanner('Failed to trigger scan on ' + player + ': ' + (_err.message || 'Error'), 'danger');
          if (btn) btn.disabled = false;
        }
      }

      async function deleteCurrentPlayer() {
        const name = STATE.selectedPlayerName;
        if (!name || !window.confirm(t('Delete all stored data of') + ' ' + name + '? ' + t('This cannot be undone.'))) return;
        try {
          await apiRequest('/api/player/' + encodeURIComponent(name), { method: 'DELETE' });
          showStatusBanner(t('Deleted player') + ' ' + name, 'success');
          STATE.selectedPlayerName = '';
          await Promise.all([refreshStatsAndOnline(), refreshHistoryScans()]);
        } catch (err) {
          showStatusBanner(t('Failed to delete player') + ' ' + name + ': ' + (err.message || 'Error'), 'danger');
        }
      }

      /** Scan progress pushed by the server; the slower polling stays as a fallback. */
      function connectScanEvents() {
        if (IS_MOCK || typeof EventSource === 'undefined') return;
        const source = new EventSource('/api/events');
        source.addEventListener('scan', (message) => {
          const e = JSON.parse(message.data);
          const type = String(e.type).toUpperCase();
          if (e.state === 'started') showStatusBanner(type + ': ' + e.player + ' — ' + t('scan started'), 'warning');
          else if (e.state === 'progress') showStatusBanner(type + ': ' + e.player + ' — ' + t('signs answered') + ' ' + e.count, 'warning');
          else if (e.state === 'aborted') showStatusBanner(type + ': ' + e.player + ' — ' + t('scan stopped'), 'warning');
          else if (e.state === 'done') showStatusBanner(type + ': ' + e.player + ' — ' + t('scan finished'), 'success');
          if (e.state === 'done' || e.state === 'progress') {
            if (STATE.selectedPlayerName && STATE.selectedPlayerName.toLowerCase() === String(e.player).toLowerCase())
              loadPlayerDossier(STATE.selectedPlayerName, false).catch(() => {});
          }
          if (e.state === 'done') Promise.all([refreshStatsAndOnline(), refreshHistoryScans()]).catch(() => {});
        });
      }

      async function deleteScanById(scanId) {
        if (!window.confirm('Delete scan #' + scanId + '? This cannot be undone.')) return;
        try {
          await apiRequest('/api/scan/' + encodeURIComponent(scanId), { method: 'DELETE' });
          await Promise.all([
            refreshHistoryScans(),
            STATE.selectedPlayerName ? loadPlayerDossier(STATE.selectedPlayerName, false) : null,
            refreshStatsAndOnline(),
          ]);
          showStatusBanner('Deleted Scan #' + scanId + ' from audit log.');
        } catch (err) {
          showStatusBanner('Failed to delete scan #' + scanId + ': ' + (err.message || 'Error'), 'danger');
        }
      }

      // The alts panel: a typed name links an account to the one on the page, Unlink removes a manual link
      async function linkAccount() {
        const input = document.getElementById('linkAccountInput');
        const me = STATE.currentPlayer;
        if (!input || !me) return;
        const other = input.value.trim();
        if (!other) return;
        const btn = document.getElementById('linkAccountBtn');
        if (btn) btn.disabled = true;
        try {
          await apiRequest('/api/link', { method: 'POST', body: JSON.stringify({ a: me.uuid || me.name, b: other }) });
          input.value = '';
          await Promise.all([loadPlayerDossier(me.name, false), refreshStatsAndOnline()]);
          showStatusBanner('Manual link saved.');
        } catch (err) {
          showStatusBanner('Could not link the accounts: ' + (err.message || 'Error'), 'danger');
        } finally {
          if (btn) btn.disabled = false;
        }
      }

      async function unlinkAlt(me, other) {
        if (!me || !other) return;
        try {
          await apiRequest('/api/link?a=' + encodeURIComponent(me) + '&b=' + encodeURIComponent(other), { method: 'DELETE' });
          await Promise.all([
            STATE.selectedPlayerName ? loadPlayerDossier(STATE.selectedPlayerName, false) : null,
            refreshStatsAndOnline(),
          ]);
          showStatusBanner('Manual link removed.');
        } catch (err) {
          showStatusBanner('Could not remove the link: ' + (err.message || 'Error'), 'danger');
        }
      }

      // ============================================================================
      // 8. INITIALIZATION & EVENT LISTENERS
      // ============================================================================
      async function initDashboard() {
        document.querySelectorAll('[data-i18n]').forEach((el) => {
          const key = el.getAttribute('data-i18n');
          if (key) el.textContent = t(key);
        });

        document.querySelectorAll('.nav-tab').forEach((btn) => {
          btn.addEventListener('click', () => {
            switchView(btn.dataset.view);
          });
        });

        document.getElementById('brandHomeLink').addEventListener('click', (e) => {
          e.preventDefault();
          switchView('overview');
        });

        document.getElementById('closeFeedbackBtn').addEventListener('click', () => {
          document.getElementById('statusFeedbackBanner').classList.remove('visible');
        });

        document.getElementById('copyUuidBtn').addEventListener('click', () => {
          const uuid = document.getElementById('dossierUuidText').textContent;
          if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(uuid).catch(() => {});
          }
          showStatusBanner('Copied UUID ' + uuid + ' to clipboard.');
        });

        document.getElementById('runHackCheckBtn').addEventListener('click', () => triggerPlayerScan('hack'));
        document.getElementById('runLangCheckBtn').addEventListener('click', () => triggerPlayerScan('lang'));
        document.getElementById('runVersionCheckBtn').addEventListener('click', () => triggerPlayerScan('version'));
        document.getElementById('runAllCheckBtn').addEventListener('click', () => triggerPlayerScan('all'));
        document.getElementById('deletePlayerBtn').addEventListener('click', deleteCurrentPlayer);
        const linkAccountBtn = document.getElementById('linkAccountBtn');
        if (linkAccountBtn) {
          linkAccountBtn.addEventListener('click', linkAccount);
          document.getElementById('linkAccountInput').addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); linkAccount(); }
          });
        }
        document.getElementById('historyMoreBtn').addEventListener('click', () => {
          STATE.historyLimit = Math.min(500, STATE.historyLimit + 50);
          refreshHistoryScans().catch(() => {});
        });
        document.getElementById('openPlayerGraphBtn').addEventListener('click', () => {
          const uuid = STATE.currentPlayer?.uuid || STATE.selectedPlayerName;
          switchView('graph', uuid);
        });

        document.getElementById('sidebarFilterInput').addEventListener('input', (e) => {
          STATE.sidebarFilter = e.target.value || '';
          renderOnlineSidebar();
        });

        document.querySelectorAll('[data-mod-filter]').forEach((btn) => {
          btn.addEventListener('click', () => {
            STATE.modFilter = btn.getAttribute('data-mod-filter') || 'ALL';
            document.querySelectorAll('[data-mod-filter]').forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            if (STATE.currentPlayer) renderModMatrixTable(STATE.currentPlayer);
          });
        });

        document.querySelectorAll('[data-history-filter]').forEach((btn) => {
          btn.addEventListener('click', () => {
            STATE.historyFilter = btn.getAttribute('data-history-filter') || 'ALL';
            document.querySelectorAll('[data-history-filter]').forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            renderHistoryView();
          });
        });

        document.body.addEventListener('click', (e) => {
          const selectPlayerBtn = e.target.closest('[data-select-player]');
          if (selectPlayerBtn) {
            const targetName = selectPlayerBtn.getAttribute('data-select-player');
            if (targetName) {
              document.getElementById('globalSearchDropdown').classList.remove('open');
              loadPlayerDossier(targetName, true);
            }
            return;
          }

          const deleteScanBtn = e.target.closest('[data-delete-scan]');
          if (deleteScanBtn) {
            const scanId = Number(deleteScanBtn.getAttribute('data-delete-scan'));
            if (scanId) deleteScanById(scanId);
            return;
          }

          const unlinkAltBtn = e.target.closest('[data-unlink-alt]');
          if (unlinkAltBtn) {
            unlinkAlt(
              unlinkAltBtn.getAttribute('data-unlink-with'),
              unlinkAltBtn.getAttribute('data-unlink-alt')
            );
            return;
          }

        });

        const searchInput = document.getElementById('globalSearchInput');
        const searchDropdown = document.getElementById('globalSearchDropdown');

        searchInput.addEventListener('input', async () => {
          const q = searchInput.value.trim();
          if (!q) {
            searchDropdown.classList.remove('open');
            return;
          }
          const matches = await apiRequest('/api/players/search?q=' + encodeURIComponent(q));
          if (!Array.isArray(matches) || matches.length === 0) {
            searchDropdown.innerHTML = '<div class="empty-box" style="padding: 10px;">No matching players found</div>';
          } else {
            searchDropdown.innerHTML = matches
              .map(
                (m) => {
                  const live = STATE.onlinePlayers.find((o) => o.name.toLowerCase() === String(m.name).toLowerCase());
                  return '<button type="button" class="search-result-btn" data-select-player="' +
                  escapeHtml(m.name) +
                  '">' +
                  '<div><div style="font-weight:600;">' +
                  escapeHtml(m.name) +
                  '</div><div class="mono" style="font-size:10.5px;color:var(--text-muted);">' +
                  escapeHtml(m.uuid) +
                  '</div></div>' +
                  '<span class="sec-pill ' +
                  (live ? 'success' : 'neutral') +
                  '">' +
                  (live ? Number(live.ping || 0) + 'ms' : 'OFFLINE') +
                  '</span>' +
                  '</button>';
                }
              )
              .join('');
          }
          searchDropdown.classList.add('open');
        });

        searchInput.addEventListener('keydown', async (e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            const q = searchInput.value.trim();
            if (!q) return;
            const matches = await apiRequest('/api/players/search?q=' + encodeURIComponent(q));
            if (Array.isArray(matches) && matches.length > 0) {
              searchDropdown.classList.remove('open');
              loadPlayerDossier(matches[0].name, true);
            }
          } else if (e.key === 'Escape') {
            searchDropdown.classList.remove('open');
          }
        });

        window.addEventListener('keydown', (e) => {
          if (e.key === '/' && document.activeElement !== searchInput && document.activeElement?.tagName !== 'INPUT') {
            e.preventDefault();
            searchInput.focus();
          }
        });

                window.addEventListener('resize', () => {
          if (STATE.activeView === 'graph' && graph3d) {
            const box = document.getElementById('graph-box');
            if (box) graph3d.width(box.clientWidth).height(box.clientHeight);
          }
        });

        let targetInitialPlayer = null;
        const rawHash = window.location.hash ? window.location.hash.slice(1) : '';
        if (rawHash.startsWith('player/')) {
          targetInitialPlayer = decodeURIComponent(rawHash.slice('player/'.length));
        }

        // The account graph is not asked for here: it is the costliest request and only the graph view shows it
        const [authInfo, stats, online, allScans] = await Promise.all([
          apiRequest('/api/validate').catch(() => null),
          apiRequest('/api/stats').catch(() => null),
          apiRequest('/api/players/online').catch(() => []),
          apiRequest('/api/scans?limit=51&offset=0').catch(() => []),
        ]);

        if (authInfo) {
          if (authInfo.mod_levels) MOD_LEVELS = authInfo.mod_levels;
          if (authInfo.player_name) {
            document.getElementById('operatorNameLabel').textContent = authInfo.player_name;
          }
          const perms = Array.isArray(authInfo.permissions)
            ? authInfo.permissions
            : String(authInfo.perms || '').split(',').map((s) => s.trim());
          const hasLinks = perms.includes('links') || perms.includes('veneer.links.view') || perms.includes('veneer.admin');
          const hasScan = perms.includes('scan') || perms.includes('veneer.scan.run') || perms.includes('veneer.admin');
          const hasDelete = perms.includes('delete') || perms.includes('veneer.scan.delete') || perms.includes('veneer.admin');
          document.body.classList.toggle('no-links', !hasLinks);
          document.body.classList.toggle('no-scan', !hasScan);
          document.body.classList.toggle('no-delete', !hasDelete);
          if (authInfo.exposed) {
            showStatusBanner(t('This panel runs without HTTPS on a public address: your login can be read on the network.'), 'warning');
            clearTimeout(bannerTimer);
          }
        }

        if (stats) renderStats(stats);
        if (Array.isArray(online)) {
          STATE.onlinePlayers = online;
          renderOnlineSidebar();
          if (!rawHash.startsWith('player/') && online.length > 0) {
            targetInitialPlayer = online[0].name;
          }
        }
        if (Array.isArray(allScans)) {
          STATE.historyMore = allScans.length > STATE.historyLimit;
          STATE.historyScans = allScans.slice(0, STATE.historyLimit);
          renderHistoryView();
          if (!rawHash.startsWith('player/') && (!online || online.length === 0) && allScans.length > 0 && allScans[0].player) {
            targetInitialPlayer = allScans[0].player;
          }
        }
        

        if (targetInitialPlayer) await loadPlayerDossier(targetInitialPlayer, false).catch(() => {});

        connectScanEvents();
        // Background live refresh intervals
        setInterval(() => { refreshStatsAndOnline().catch(() => {}); }, 10000);
        setInterval(() => { refreshHistoryScans().catch(() => {}); }, 15000);
      }

      document.addEventListener('DOMContentLoaded', initDashboard);
