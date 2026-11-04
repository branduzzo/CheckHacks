#!/usr/bin/env python3
"""Renders the README diagrams: SVG frames -> PNG (rsvg-convert) -> animated WebP (magick)."""
import os
import subprocess
import sys
import tempfile
from html import escape

OUT = sys.argv[1]
W, H = 960, 440
SCALE = 1.5

BG, CARD, LINE = "#0d1117", "#161b22", "#30363d"
TEXT, DIM = "#e6edf3", "#8b949e"
BLUE, GREEN, RED, AMBER, PURPLE = "#58a6ff", "#3fb950", "#f85149", "#d29922", "#bc8cff"
SANS, MONO = "Fira Sans", "DejaVu Sans Mono"


def t(x, y, s, size=15, color=TEXT, anchor="start", weight=400, mono=False, opacity=1):
    return (f'<text x="{x}" y="{y}" font-family="{MONO if mono else SANS}" font-size="{size}" fill="{color}" '
            f'text-anchor="{anchor}" font-weight="{weight}" opacity="{opacity}">{escape(s)}</text>')


def box(x, y, w, h, stroke=LINE, fill=CARD, r=10, sw=1.5, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>'


def arrow(x1, y1, x2, y2, color=LINE, sw=1.5, dash="5 5"):
    return (f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{sw}" stroke-dasharray="{dash}"/>')


def pill(cx, cy, s, color, size=12, mono=True):
    w = len(s) * size * (0.62 if mono else 0.5) + 22
    return (box(cx - w / 2, cy - 13, w, 26, stroke=color, fill=BG, r=13) +
            t(cx, cy + size * 0.36, s, size, color, "middle", 500, mono))


def frame(body, title, sub, h=H):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" viewBox="0 0 {W} {h}">'
            f'<rect width="{W}" height="{h}" rx="14" fill="{BG}"/>'
            + t(30, 42, title, 22, TEXT, weight=600) + t(30, 66, sub, 14, DIM) + body + '</svg>')


def ease(a, b, k):
    k = k * k * (3 - 2 * k)
    return a + (b - a) * k


def render(name, frames):
    """frames: list of (svg, centiseconds)."""
    with tempfile.TemporaryDirectory() as tmp:
        args = ["magick"]
        for i, (svg, delay) in enumerate(frames):
            sp, pp = os.path.join(tmp, f"{i:03}.svg"), os.path.join(tmp, f"{i:03}.png")
            with open(sp, "w") as f:
                f.write(svg)
            subprocess.run(["rsvg-convert", "-z", str(SCALE), "-o", pp, sp], check=True)
            args += ["-delay", str(delay), pp]
        out = os.path.join(OUT, name)
        args += ["-loop", "0", "-define", "webp:lossless=true", "-define", "webp:method=6", out]
        if len(frames) == 1:
            args = ["magick", os.path.join(tmp, "000.png"), "-define", "webp:lossless=true",
                    "-define", "webp:method=6", out]
        subprocess.run(args, check=True)
        print(f"{name}: {len(frames)} frames, {os.path.getsize(out) // 1024} KB")


def moving(build, steps, delay=6):
    """Frames of a packet moving: build(k) for k in 0..1."""
    return [(build(i / (steps - 1)), delay) for i in range(steps)]


# --------------------------------------------------------------------------- mod scan

def mod_scan():
    title = "Mod scan: the sign translation trick (MC-265322)"
    sub = "A client fills in translation and keybind text with what its own mods define, and sends it back."
    SX, CX = 30, 650
    lanes = [(130, "Vanilla client", "key.freecam.toggle", False), (290, "Client with Freecam", "F", True)]

    def scene(stage, k=0.0):
        b = box(SX, 100, 270, 300) + t(SX + 20, 130, "Server (Veneer)", 17, TEXT, weight=600)
        b += t(SX + 20, 160, "1. hidden sign, one line per mod:", 13, DIM)
        b += box(SX + 20, 172, 230, 92, stroke=AMBER, fill=BG, r=6)
        b += t(SX + 32, 196, 'keybind(', 12, AMBER, mono=True)
        b += t(SX + 32, 216, '  "key.freecam.toggle")', 12, AMBER, mono=True)
        b += t(SX + 32, 246, "+ more mods on other lines", 11, DIM)
        if stage >= 1:
            b += t(SX + 20, 290, "2. open the editor, then turn", 13, DIM)
            b += t(SX + 20, 308, "   the sign into air client-side", 13, DIM)
        for y, name, answer, modded in lanes:
            b += box(CX, y, 280, 120) + t(CX + 20, y + 30, name, 16, TEXT, weight=600)
            b += arrow(SX + 270, y + 60, CX, y + 60)
            if stage >= 2:
                b += t(CX + 20, y + 58, "sign editor closes itself,", 12, DIM)
                b += t(CX + 20, y + 76, "text resolves to:", 12, DIM)
                b += t(CX + 20, y + 100, f'"{answer}"', 14, RED if modded else GREEN, mono=True, weight=600)
            mid = (SX + 270 + CX) / 2
            if stage == 1:
                b += pill(ease(SX + 330, CX - 60, k), y + 60, "open sign", BLUE)
            if stage == 3:
                b += pill(ease(CX - 70, SX + 340, k), y + 60, f'"{answer}"', RED if modded else GREEN)
            if stage >= 4:
                verdict, col = ("DETECTED: Freecam", RED) if modded else ("NOT_DETECTED", GREEN)
                b += pill(mid, y + 60, verdict, col, 13)
        if stage >= 4:
            b += t(SX + 20, 350, "3. unknown key comes back as", 13, DIM)
            b += t(SX + 20, 368, "   is: the mod is not there", 13, DIM)
        return frame(b, title, sub)

    f = [(scene(0), 140)]
    f += moving(lambda k: scene(1, k), 12)
    f += [(scene(1, 1), 40), (scene(2), 160)]
    f += moving(lambda k: scene(3, k), 12)
    f += [(scene(4), 360)]
    render("mod-scan.webp", f)


# --------------------------------------------------------------------------- spoof

def spoof():
    title = "Spoof detection: brand vs. plugin channels"
    sub = "The brand is just a string; the channels a client registers show what is really loaded."
    channels = ["minecraft:register", "fabric:registry/sync", "fabric-screen-handler-api-v1:open_screen", "fabric:custom_ingredient_sync"]

    def scene(stage, k=0.0):
        b = box(30, 100, 330, 300) + t(50, 130, "Client", 17, TEXT, weight=600)
        b += t(50, 162, "minecraft:brand", 13, DIM) + pill(140, 186, '"vanilla"', BLUE)
        b += t(50, 230, "registered channels", 13, DIM)
        shown = min(len(channels), max(0, stage - 1))
        for i in range(shown):
            col = DIM if channels[i].startswith("minecraft:") else AMBER
            b += t(50, 256 + i * 26, channels[i], 12, col, mono=True)
        b += box(600, 100, 330, 300) + t(620, 130, "Veneer", 17, TEXT, weight=600)
        b += arrow(360, 186, 600, 186) + arrow(360, 290, 600, 290)
        if stage == 1:
            b += pill(ease(420, 540, k), 186, "brand", BLUE)
        if stage >= 2:
            b += t(620, 172, 'claims "vanilla"', 14, TEXT)
            b += t(620, 194, "vanilla registers no mod channels", 12, DIM)
        if 2 <= stage <= 5:
            b += pill(ease(430, 530, k), 290, "register", AMBER)
        if stage >= 6:
            b += t(620, 250, "Fabric API channels seen", 14, AMBER)
            b += box(620, 300, 290, 76, stroke=RED, fill=BG, r=8)
            b += t(640, 330, "FLAG: fake vanilla", 16, RED, weight=600)
            b += t(640, 354, "alert, Discord, optional punishment", 12, DIM)
        return frame(b, title, sub)

    f = [(scene(0), 100)]
    f += moving(lambda k: scene(1, k), 10)
    f += [(scene(2), 60)]
    for s in range(2, 6):
        f += moving(lambda k, s=s: scene(s, k), 8, 5)
    f += [(scene(6), 400)]
    render("spoof.webp", f)


# --------------------------------------------------------------------------- rptrack

def rptrack():
    title = "RPTrack: the resource-pack cache remembers a machine"
    sub = ".minecraft/downloads is shared by every account on the PC, and a probe with a dead URL loads only from it."
    N, COLS = 28, 14
    SET = [2, 7, 10, 16, 21, 25]

    def grid(x, y, state, label):
        """state[i]: None empty, 'probe', 'miss', 'cached', 'hit'."""
        b = t(x, y - 12, label, 13, DIM)
        for i in range(N):
            cx, cy = x + (i % COLS) * 30, y + (i // COLS) * 30
            s = state[i]
            fill, stroke, col = {None: (BG, LINE, DIM), "probe": (BG, BLUE, BLUE), "miss": (BG, LINE, LINE),
                                 "cached": ("#0c2d6b", BLUE, TEXT), "hit": ("#0f3d1e", GREEN, TEXT)}[s]
            b += box(cx, cy, 24, 24, stroke=stroke, fill=fill, r=4, sw=1.2)
            b += t(cx + 12, cy + 16, str(i + 1), 10, col, "middle", mono=True)
        return b

    def scene(act, stage):
        b = ""
        who = ("1st join: account Alice, fresh PC", "Later: account Bob, other IP, same PC")[act]
        b += pill(30 + (len(who) * 6.5 + 22) / 2, 100, who, PURPLE, 13, mono=False)
        if act == 0:
            st = [None] * N
            if stage == 1: st = ["probe"] * N
            if stage == 2: st = ["miss"] * N
            if stage >= 3: st = ["cached" if i in SET else None for i in range(N)]
            b += grid(30, 160, st, "client cache: 28 probe packs")
            notes = ["", "probe all 28 (dead URL)", "nothing cached: a new machine",
                     "send 6 random packs for real", "stored: Alice = {3, 8, 11, 17, 22, 26}"]
            b += t(530, 175, notes[stage], 16, [TEXT, BLUE, DIM, BLUE, GREEN][stage], weight=500)
            if stage >= 4:
                b += t(530, 200, "the client keeps them in its cache", 13, DIM)
        else:
            st = ["cached" if i in SET else None for i in range(N)]
            if stage == 1: st = ["probe"] * N
            if stage >= 2: st = ["hit" if i in SET else "miss" for i in range(N)]
            b += grid(30, 160, st, "client cache")
            notes = ["", "probe all 28 (dead URL)", "6 load from the cache", "Bob linked to Alice"]
            b += t(530, 175, notes[stage], 16, [TEXT, BLUE, GREEN, RED][stage], weight=500)
            if stage >= 3:
                b += t(530, 200, "other account, other IP, same installation", 13, DIM)
                b += box(530, 222, 370, 34, stroke=RED, fill=BG, r=8)
                b += t(546, 244, "alert: same client cache as Alice", 13, RED, mono=True)
        b += t(30, 280, "ExploitPreventer blocks the probes, so a client with no match is not proof of a new PC.",
               12, DIM)
        b += t(30, 298, "This is covert tracking: disclose it in your server rules.", 12, DIM)
        return frame(b, title, sub, 340)

    f = [(scene(0, 0), 80), (scene(0, 1), 120), (scene(0, 2), 140), (scene(0, 3), 140), (scene(0, 4), 260),
         (scene(1, 0), 120), (scene(1, 1), 120), (scene(1, 2), 160), (scene(1, 3), 380)]
    render("rptrack.webp", f)


# --------------------------------------------------------------------------- overview

def overview():
    title = "Veneer at a glance"
    sub = "One jar, three checks on join, one place for the results."
    b = box(30, 190, 150, 70) + t(105, 222, "Player joins", 16, TEXT, "middle", 600)
    b += t(105, 244, "Java Edition", 12, DIM, "middle")
    mods = [(100, "Spoof detection", "brand + plugin channels", BLUE),
            (190, "Mod scan", "sign translation probe", AMBER),
            (280, "RPTrack", "resource-pack cache (opt-in)", PURPLE)]
    for y, name, what, col in mods:
        b += arrow(180, 225, 330, y + 35, col, 1.5, "none")
        b += box(330, y, 260, 70, stroke=col) + t(350, y + 30, name, 16, TEXT, weight=600)
        b += t(350, y + 52, what, 12, DIM)
        b += arrow(590, y + 35, 690, y + 35, col, 1.5, "none")
        b += f'<circle cx="690" cy="{y + 35}" r="4" fill="{col}"/>'
    outs = ["Staff alerts in game", "Discord webhook", "Web panel + account graph", "SQLite history",
            "PlaceholderAPI"]
    # Rows sit between the three entries (y 135, 225, 315), so no line seems to feed one output only
    b += box(690, 90, 240, 280)
    b += t(712, 117, "shared by all three", 13, DIM)
    for i, o in enumerate(outs):
        b += t(712, 162 + i * 45, "• " + o, 15, TEXT)
    b += t(30, 392, "Bedrock players (Floodgate, Geyser) are recognised and left out of every check.", 13, DIM)
    b += t(30, 414, "Anticheat flags (Grim, Vulcan, ...) and spoof flags can also start a mod scan.", 13, DIM)
    render("overview.webp", [(frame(b, title, sub), 0)])


os.makedirs(OUT, exist_ok=True)
for fn in (overview, spoof, mod_scan, rptrack):
    fn()
