"""通过 uiautomator 控件树读屏与点击（仅用于我们自己的 App，游戏读不到节点）

用法:
  python tools/ui.py list              列出所有带文字的节点及中心坐标
  python tools/ui.py tap "添加账号"      点击第一个文本包含该字符串的节点
"""
import re
import subprocess
import sys

NODE = re.compile(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

# 可选第二参数指定设备序列号：python tools/ui.py list <serial>
SERIAL = sys.argv[2] if len(sys.argv) > 2 else None


def adb(*args):
    prefix = ("-s", SERIAL) if SERIAL else ()
    return subprocess.run(["adb", *prefix, *args], capture_output=True, text=True).stdout


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def nodes():
    out = []
    for m in NODE.finditer(dump()):
        t = m.group(1)
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "list":
        for t, x, y in nodes():
            if t.strip():
                print(f"{x:5d},{y:5d}  {t[:70]}")
    elif cmd == "tap":
        target = sys.argv[2]
        for t, x, y in nodes():
            if target in t:
                adb("shell", "input", "tap", str(x), str(y))
                print(f"tap '{target}' -> ({x},{y})")
                break
        else:
            print(f"not found: {target}")
    else:
        print(__doc__)
