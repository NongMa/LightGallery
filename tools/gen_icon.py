# 生成 iOS Photos 风格的八瓣花启动图标（自绘矢量，非官方素材）
import math

CX, CY = 54.0, 54.0

# 单片花瓣（朝上）的关键点：M + 4 段三次贝塞尔
BASE = [
    (54, 61),                 # M 起点（花心下方，瓣间交叠）
    (47.5, 53), (40.5, 46.5), (40.5, 37.5),   # C1 右下外扩
    (40.5, 27.5), (46, 22), (54, 22),         # C2 到达顶端
    (62, 22), (67.5, 27.5), (67.5, 37.5),     # C3 顶部右侧
    (67.5, 46.5), (60.5, 53), (54, 61),       # C4 收回花心
]

COLORS = [
    "#FFD60A",  # 黄
    "#FF9F0A",  # 橙
    "#FF6B35",  # 橘红
    "#FF375F",  # 红粉
    "#BF5AF2",  # 紫
    "#5E5CE6",  # 靛蓝
    "#64D2FF",  # 天蓝
    "#30D158",  # 绿
]


def rot(x, y, deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    dx, dy = x - CX, y - CY
    return CX + dx * c - dy * s, CY + dx * s + dy * c


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s != "-0" else "0"


paths = []
for i, color in enumerate(COLORS):
    deg = i * 45.0
    pts = [rot(x, y, deg) for (x, y) in BASE]
    segs = [f"M{fmt(pts[0][0])},{fmt(pts[0][1])}"]
    for k in range(4):
        a, b, c = pts[1 + k * 3], pts[2 + k * 3], pts[3 + k * 3]
        segs.append(
            f"C{fmt(a[0])},{fmt(a[1])} {fmt(b[0])},{fmt(b[1])} {fmt(c[0])},{fmt(c[1])}"
        )
    segs.append("Z")
    d = "".join(segs)
    paths.append(
        f'    <path\n'
        f'        android:fillColor="{color}"\n'
        f'        android:fillAlpha="0.93"\n'
        f'        android:pathData="{d}" />'
    )

xml = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- 自绘 iOS 相册风格图标：白色底 + 八片彩色花瓣（任意分辨率矢量输出） -->\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    '    android:viewportWidth="108"\n'
    '    android:viewportHeight="108">\n'
    + "\n".join(paths)
    + "\n</vector>\n"
)

out = r"C:\Users\刘震艳\Documents\Default Project\PhotoAlbum\app\src\main\res\drawable\ic_launcher_foreground.xml"
with open(out, "w", encoding="utf-8") as f:
    f.write(xml)
print("written:", out)
