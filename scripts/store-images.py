#!/usr/bin/env python3
"""Store marketing images: a headline about what the app does for you, above a real screenshot in a phone frame.

    python3 scripts/store-images.py [--store app-store|play] [--shots DIR] [--out DIR] [--font TTF]

app-store (default): reads the 6.9" iPhone screenshots (1320 x 2868) that `scripts/ios-app.sh screenshots` makes
from dist/ios-screenshots and writes same-size images to dist/ios-marketing, in macOS's SF font.

play: reads Android store-edition screenshots (1080 x 2400, `adb exec-out screencap -p`) from dist/play-screenshots
and writes 1080 x 1920 (9:16) images to dist/play-marketing. SF may only be used for Apple platforms, so this uses
Roboto: pass --font, or copy it from an emulator first (adb pull /system/fonts/Roboto-Regular.ttf ~/Library/Fonts/).

Needs Pillow (pip install pillow). The headlines are in STORES below.
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOP, BOTTOM = (0x4F, 0x46, 0xE5), (0x2E, 0x27, 0x8F)  # the app's accent color, darkening downwards

# Per store: canvas size, font, folders and the slides as (screenshot, headline with its line breaks, supporting
# line), in listing order. Play gets the store edition, so its slides never promise reading or tapping the screen.
STORES = {
    "app-store": {
        "size": (1320, 2868),
        "fonts": ["/System/Library/Fonts/SFNS.ttf"],
        "shots": "ios-screenshots",
        "out": "ios-marketing",
        "slides": [
            ("1-home", "Control your iPhone\nwith AI agents", "Claude, ChatGPT, Grok or any MCP agent"),
            ("2-connect", "Connect your AI agent\nin a minute", "Send it one message, then tap Allow"),
            ("5-access", "You decide what\nthey can do", "Everything, or only what you choose"),
            ("6-activity", "See every action\nthey take", "Allowed or refused, it's all in the log"),
            ("3-agents", "Pause or revoke\nany agent", "One switch stops them all at once"),
            ("4-agent", "Know how each agent\nuses your phone", "Calls, tools and errors at a glance"),
        ],
    },
    "play": {
        "size": (1080, 1920),
        "fonts": [os.path.expanduser("~/Library/Fonts/Roboto-Regular.ttf"), "/Library/Fonts/Roboto-Regular.ttf"],
        "shots": "play-screenshots",
        "out": "play-marketing",
        "slides": [
            ("1-home", "Control your phone\nwith AI agents", "Claude, ChatGPT, Grok or any MCP agent"),
            ("2-connect", "Connect your AI agent\nto your phone", "Send it one message, then tap Allow"),
            ("7-request", "Nothing connects\nwithout your OK", "Check the code, then tap Allow"),
            ("5-access", "You decide what\nthey can do", "Everything, or only what you choose"),
            ("6-activity", "See every action\nthey take", "Allowed or refused, it's all in the log"),
            ("3-agents", "Pause or revoke\nany agent", "Its access ends right away"),
            ("4-agent", "Know how each agent\nuses your phone", "Calls, tools and errors at a glance"),
        ],
    },
}
WIDTH, HEIGHT = STORES["app-store"]["size"]
FONT = STORES["app-store"]["fonts"][0]


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


def wrap(draw, text, f, width):
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if line and draw.textlength(candidate, font=f) > width:
            lines.append(line)
            line = word
        else:
            line = candidate
    return lines + [line]


def gradient():
    image = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        t = y / (HEIGHT - 1)
        draw.line([(0, y), (WIDTH, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    return image


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def slide(shot_path, headline, sub):
    canvas = gradient().convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    k = WIDTH / 1320  # the layout was drawn at the iPhone width; scale it to this canvas

    def px(n):
        return round(n * k)

    # Headline and supporting line, centered.
    title_font, sub_font = font(px(116), "Bold"), font(px(56), "Medium")
    y = px(190)
    for line in [l for part in headline.split("\n") for l in wrap(draw, part, title_font, WIDTH - px(120))]:
        w = draw.textlength(line, font=title_font)
        draw.text(((WIDTH - w) / 2, y), line, font=title_font, fill="white")
        y += px(136)
    y += px(24)
    for line in wrap(draw, sub, sub_font, WIDTH - px(200)):
        w = draw.textlength(line, font=sub_font)
        draw.text(((WIDTH - w) / 2, y), line, font=sub_font, fill=(255, 255, 255, 215))
        y += px(72)

    # The screenshot in a phone frame that runs off the bottom edge.
    screen_w = px(1000)
    shot = Image.open(shot_path).convert("RGBA")
    shot = shot.resize((screen_w, round(shot.height * screen_w / shot.width)), Image.LANCZOS)
    bezel = px(26)
    frame_w, frame_h = screen_w + 2 * bezel, shot.height + 2 * bezel
    x, top = (WIDTH - frame_w) // 2, y + px(70)

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([x, top + px(30), x + frame_w, top + px(30) + frame_h], radius=px(130),
                                             fill=(10, 6, 60, 150))
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(px(45))))

    frame = Image.new("RGBA", (frame_w, frame_h), (17, 17, 22, 255))
    frame.paste(shot, (bezel, bezel), rounded_mask(shot.size, px(104)))
    canvas.paste(frame, (x, top), rounded_mask(frame.size, px(130)))
    return canvas.convert("RGB")


def main():
    global WIDTH, HEIGHT, FONT
    parser = argparse.ArgumentParser()
    parser.add_argument("--store", choices=sorted(STORES), default="app-store")
    parser.add_argument("--shots", help="screenshots folder (default dist/<store>-screenshots)")
    parser.add_argument("--out", help="output folder (default dist/<store>-marketing)")
    parser.add_argument("--font", help="a variable TTF with Bold and Medium weights")
    args = parser.parse_args()
    store = STORES[args.store]
    WIDTH, HEIGHT = store["size"]
    FONT = args.font or next((f for f in store["fonts"] if os.path.exists(f)), None)
    if not FONT:
        parser.error(f"no font found for --store {args.store}; pass --font (see the usage at the top)")
    shots = args.shots or os.path.join(ROOT, "dist", store["shots"])
    out = args.out or os.path.join(ROOT, "dist", store["out"])
    os.makedirs(out, exist_ok=True)
    for index, (name, headline, sub) in enumerate(store["slides"], start=1):
        path = os.path.join(out, f"{index}-{name}.png")
        slide(os.path.join(shots, f"{name}.png"), headline, sub).save(path, optimize=True)
        print(f"✓ {path}")


if __name__ == "__main__":
    main()
