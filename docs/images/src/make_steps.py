"""설명서용 단계별 그림(SVG)을 만든다.

    python -I docs/images/src/make_steps.py

docs/images/steps/ 아래에 SVG가 생성된다. 그림 내용을 고치려면 아래 STEPS를 수정하고 다시 실행.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "steps"
FONT = "'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',sans-serif"
CREAM, PEACH, BROWN, SUB, RED, BLUE = "#FFF6EC", "#FF9F7A", "#5B3A29", "#8A6A5A", "#D9534F", "#1A73E8"
W, H = 800, 580


def esc(t):
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, t, size=14, color="#222", weight="400", anchor="start"):
    return (f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" font-weight="{weight}" '
            f'text-anchor="{anchor}">{esc(t)}</text>')


def cat(cx, cy, s=1.0):
    return (f'<g transform="translate({cx} {cy}) scale({s})">'
            f'<circle r="22" fill="{PEACH}"/>'
            f'<path d="M-15 -2 L-13 -18 L-4 -9 Z M15 -2 L13 -18 L4 -9 Z" fill="{CREAM}"/>'
            f'<ellipse cy="1" rx="15" ry="13" fill="{CREAM}"/>'
            f'<circle cx="-5" r="2.3" fill="{BROWN}"/><circle cx="5" r="2.3" fill="{BROWN}"/>'
            f'<path d="M-2 5 L2 5 L0 7 Z" fill="#FF7A8A"/></g>')


def tap(x, y):
    return (f'<circle cx="{x}" cy="{y}" r="18" fill="{PEACH}" fill-opacity="0.35"/>'
            f'<circle cx="{x}" cy="{y}" r="30" fill="none" stroke="{PEACH}" stroke-width="4" stroke-opacity="0.8"/>')


def rect(x, y, w, h, fill, rx=8, stroke=None, sw=2):
    s = f' stroke="{stroke}" stroke-width="{sw}"' if stroke else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}"{s}/>'


def button(x, y, w, h, label, fill=PEACH, color="#FFF", size=15):
    return rect(x, y, w, h, fill, rx=h / 2) + text(x + w / 2, y + h / 2 + size * 0.36, label, size, color, "700", "middle")


def dim(w, h, op=0.4):
    return rect(0, 0, w, h, "#000", rx=22).replace('fill="#000"', f'fill="#000" fill-opacity="{op}"')


def dialog(x, y, w, lines, buttons, title_size=13):
    """lines: [(text, size, bold)]  buttons: [(label, color, bold)]"""
    h = 26 + 20 * len(lines) + 46
    out = [rect(x, y, w, h, "#FFFFFF", rx=14)]
    ty = y + 28
    for t, size, bold in lines:
        out.append(text(x + w / 2, ty, t, size, "#222", "700" if bold else "400", "middle"))
        ty += 20
    by = y + h - 18
    out.append(f'<line x1="{x}" y1="{y + h - 42}" x2="{x + w}" y2="{y + h - 42}" stroke="#E2E2E2"/>')
    n = len(buttons)
    for i, (label, color, bold) in enumerate(buttons):
        cx = x + w * (2 * i + 1) / (2 * n)
        out.append(text(cx, by, label, 14, color, "700" if bold else "400", "middle"))
    return "".join(out), [(x + w * (2 * i + 1) / (2 * n), by - 5) for i in range(n)]


def row(x, y, w, label, right="", fill="#F2F2F2", h=36, size=13):
    return rect(x, y, w, h, fill, rx=8) + text(x + 12, y + h / 2 + 5, label, size) + (
        text(x + w - 12, y + h / 2 + 5, right, size - 1, "#888", anchor="end") if right else "")


def toggle(x, y, on):
    return (rect(x, y, 44, 26, "#34C759" if on else "#CCC", rx=13)
            + f'<circle cx="{x + (31 if on else 13)}" cy="{y + 13}" r="11" fill="#FFF"/>')


def browser_bar(w, url, mobile=True):
    h = 40 if mobile else 30
    return (rect(0, 0, w, h, "#F1F3F4", rx=0) + rect(10, 7, w - 20, h - 14, "#FFFFFF", rx=(h - 14) / 2)
            + text(24, h / 2 + 5, url, 12 if mobile else 11, "#333"))


# ------------------------------------------------------------- 기기 틀

PHONE_W, PHONE_H = 230, 440
SCREEN_W, SCREEN_H = 206, 412


def phone(body, kind="android"):
    """화면 내용(body, 화면 좌표)을 폰 틀에 넣는다. 반환: (svg, 화면 원점)"""
    px, py = (W - PHONE_W) / 2 - 120, 110
    sx, sy = px + 12, py + 14
    frame = rect(px, py, PHONE_W, PHONE_H, "#222" if kind == "iphone" else BROWN, rx=34)
    screen = rect(sx, sy, SCREEN_W, SCREEN_H, "#FFFFFF", rx=24)
    notch = rect(px + PHONE_W / 2 - 30, sy + 8, 60, 16, "#222", rx=8) if kind == "iphone" else (
        f'<circle cx="{px + PHONE_W / 2}" cy="{sy + 12}" r="5" fill="#222"/>')
    clip = f'<clipPath id="scr"><rect x="0" y="0" width="{SCREEN_W}" height="{SCREEN_H}" rx="24"/></clipPath>'
    return (f'<defs>{clip}</defs>{frame}{screen}'
            f'<g transform="translate({sx} {sy})" clip-path="url(#scr)">{body}</g>{notch}'), (sx, sy)


TV_W, TV_H = 500, 290


def tv(body, remote=False):
    tx, ty = 70, 130
    sx, sy = tx + 12, ty + 12
    out = (rect(tx, ty, TV_W, TV_H, "#2B2B2B", rx=12) + rect(sx, sy, TV_W - 24, TV_H - 24, "#111", rx=4)
           + f'<g transform="translate({sx} {sy})">{body}</g>'
           + rect(tx + TV_W / 2 - 20, ty + TV_H, 40, 18, "#2B2B2B", rx=0)
           + rect(tx + TV_W / 2 - 80, ty + TV_H + 16, 160, 10, "#2B2B2B", rx=5))
    if remote:
        out += (rect(610, 300, 54, 170, "#3A3A3A", rx=26) + f'<circle cx="637" cy="345" r="15" fill="#5A5A5A"/>'
                + text(637, 351, "⌂", 16, "#FFF", anchor="middle")
                + f'<circle cx="637" cy="400" r="22" fill="#4A4A4A"/><circle cx="637" cy="400" r="9" fill="#666"/>')
    return out, (sx, sy)


def laptop(body):
    lx, ly = 90, 120
    sx, sy = lx + 14, ly + 14
    return (rect(lx, ly, 460, 300, BROWN, rx=12) + rect(sx, sy, 432, 272, "#FFF", rx=4)
            + f'<g transform="translate({sx} {sy})">{body}</g>'
            + f'<path d="M{lx - 40} {ly + 304} H{lx + 500} L{lx + 480} {ly + 324} H{lx - 20} Z" fill="{SUB}"/>'), (sx, sy)


# ------------------------------------------------------------- 카드

def text_width(t, size):
    return sum(size * (1.0 if ord(c) > 0x1100 else 0.58) for c in t)


def callout(x, y, ko, en, target=None):
    w = max(text_width(ko, 16), text_width(en, 12.5)) + 36
    x = min(x, W - 14 - w)  # 오른쪽 밖으로 나가지 않게
    out = ""
    if target:
        out += (f'<line x1="{x}" y1="{y + 30}" x2="{target[0]}" y2="{target[1]}" stroke="{PEACH}" '
                f'stroke-width="3" stroke-dasharray="6 5"/>')
    out += rect(x, y, w, 60, "#FFFFFF", rx=14, stroke=PEACH, sw=3)
    out += text(x + 16, y + 26, ko, 16, BROWN, "700") + text(x + 16, y + 47, en, 12.5, SUB)
    return out


def card(num, ko, en, device_svg, callouts=()):
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="{FONT}">',
             rect(0, 0, W, H, CREAM, rx=24),
             f'<circle cx="56" cy="56" r="28" fill="{PEACH}"/>',
             text(56, 66, str(num), 28, "#FFF", "700", "middle"),
             text(100, 56, ko, 26, BROWN, "700"),
             text(100, 84, en, 16, SUB),
             device_svg]
    parts += list(callouts)
    parts.append("</svg>")
    return "\n".join(parts)


def save(name, svg):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.svg").write_text(svg, encoding="utf-8")


def on_screen(origin, x, y):
    return origin[0] + x, origin[1] + y


CX = 490  # 말풍선 x


# ============================================================ 안드로이드

def android_steps():
    # 1. Releases 페이지에서 APK 받기
    body = (browser_bar(SCREEN_W, "github.com/yeomyaloo/mirror…")
            + text(14, 70, "Releases", 12, "#666") + text(14, 96, "v0.2.0  Latest", 17, "#222", "700")
            + rect(14, 106, 58, 18, "#E6F4EA", rx=9) + text(43, 119, "Latest", 10, "#1E8E3E", "700", "middle")
            + text(14, 150, "Assets", 13, "#222", "700")
            + row(10, 162, 186, "📦 mirror-nyang-v0.2.0.apk", fill="#FFF0E8", size=11)
            + row(10, 204, 186, "📦 mirror-nyang-v0.2.0.ipa", size=11)
            + row(10, 246, 186, "Source code (zip)", size=11))
    dev, o = phone(body)
    save("android-1-download", card(1, "Releases에서 APK 받기", "Download the APK from Releases",
                                    dev + tap(*on_screen(o, 100, 180)),
                                    [callout(CX, 230, ".apk 파일을 누르세요", "Tap the .apk file", on_screen(o, 150, 180))]))

    # 2. 다운로드 경고
    d, b = dialog(14, 250, 178, [("파일이 유해할 수 있습니다", 12, True), ("그래도 다운로드할까요?", 11, False)],
                  [("취소", "#888", False), ("다운로드", BLUE, True)])
    body = browser_bar(SCREEN_W, "github.com/…/releases") + rect(0, 40, SCREEN_W, 372, "#FAFAFA", 0) + d
    dev, o = phone(body)
    save("android-2-warning", card(2, "다운로드 경고는 '다운로드'", "Chrome warning: tap \"Download\"",
                                   dev + tap(*on_screen(o, *b[1])),
                                   [callout(CX, 300, "'다운로드'를 누르세요", "Tap \"Download\" anyway", on_screen(o, *b[1]))]))

    # 3. 출처를 알 수 없는 앱 허용
    body = (text(16, 56, "← 출처를 알 수 없는 앱 설치", 13, "#222", "700")
            + cat(40, 110, 0.8) + text(72, 106, "Chrome", 14, "#222", "700") + text(72, 124, "설치 권한", 11, "#888")
            + row(10, 160, 186, "이 출처 허용", fill="#F7F7F7", h=48, size=14) + toggle(140, 171, True)
            + text(16, 240, "허용한 뒤 뒤로 가기를 누르면", 11, "#666") + text(16, 258, "설치 화면으로 돌아갑니다", 11, "#666"))
    dev, o = phone(body)
    save("android-3-unknown-sources", card(3, "'이 출처 허용' 켜기", "Allow installs from this source",
                                           dev + tap(*on_screen(o, 162, 184)),
                                           [callout(CX, 230, "스위치를 켜세요", "Turn the switch on", on_screen(o, 190, 184))]))

    # 4. 설치 (+ Play 프로텍트)
    d, b = dialog(14, 150, 178, [("미러냥", 15, True), ("이 앱을 설치하시겠습니까?", 11, False)],
                  [("취소", "#888", False), ("설치", BLUE, True)])
    d2, b2 = dialog(14, 290, 178, [("Play 프로텍트", 12, True), ("인식할 수 없는 앱입니다", 11, False)],
                    [("확인", "#888", False), ("무시하고 설치", BLUE, True)])
    body = rect(0, 0, SCREEN_W, SCREEN_H, "#EDEDED", 0) + d + cat(103, 128, 0.7) + d2
    dev, o = phone(body)
    save("android-4-install", card(4, "'설치' 누르기", "Tap \"Install\"",
                                   dev + tap(*on_screen(o, *b[1])) + tap(*on_screen(o, *b2[1])),
                                   [callout(CX, 180, "'설치'를 누르세요", "Tap \"Install\"", on_screen(o, *b[1])),
                                    callout(CX, 360, "이 창이 뜨면 '무시하고 설치'", "Play Protect: \"Install anyway\"",
                                            on_screen(o, *b2[1]))]))

    # 5. 앱 열고 미러링 시작
    body = (cat(103, 90, 1.4) + text(103, 160, "미러냥", 26, BROWN, "700", "middle")
            + text(103, 184, "쉬는 중이에요 😺", 12, BROWN, anchor="middle")
            + button(28, 230, 150, 46, "미러링 시작", size=17)
            + text(103, 316, "같은 Wi-Fi에 있어야 해요", 10.5, SUB, anchor="middle"))
    dev, o = phone(body)
    save("android-5-start", card(5, "앱을 열고 '미러링 시작'", "Open the app, tap \"Start mirroring\"",
                                 dev + tap(*on_screen(o, 103, 253)),
                                 [callout(CX, 280, "'미러링 시작'을 누르세요", "Tap \"Start mirroring\"", on_screen(o, 180, 253))]))

    # 6. 화면 공유 허용
    d, b = dialog(14, 120, 178, [("미러냥으로 녹화 또는", 12, True), ("전송을 시작할까요?", 12, True),
                                 ("", 8, False), ("", 8, False)],
                  [("취소", "#888", False), ("지금 시작", BLUE, True)])
    body = (cat(103, 70, 1.0) + dim(SCREEN_W, SCREEN_H) + d
            + rect(32, 184, 142, 28, "#F1F1F1", rx=8) + text(103, 203, "전체 화면 공유 ▾", 11.5, "#444", anchor="middle"))
    dev, o = phone(body)
    save("android-6-consent", card(6, "'지금 시작' 누르기", "Tap \"Start now\"",
                                   dev + tap(*on_screen(o, *b[1])),
                                   [callout(CX, 190, "Android 14+: '전체 화면 공유'", "Choose \"Share entire screen\"",
                                            on_screen(o, 174, 198)),
                                    callout(CX, 330, "'지금 시작'을 누르세요", "Tap \"Start now\"", on_screen(o, *b[1]))]))

    # 7. 주소 확인
    qr = "".join(rect(70 + x * 11, 210 + y * 11, 10, 10, BROWN, rx=1)
                 for y, line in enumerate(["1110111", "1010001", "1110101", "0001010", "1101011", "1000101", "1110111"])
                 for x, c in enumerate(line) if c == "1")
    body = (cat(103, 54, 0.9) + text(103, 100, "미러냥", 20, BROWN, "700", "middle")
            + text(103, 128, "미러링 중! 이 주소를 입력하세요", 10.5, BROWN, anchor="middle")
            + rect(12, 142, 182, 40, "#FFF0E8", rx=8, stroke=RED, sw=2)
            + text(103, 168, "http://192.168.0.12:8080", 13.5, RED, "700", "middle")
            + rect(62, 202, 85, 85, "#FFF", rx=4, stroke=BROWN) + qr
            + button(28, 310, 150, 40, "미러링 멈추기", size=14))
    dev, o = phone(body)
    save("android-7-address", card(7, "화면에 뜬 주소 확인", "Note the address on the screen",
                                   dev, [callout(CX, 200, "이 주소를 TV·PC에 입력", "Type this into the TV/PC", on_screen(o, 194, 162)),
                                         callout(CX, 400, "끝낼 때 누르세요", "Tap to stop", on_screen(o, 178, 330))]))


# ============================================================ TV / PC

def tv_steps():
    tiles = "".join(rect(20 + i * 92, 180, 80, 64, "#2F3B55", rx=8) for i in range(5))
    body = (rect(0, 0, TV_W - 24, TV_H - 24, "#1C2333", 0) + text(238, 60, "⌂ 홈", 22, "#9FB0D0", anchor="middle")
            + tiles + rect(204, 176, 88, 72, BLUE, rx=10, stroke="#FFF", sw=4)
            + f'<circle cx="248" cy="204" r="14" fill="none" stroke="#FFF" stroke-width="2.5"/>'
            + f'<path d="M234 204 H262 M248 190 Q238 204 248 218 M248 190 Q258 204 248 218" stroke="#FFF" stroke-width="1.6" fill="none"/>'
            + text(248, 238, "인터넷", 11, "#FFF", "700", "middle"))
    dev, o = tv(body, remote=True)
    save("tv-1-internet", card(1, "리모컨 홈 → '인터넷' 앱", "Press Home, open the \"Internet\" app",
                               dev + tap(637, 345),
                               [callout(70, 470, "'인터넷'을 고르고 확인", "Select \"Internet\", press OK", on_screen(o, 248, 176)),
                                callout(400, 490, "리모컨 홈 버튼", "Home button on the remote", (622, 345))]))

    keys = "".join(rect(40 + (i % 10) * 40, 120 + (i // 10) * 40, 34, 32, "#2F3B55", rx=5) for i in range(30))
    body = (rect(0, 0, TV_W - 24, TV_H - 24, "#1C2333", 0) + rect(0, 0, TV_W - 24, 50, "#E9E9E9", 0)
            + rect(14, 9, 448, 32, "#FFF", rx=16, stroke=BLUE, sw=3) + text(32, 31, "192.168.0.12:8080", 17, "#222")
            + keys + rect(360, 200, 74, 32, BLUE, rx=5) + text(397, 221, "완료", 13, "#FFF", "700", "middle"))
    dev, o = tv(body)
    save("tv-2-address", card(2, "폰에 뜬 주소 입력", "Type the address shown on the phone",
                              dev, [callout(500, 470, "주소창에 입력 → 완료", "Address bar → Done", on_screen(o, 300, 26))]))

    body = (rect(0, 0, TV_W - 24, TV_H - 24, "#000", 0) + rect(186, 10, 104, 246, "#FFF", rx=10)
            + cat(238, 70, 1.1) + rect(206, 120, 64, 14, PEACH, rx=7)
            + "".join(rect(206, 150 + i * 18, 64 - (i % 2) * 14, 8, "#EEE", rx=4) for i in range(5))
            + rect(12, 12, 130, 30, PEACH, rx=15) + text(77, 32, "😺 미러링 중", 13, BROWN, "700", "middle")
            + rect(356, 222, 104, 32, CREAM, rx=16, stroke=PEACH, sw=3) + text(408, 243, "전체 화면", 13, BROWN, "700", "middle"))
    dev, o = tv(body)
    save("tv-3-fullscreen", card(3, "폰 화면이 나오면 '전체 화면'", "When the phone screen appears, go full screen",
                                 dev + tap(*on_screen(o, 408, 238)),
                                 [callout(470, 470, "'전체 화면' 버튼", "\"Full screen\" button", on_screen(o, 408, 254))]))

    body = (rect(0, 0, TV_W - 24, TV_H - 24, "#1C2333", 0) + rect(0, 0, TV_W - 24, 50, "#E9E9E9", 0)
            + rect(14, 9, 448, 32, "#FFF", rx=16, stroke=BLUE, sw=3)
            + text(32, 31, "192.168.0.12:8080", 17, "#222") + text(190, 31, "/?mode=jpeg", 17, RED, "700")
            + text(238, 150, "검은 화면만 나오면?", 22, "#FFF", "700", "middle")
            + text(238, 182, "Black screen only?", 15, "#9FB0D0", anchor="middle"))
    dev, o = tv(body)
    save("tv-4-compat", card(4, "안 나오면 호환 모드", "Not working? Use compatibility mode",
                             dev, [callout(480, 470, "주소 끝에 /?mode=jpeg", "Add /?mode=jpeg to the address", on_screen(o, 260, 40))]))

    body = (browser_bar(432, "192.168.0.12:8080", mobile=False) + rect(0, 30, 432, 242, "#000", 0)
            + rect(176, 40, 80, 222, "#FFF", rx=8) + cat(216, 90, 0.9))
    dev, o = laptop(body)
    save("pc-1-browser", card("PC", "PC는 크롬·엣지 주소창에", "On a PC, use the Chrome/Edge address bar",
                              dev, [callout(480, 470, "주소 입력 → Enter", "Type the address → Enter", on_screen(o, 120, 15))]))


# ============================================================ 아이폰

def iphone_steps():
    # 1. PC에 AltServer
    body = (browser_bar(432, "altstore.io", mobile=False) + rect(0, 30, 432, 242, "#F5FBFB", 0)
            + rect(186, 60, 60, 60, "#1BB0A8", rx=14) + '<path d="M204 108 L216 78 L228 108 Z" fill="#FFF"/>'
            + text(216, 150, "AltStore", 20, "#222", "700", "middle")
            + button(110, 175, 100, 34, "Windows", fill="#1BB0A8", size=13) + button(222, 175, 100, 34, "macOS", fill="#1BB0A8", size=13)
            + text(216, 240, "Windows: iTunes · iCloud (apple.com 버전) 필요", 11.5, "#555", anchor="middle"))
    dev, o = laptop(body)
    save("ios-1-altserver", card(1, "PC에 AltServer 설치", "Install AltServer on your computer",
                                 dev + tap(*on_screen(o, 160, 192)),
                                 [callout(470, 470, "내 컴퓨터용을 받아 설치", "Download for your OS, install", on_screen(o, 160, 209))]))

    # 2. Install AltStore
    body = (rect(0, 0, 432, 272, "#EEF2F7", 0) + rect(0, 246, 432, 26, "#D7DEE8", 0)
            + rect(396, 250, 18, 18, "#1BB0A8", rx=4)
            + rect(250, 96, 170, 146, "#FFF", rx=8, stroke="#C9D2DE")
            + text(264, 120, "About AltServer", 12, "#333") + rect(256, 132, 158, 26, "#E8F0FE", rx=4)
            + text(264, 150, "Install AltStore  ▸  iPhone", 12, "#1A57C6", "700")
            + text(264, 180, "Install Mail Plug-in", 12, "#333") + text(264, 206, "Quit", 12, "#333")
            + rect(30, 50, 180, 110, "#FFF", rx=8, stroke="#C9D2DE") + text(46, 76, "Apple ID로 로그인", 13, "#222", "700")
            + rect(44, 90, 152, 24, "#F2F4F7", rx=4) + text(52, 107, "you@icloud.com", 11, "#888")
            + rect(44, 120, 152, 24, "#F2F4F7", rx=4) + text(52, 137, "••••••••", 11, "#888"))
    dev, o = laptop(body)
    save("ios-2-install-altstore", card(2, "케이블 연결 → Install AltStore", "Connect the cable → Install AltStore",
                                        dev + tap(*on_screen(o, 405, 259)),
                                        [callout(470, 470, "작업 표시줄 아이콘 클릭", "Click the tray icon", on_screen(o, 405, 268)),
                                         callout(540, 24, "내 아이폰 선택 → 로그인", "Pick your iPhone, sign in", on_screen(o, 335, 145))]))

    # 3. 신뢰
    d, b = dialog(14, 240, 178, [("\"you@icloud.com\"을(를)", 11, True), ("신뢰하시겠습니까?", 11, True)],
                  [("취소", "#888", False), ("신뢰", BLUE, True)])
    body = (text(16, 56, "‹ 일반", 13, BLUE) + text(16, 84, "VPN 및 기기 관리", 18, "#222", "700")
            + text(16, 116, "개발자 앱", 11, "#888") + row(10, 124, 186, "you@icloud.com", "›", fill="#F2F2F2", h=40)
            + row(10, 180, 186, "신뢰 \"you@icloud.com\"", fill="#F2F2F2", h=40, size=12) + dim(SCREEN_W, SCREEN_H, 0.25) + d)
    dev, o = phone(body, "iphone")
    save("ios-3-trust", card(3, "설정에서 '신뢰'", "Trust your Apple ID in Settings",
                             dev + tap(*on_screen(o, *b[1])),
                             [callout(CX, 150, "설정 › 일반 › VPN 및 기기 관리", "Settings › General › VPN & Device Mgmt", on_screen(o, 190, 144)),
                              callout(CX, 340, "'신뢰'를 누르세요", "Tap \"Trust\"", on_screen(o, *b[1]))]))

    # 4. 개발자 모드
    body = (text(16, 56, "‹ 개인정보 보호 및 보안", 12, BLUE) + text(16, 86, "개발자 모드", 18, "#222", "700")
            + row(10, 104, 186, "개발자 모드", fill="#F2F2F2", h=46, size=14) + toggle(144, 114, True)
            + text(16, 178, "켜면 재시동하라고 나옵니다.", 11, "#666") + text(16, 196, "재시동 후 '켜기'를 누르세요.", 11, "#666"))
    dev, o = phone(body, "iphone")
    save("ios-4-developer-mode", card(4, "(iOS 16+) 개발자 모드 켜기", "(iOS 16+) Turn on Developer Mode",
                                      dev + tap(*on_screen(o, 166, 127)),
                                      [callout(CX, 230, "설정 › 개인정보 보호 및 보안", "Settings › Privacy & Security", on_screen(o, 196, 127))]))

    # 5. Safari로 IPA 받기
    body = (browser_bar(SCREEN_W, "github.com/…/releases") + text(14, 80, "v0.2.0  Latest", 17, "#222", "700")
            + text(14, 116, "Assets", 13, "#222", "700") + row(10, 128, 186, "📦 mirror-nyang-v0.2.0.apk", size=11)
            + row(10, 170, 186, "📦 mirror-nyang-v0.2.0.ipa", fill="#FFF0E8", size=11)
            + rect(14, 280, 178, 70, "#FFF", rx=12, stroke="#DDD") + text(103, 306, "다운로드할까요?", 12, "#222", "700", "middle")
            + text(103, 334, "다운로드", 14, BLUE, "700", "middle"))
    dev, o = phone(body, "iphone")
    save("ios-5-download-ipa", card(5, "아이폰 Safari로 IPA 받기", "Download the IPA in Safari",
                                    dev + tap(*on_screen(o, 100, 188)),
                                    [callout(CX, 180, ".ipa 파일을 누르세요", "Tap the .ipa file", on_screen(o, 196, 188)),
                                     callout(CX, 360, "'파일 › 다운로드'에 저장돼요", "Saved to Files › Downloads", on_screen(o, 192, 316))]))

    # 6. AltStore + IPA
    body = (text(16, 66, "My Apps", 20, "#222", "700") + text(186, 66, "+", 26, "#1BB0A8", "700", "end")
            + row(10, 90, 186, "     AltStore", "7 DAYS", h=46) + rect(20, 101, 24, 24, "#1BB0A8", rx=6)
            + row(10, 146, 186, "     미러냥", "7 DAYS", fill="#FFF0E8", h=46) + f'<circle cx="32" cy="169" r="12" fill="{PEACH}"/>'
            + text(16, 230, "Refresh All", 14, "#1BB0A8", "700"))
    dev, o = phone(body, "iphone")
    save("ios-6-altstore-add", card(6, "AltStore › My Apps › +", "AltStore › My Apps › +",
                                    dev + tap(*on_screen(o, 178, 58)),
                                    [callout(CX, 140, "+ 를 누르고 IPA 선택", "Tap +, pick the IPA", on_screen(o, 196, 58)),
                                     callout(CX, 300, "남은 기간이 표시돼요", "Days left are shown here", on_screen(o, 196, 169))]))

    # 7. 로컬 네트워크 허용
    d, b = dialog(14, 170, 178, [("\"미러냥\"이(가) 로컬 네트워크의", 10.5, True), ("기기를 찾고 연결하도록", 10.5, True),
                                 ("허용하겠습니까?", 10.5, True)], [("허용 안 함", BLUE, False), ("허용", BLUE, True)])
    body = cat(103, 90, 1.2) + text(103, 150, "미러냥", 20, BROWN, "700", "middle") + dim(SCREEN_W, SCREEN_H, 0.3) + d
    dev, o = phone(body, "iphone")
    save("ios-7-local-network", card(7, "앱을 열고 '허용'", "Open the app, tap \"Allow\"",
                                     dev + tap(*on_screen(o, *b[1])),
                                     [callout(CX, 300, "꼭 '허용'을 누르세요", "You must tap \"Allow\"", on_screen(o, *b[1]))]))

    # 8. 방송 시작
    body = (rect(0, 0, SCREEN_W, SCREEN_H, "#1E1E1E", 0) + text(103, 70, "화면 브로드캐스트", 15, "#FFF", "700", "middle")
            + rect(20, 96, 166, 42, "#3A3A3A", rx=10) + f'<circle cx="44" cy="117" r="12" fill="{PEACH}"/>'
            + text(66, 122, "미러냥", 14, "#FFF") + text(174, 122, "✓", 15, PEACH, anchor="end")
            + rect(20, 144, 166, 42, "#2A2A2A", rx=10) + text(66, 170, "사진", 14, "#888")
            + '<circle cx="103" cy="280" r="34" fill="#FFF"/><circle cx="103" cy="280" r="25" fill="#FF3B30"/>'
            + text(103, 340, "방송 시작", 15, "#FFF", "700", "middle"))
    dev, o = phone(body, "iphone")
    save("ios-8-broadcast", card(8, "동그라미 → '방송 시작'", "Round button → \"Start Broadcast\"",
                                 dev + tap(*on_screen(o, 103, 334)),
                                 [callout(CX, 140, "'미러냥'이 선택됐는지 확인", "Make sure 미러냥 is checked", on_screen(o, 186, 117)),
                                  callout(CX, 380, "'방송 시작'을 누르세요", "Tap \"Start Broadcast\"", on_screen(o, 140, 334))]))

    # 9. 끝내기
    d, b = dialog(14, 170, 178, [("화면 브로드캐스트를", 12, True), ("중단하겠습니까?", 12, True)],
                  [("취소", BLUE, False), ("중단", "#FF3B30", True)])
    body = (cat(103, 110, 1.1) + rect(10, 6, 52, 22, "#FF3B30", rx=11) + dim(SCREEN_W, SCREEN_H, 0.25) + d)
    dev, o = phone(body, "iphone")
    save("ios-9-stop", card(9, "끝낼 때: 빨간 표시 → '중단'", "To stop: red pill → \"Stop\"",
                            dev + tap(*on_screen(o, 36, 17)) + tap(*on_screen(o, *b[1])),
                            [callout(CX, 130, "왼쪽 위 빨간 표시", "Red pill at the top left", on_screen(o, 62, 17)),
                             callout(CX, 300, "'중단'을 누르세요", "Tap \"Stop\"", on_screen(o, *b[1]))]))


if __name__ == "__main__":
    android_steps()
    tv_steps()
    iphone_steps()
    print("\n".join(sorted(p.name for p in OUT.glob("*.svg"))))
