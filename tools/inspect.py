"""截图标定工具：网格叠加 / 裁剪放大 / 局部比对

用法:
  python tools/inspect.py grid  <in.png> <out.png> [step]
  python tools/inspect.py crop  <in.png> <out.png> x1 y1 x2 y2 [scale]
"""
import sys
from PIL import Image, ImageDraw

LINE = (255, 0, 255)
THICK = (0, 255, 0)


def load(p):
    return Image.open(p).convert("RGB")


def grid(src, dst, step=200):
    im = load(src)
    d = ImageDraw.Draw(im)
    w, h = im.size
    for x in range(0, w, step):
        major = (x % (step * 2) == 0)
        d.line([(x, 0), (x, h)], fill=THICK if major else LINE, width=3 if major else 1)
        if major:
            d.text((x + 6, 4), str(x), fill=THICK)
            d.text((x + 6, h - 20), str(x), fill=THICK)
    for y in range(0, h, step):
        major = (y % (step * 2) == 0)
        d.line([(0, y), (w, y)], fill=THICK if major else LINE, width=3 if major else 1)
        if major:
            d.text((6, y + 2), str(y), fill=THICK)
            d.text((w - 60, y + 2), str(y), fill=THICK)
    im.save(dst)
    print(f"{dst}  grid step={step}  size={w}x{h}")


def crop(src, dst, x1, y1, x2, y2, scale=1):
    im = load(src).crop((x1, y1, x2, y2))
    if scale != 1:
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    im.save(dst)
    print(f"{dst}  crop=({x1},{y1})-({x2},{y2}) scale={scale}  out={im.size[0]}x{im.size[1]}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "grid":
        step = int(sys.argv[4]) if len(sys.argv) > 4 else 200
        grid(sys.argv[2], sys.argv[3], step)
    elif cmd == "crop":
        a = sys.argv[2:]
        src, dst = a[0], a[1]
        x1, y1, x2, y2 = int(a[2]), int(a[3]), int(a[4]), int(a[5])
        scale = float(a[6]) if len(a) > 6 else 1.0
        crop(src, dst, x1, y1, x2, y2, scale)
    else:
        print(__doc__)
