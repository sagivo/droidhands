#!/usr/bin/env python3
"""App Store marketing images: a headline about what the app does for you, above a real screenshot in a phone frame.

    python3 scripts/app-store-images.py [--shots dist/ios-screenshots] [--out dist/ios-marketing]

Reads the 6.9" screenshots (1320 x 2868) that `scripts/ios-app.sh screenshots` makes and writes images of the same
size, ready to upload. Needs Pillow (pip install pillow) and macOS's SF font. The headlines are in SLIDES below.
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WIDTH, HEIGHT = 1320, 2868
TOP, BOTTOM = (0x4F, 0x46, 0xE5), (0x2E, 0x27, 0x8F)  # the app's accent color, darkening downwards
FONT = "/System/Library/Fonts/SFNS.ttf"

# (screenshot, headline with its line breaks, supporting line), in listing order.
SLIDES = [
    ("1-home", "Control your iPhone\nwith AI agents", "Claude, ChatGPT, Grok or any MCP agent"),
    ("2-connect", "Connect your AI agent\nin a minute", "Send it one message, then tap Allow"),
    ("5-access", "You decide what\nthey can do", "Everything, or only what you choose"),
    ("6-activity", "See every action\nthey take", "Allowed or refused, it's all in the log"),
    ("3-agents", "Pause or revoke\nany agent", "One switch stops them all at once"),
    ("4-agent", "Know how each agent\nuses your phone", "Calls, tools and errors at a glance"),
]


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

    # Headline and supporting line, centered.
    title_font, sub_font = font(116, "Bold"), font(56, "Medium")
    y = 190
    for line in [l for part in headline.split("\n") for l in wrap(draw, part, title_font, WIDTH - 120)]:
        w = draw.textlength(line, font=title_font)
        draw.text(((WIDTH - w) / 2, y), line, font=title_font, fill="white")
        y += 136
    y += 24
    for line in wrap(draw, sub, sub_font, WIDTH - 200):
        w = draw.textlength(line, font=sub_font)
        draw.text(((WIDTH - w) / 2, y), line, font=sub_font, fill=(255, 255, 255, 215))
        y += 72

    # The screenshot in a phone frame that runs off the bottom edge.
    screen_w = 1000
    shot = Image.open(shot_path).convert("RGBA")
    shot = shot.resize((screen_w, round(shot.height * screen_w / shot.width)), Image.LANCZOS)
    bezel = 26
    frame_w, frame_h = screen_w + 2 * bezel, shot.height + 2 * bezel
    x, top = (WIDTH - frame_w) // 2, y + 70

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([x, top + 30, x + frame_w, top + 30 + frame_h], radius=130,
                                             fill=(10, 6, 60, 150))
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(45)))

    frame = Image.new("RGBA", (frame_w, frame_h), (17, 17, 22, 255))
    frame.paste(shot, (bezel, bezel), rounded_mask(shot.size, 104))
    canvas.paste(frame, (x, top), rounded_mask(frame.size, 130))
    return canvas.convert("RGB")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shots", default=os.path.join(ROOT, "dist", "ios-screenshots"))
    parser.add_argument("--out", default=os.path.join(ROOT, "dist", "ios-marketing"))
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for index, (name, headline, sub) in enumerate(SLIDES, start=1):
        path = os.path.join(args.out, f"{index}-{name}.png")
        slide(os.path.join(args.shots, f"{name}.png"), headline, sub).save(path, optimize=True)
        print(f"✓ {path}")


if __name__ == "__main__":
    main()
