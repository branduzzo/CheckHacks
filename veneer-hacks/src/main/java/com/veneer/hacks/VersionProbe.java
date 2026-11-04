package com.veneer.hacks;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tells the client's real game version from how it renders a few components on the language-check sign, to
 * compare with the protocol version it connects with (ViaFabricPlus, ViaProxy and the like make the two differ).
 * Lines 1-3 of the sign each pack several probes separated by {@link #SEP}; the answers can be far longer than the
 * 80 characters Paper keeps of a sign line, so they are read from the raw packet.
 * <p>
 * Only English is built into the game; every other language file is downloaded from the same place for every
 * version (one file for 1.19.3 to 26.3) and holds every key, so a 1.20 client in Russian knows the names of 26.3
 * blocks. Three kinds of evidence survive that:
 * <ul>
 *   <li>Key bindings: {@code Component.keybind(k)} shows the bound key when the game has that binding and falls
 *       back to {@code translatable(k)} when it does not, in any language. Bindings were added in 1.21.6, 1.21.9,
 *       1.21.11, 26.1, 26.2 and 26.3 (none from 1.16.5 to 1.21.5).</li>
 *   <li>Keys Mojang removed from the game and from the shared files: a client still translates them from its
 *       built-in English file, in any language. None were removed after 1.21.4.</li>
 *   <li>For en_us clients only: keys added in a version (one per protocol from 1.18 on), and "Walk Forwards"
 *       becoming "Walk Forward" in 1.21.2. A key is only asked of clients whose protocol already has it:
 *       ViaBackwards rewrites keys the protocol does not know, which would read as an old client.</li>
 * </ul>
 * Lists checked against the en_us files, the 142 current language files and the key bindings of 1.16.5 to 26.3.
 */
public final class VersionProbe {

    /** Separates the probes of one line; not used in any translation. */
    public static final String SEP = "¦";
    private static final String ABSENT = "~";
    private static final String CODE_KEY = "language.code";
    private static final String UNBOUND_KEY = "key.keyboard.unknown";
    private static final String MIDDLE_KEY = "key.mouse.middle";
    private static final String FALLBACK_KEY = "veneer.fallback";

    private record Bind(String key, ClientVersion since) {}

    /** A key the game had from {@code from} to {@code to} (inclusive, protocol versions). */
    private record Removed(String key, ClientVersion from, ClientVersion to) {}

    /** A key en_us has from {@code since} on, with its English text. */
    private record Added(String key, ClientVersion since, String text) {}

    private static final List<Bind> BINDS = List.of(
            new Bind("key.quickActions", ClientVersion.V_1_21_6),
            new Bind("key.spectatorHotbar", ClientVersion.V_1_21_9),
            new Bind("key.debug.overlay", ClientVersion.V_1_21_11),
            new Bind("key.debug.lightmapTexture", ClientVersion.V_26_1),
            new Bind("key.friends", ClientVersion.V_26_2),
            new Bind("key.debug.improvedTransparency", ClientVersion.V_26_3));

    private static final List<ClientVersion> CANDIDATES = Arrays.stream(ClientVersion.values())
            .filter(v -> v.name().startsWith("V_") && !v.isOlderThan(ClientVersion.V_1_16_4)).toList();
    private static final ClientVersion FIRST = CANDIDATES.get(0);

    // Line 2 and line 3; the longest answers in any language keep each line under the 384 characters a client sends
    private static final List<Removed> REMOVED_2 = List.of(
            new Removed("options.vbo", FIRST, ClientVersion.V_1_16_4),
            new Removed("options.snooper", FIRST, ClientVersion.V_1_17_1),
            new Removed("generator.flat", FIRST, ClientVersion.V_1_18_2),
            new Removed("chat.preview", ClientVersion.V_1_19, ClientVersion.V_1_19),
            new Removed("options.chatPreview", ClientVersion.V_1_19, ClientVersion.V_1_19_1),
            new Removed("subtitles.item.brush.brushing", ClientVersion.V_1_19_4, ClientVersion.V_1_19_4));
    private static final List<Removed> REMOVED_3 = List.of(
            new Removed("advMode.allEntities", FIRST, ClientVersion.V_1_21),
            new Removed("subtitles.entity.creaking.angry", ClientVersion.V_1_21_2, ClientVersion.V_1_21_2));

    private static final List<Added> ADDED = List.of(
            new Added("biome.minecraft.meadow", ClientVersion.V_1_18, "Meadow"),
            new Added("block.minecraft.mud", ClientVersion.V_1_19, "Mud"),
            new Added("gui.acknowledge", ClientVersion.V_1_19_1, "Acknowledge"),
            new Added("mco.connect.success", ClientVersion.V_1_19_3, "Done"),
            new Added("effect.duration.infinite", ClientVersion.V_1_19_4, "∞"),
            new Added("mco.question", ClientVersion.V_1_20, "Question"),
            new Added("stats.none", ClientVersion.V_1_20_2, "-"),
            new Added("entity.minecraft.breeze", ClientVersion.V_1_20_3, "Breeze"),
            new Added("item.minecraft.mace", ClientVersion.V_1_20_5, "Mace"),
            new Added("known_server_link.community", ClientVersion.V_1_21, "Community"),
            new Added("item.minecraft.bundle.empty", ClientVersion.V_1_21_2, "Empty"),
            new Added("item.minecraft.resin_clump", ClientVersion.V_1_21_4, "Resin Clump"),
            new Added("test_block.mode.start", ClientVersion.V_1_21_5, "Start"),
            new Added("mco.configure.world.buttons.save", ClientVersion.V_1_21_6, "Save"),
            new Added("painting.minecraft.dennis.title", ClientVersion.V_1_21_7, "Dennis"),
            new Added("debug.entry.always", ClientVersion.V_1_21_9, "Always"),
            new Added("options.textureFiltering.none", ClientVersion.V_1_21_11, "None"),
            new Added("gui.game_rule.search", ClientVersion.V_26_1, "Search..."),
            new Added("options.online.realms.header", ClientVersion.V_26_2, "Realms"),
            new Added("block.minecraft.black_wool_slab", ClientVersion.V_26_3, "Black Wool Slab"));

    /** Upper release of a protocol two or three releases share. */
    private static final Map<ClientVersion, String> LAST_RELEASE = Map.ofEntries(
            Map.entry(ClientVersion.V_1_16_4, "1.16.5"), Map.entry(ClientVersion.V_1_18, "1.18.1"),
            Map.entry(ClientVersion.V_1_19_1, "1.19.2"), Map.entry(ClientVersion.V_1_20, "1.20.1"),
            Map.entry(ClientVersion.V_1_20_3, "1.20.4"), Map.entry(ClientVersion.V_1_20_5, "1.20.6"),
            Map.entry(ClientVersion.V_1_21, "1.21.1"), Map.entry(ClientVersion.V_1_21_2, "1.21.3"),
            Map.entry(ClientVersion.V_1_21_7, "1.21.8"), Map.entry(ClientVersion.V_1_21_9, "1.21.10"),
            Map.entry(ClientVersion.V_26_1, "26.1.2"));

    private VersionProbe() {}

    /** Sign lines 1 to 3 for a client connecting with {@code protocol}. */
    public static Component[] lines(ClientVersion protocol) {
        List<Component> one = new ArrayList<>(List.of(Component.translatable(CODE_KEY),
                Component.translatable(UNBOUND_KEY), Component.translatable(MIDDLE_KEY)));
        for (Bind b : BINDS) one.add(Component.keybind(b.key()));

        List<Component> two = new ArrayList<>();
        two.add(Component.translatable(FALLBACK_KEY, ABSENT));
        for (Bind b : BINDS) two.add(Component.translatable(b.key()));
        for (Removed r : REMOVED_2) two.add(Component.translatable(r.key(), ABSENT));

        List<Component> three = new ArrayList<>();
        for (Removed r : REMOVED_3) three.add(Component.translatable(r.key(), ABSENT));
        for (Added a : added(protocol)) three.add(Component.translatable(a.key(), ABSENT));

        return new Component[] {join(one), join(two), join(three)};
    }

    private static List<Added> added(ClientVersion protocol) {
        return ADDED.stream().filter(a -> protocol != null && !protocol.isOlderThan(a.since())).toList();
    }

    private static Component join(List<Component> parts) {
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(Component.text(SEP));
            b.append(parts.get(i));
        }
        return b.build();
    }

    /**
     * The versions the client can be. Empty when the answers contradict each other, which no unmodified client
     * produces; every candidate when the answers tell nothing.
     */
    public static final class Range {
        private final List<ClientVersion> left;

        private Range(List<ClientVersion> left) { this.left = left; }

        public boolean known() { return left.size() < CANDIDATES.size(); }

        /** Whether a client connecting with {@code protocol} could really be this version. */
        public boolean fits(ClientVersion protocol) {
            if (protocol == null || !CANDIDATES.contains(protocol)) return !left.isEmpty();
            return left.contains(protocol);
        }

        public String text() {
            if (left.isEmpty()) return "inconsistent";
            if (!known()) return "?";
            ClientVersion min = left.get(0), max = left.get(left.size() - 1);
            String range;
            if (min == max) range = protocolName(min);
            else if (min == FIRST) range = "<=" + upper(max);
            else if (max == CANDIDATES.get(CANDIDATES.size() - 1)) range = ">=" + min.getReleaseName();
            else range = min.getReleaseName() + "-" + upper(max);
            List<String> gaps = new ArrayList<>();
            for (ClientVersion v : CANDIDATES.subList(CANDIDATES.indexOf(min), CANDIDATES.indexOf(max) + 1))
                if (!left.contains(v)) gaps.add(protocolName(v));
            return gaps.isEmpty() ? range : range + " except " + String.join(", ", gaps);
        }
    }

    /**
     * Reads the answers. {@code lines} are the four raw sign lines (line 0 is the language probe's key.forward),
     * {@code protocol} the version the lines were built for.
     */
    public static Range evaluate(String[] lines, ClientVersion protocol) {
        List<ClientVersion> left = new ArrayList<>(CANDIDATES);
        String[] one = parts(lines, 1), two = parts(lines, 2), three = parts(lines, 3);
        List<Added> added = added(protocol);
        if (one.length != 3 + BINDS.size() || two.length != 1 + BINDS.size() + REMOVED_2.size()
                || three.length != REMOVED_3.size() + added.size())
            return new Range(left);   // a translation held the separator, or the client cut a line

        String code = one[0], unbound = one[1], middle = one[2];
        for (int i = 0; i < BINDS.size(); i++) {
            Bind b = BINDS.get(i);
            String answer = one[3 + i], label = two[1 + i];
            if (answer.isEmpty()) continue;
            if (answer.equals(unbound) || answer.equals(middle)) keep(left, v -> !v.isOlderThan(b.since()));
            else if (answer.startsWith("key.") || answer.equals(label)) keep(left, v -> v.isOlderThan(b.since()));
            else if (keyName(answer)) keep(left, v -> !v.isOlderThan(b.since()));
        }

        // The fallback arrived only if the protocol has it (1.19.4+); then a raw key means an older client
        if (protocol != null && !protocol.isOlderThan(ClientVersion.V_1_19_4)) {
            if (two[0].equals(ABSENT)) keep(left, v -> !v.isOlderThan(ClientVersion.V_1_19_4));
            else if (two[0].equals(FALLBACK_KEY)) keep(left, v -> v.isOlderThan(ClientVersion.V_1_19_4));
        }

        removed(left, REMOVED_2, Arrays.copyOfRange(two, 1 + BINDS.size(), two.length));
        removed(left, REMOVED_3, Arrays.copyOfRange(three, 0, REMOVED_3.size()));

        if (code.equals("en_us")) {
            String forward = lines.length > 0 && lines[0] != null ? lines[0].strip() : "";
            if (forward.equals("Walk Forwards"))     keep(left, v -> v.isOlderThan(ClientVersion.V_1_21_2));
            else if (forward.equals("Walk Forward")) keep(left, v -> !v.isOlderThan(ClientVersion.V_1_21_2));
            for (int i = 0; i < added.size(); i++) {
                Added a = added.get(i);
                String answer = three[REMOVED_3.size() + i];
                if (answer.equals(a.text())) keep(left, v -> !v.isOlderThan(a.since()));
                else if (answer.equals(ABSENT) || answer.equals(a.key())) keep(left, v -> v.isOlderThan(a.since()));
            }
        }
        return new Range(left);
    }

    private static void removed(List<ClientVersion> left, List<Removed> keys, String[] answers) {
        for (int i = 0; i < keys.size(); i++) {
            Removed r = keys.get(i);
            String answer = answers[i];
            Predicate<ClientVersion> inside = v -> !v.isOlderThan(r.from()) && !v.isNewerThan(r.to());
            if (answer.equals(ABSENT) || answer.equals(r.key())) keep(left, inside.negate());
            else if (!answer.isEmpty()) keep(left, inside);
        }
    }

    /** A key's on-screen name: one or two letters or digits (not CJK, which labels use), or F1-F25. */
    private static boolean keyName(String s) {
        if (s.matches("F\\d{1,2}")) return true;
        if (s.codePointCount(0, s.length()) > 2) return false;
        return s.codePoints().allMatch(c -> Character.isLetterOrDigit(c) && !Character.isIdeographic(c)
                && Character.UnicodeScript.of(c) != Character.UnicodeScript.HANGUL
                && Character.UnicodeScript.of(c) != Character.UnicodeScript.HIRAGANA
                && Character.UnicodeScript.of(c) != Character.UnicodeScript.KATAKANA);
    }

    private static String[] parts(String[] lines, int i) {
        String line = i < lines.length && lines[i] != null ? lines[i] : "";
        return Arrays.stream(line.split(SEP, -1)).map(String::strip).toArray(String[]::new);
    }

    private static void keep(List<ClientVersion> left, Predicate<ClientVersion> test) {
        left.removeIf(test.negate());
    }

    /** The protocol version a client connects with, naming both releases where two share it ("1.20/1.20.1"). */
    public static String protocolName(ClientVersion v) {
        String last = LAST_RELEASE.get(v);
        return last == null ? v.getReleaseName() : v.getReleaseName() + "/" + last;
    }

    private static String upper(ClientVersion v) {
        return LAST_RELEASE.getOrDefault(v, v.getReleaseName());
    }
}
