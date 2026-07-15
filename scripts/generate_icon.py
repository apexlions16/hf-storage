from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1]
out = root / "assets" / "hf-storage.ico"
out.parent.mkdir(parents=True, exist_ok=True)

sizes = [16, 24, 32, 48, 64, 128, 256]
images = []
for size in sizes:
    image = Image.new("RGBA", (size, size), (8, 13, 24, 255))
    draw = ImageDraw.Draw(image)
    pad = max(2, size // 10)
    radius = max(3, size // 5)
    draw.rounded_rectangle((pad, pad, size - pad, size - pad), radius=radius, fill=(17, 27, 44, 255), outline=(255, 207, 74, 255), width=max(1, size // 32))
    cx, cy = size // 2, size // 2
    r = max(2, size // 7)
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 207, 74, 255))
    arm = max(1, size // 18)
    for dx, dy in [(-size // 4, 0), (size // 4, 0), (0, -size // 4), (0, size // 4)]:
        draw.rounded_rectangle((cx + dx - arm, cy + dy - arm, cx + dx + arm, cy + dy + arm), radius=arm, fill=(125, 211, 252, 255))
    images.append(image)
images[-1].save(out, format="ICO", sizes=[(s, s) for s in sizes], append_images=images[:-1])
print(out)
