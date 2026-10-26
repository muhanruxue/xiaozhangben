#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把单文件 小账本.html 构建成可安装的 PWA 工程（k70app/）。

要点：部署环境对子资源要求 ?sharecode= 校验，而 Chrome 抓 manifest 时不带 cookie，
静态 manifest 会被 403 拦截、导致无法安装。所以这里：
  1) 运行时用 Blob 生成 manifest（同源、无需网络），
  2) 图标全部内联为 data: URI（不产生额外请求），
  3) start_url 取当前完整 URL（自带 sharecode，安装后点图标也能直接打开）。

用法：python3 build_pwa.py   （每次改完 小账本.html 后重跑）
"""
import base64
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / "小账本.html"
APP = ROOT / "k70app"
OUT = APP / "index.html"
ICONS = APP / "icons"


def data_uri(path: pathlib.Path) -> str:
    b64 = base64.b64encode(path.read_bytes()).decode("ascii")
    return "data:image/png;base64," + b64


def build_head_inject() -> str:
    i192 = data_uri(ICONS / "icon-192.png")
    i512 = data_uri(ICONS / "icon-512.png")
    imask = data_uri(ICONS / "icon-maskable-512.png")
    return f"""  <!-- ===== PWA：可安装到安卓桌面（红米 K70 Pro / HyperOS 等） ===== -->
  <meta name="mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <meta name="apple-mobile-web-app-title" content="小账本">
  <link rel="apple-touch-icon" href="{i192}">
  <style>
    /* 独立运行时（已安装到桌面）让出状态栏高度，避免被通知栏/刘海压住 */
    @media (display-mode: standalone) {{
      .wrap {{ padding-top: calc(14px + env(safe-area-inset-top, 0px)); }}
    }}
  </style>
  <script>
  // 运行时生成 manifest（Blob 同源，图标内联），避开子资源需 sharecode 的限制
  (function () {{
    try {{
      var mf = {{
        name: "小账本 · 记账工作台",
        short_name: "小账本",
        description: "打工人专属记账工作台：收支记录、日/月/年统计、消费分布、智能总结",
        start_url: location.href,
        scope: location.origin + "/",
        display: "standalone",
        orientation: "portrait",
        background_color: "#0f1115",
        theme_color: "#7c8cff",
        lang: "zh-CN",
        categories: ["finance", "productivity"],
        icons: [
          {{ src: "{i192}", sizes: "192x192", type: "image/png", purpose: "any" }},
          {{ src: "{i512}", sizes: "512x512", type: "image/png", purpose: "any" }},
          {{ src: "{imask}", sizes: "512x512", type: "image/png", purpose: "maskable" }}
        ]
      }};
      var url = URL.createObjectURL(new Blob([JSON.stringify(mf)], {{ type: "application/manifest+json" }}));
      var l = document.createElement("link");
      l.rel = "manifest";
      l.href = url;
      document.head.appendChild(l);
    }} catch (e) {{}}
  }})();
  </script>
"""


SW_INJECT = """  <script>
  // 注册 Service Worker：安装后可离线打开、离线记账（数据本来就存本机）
  if ("serviceWorker" in navigator) {
    window.addEventListener("load", function () {
      navigator.serviceWorker.register("sw.js", { scope: "./" }).catch(function () {});
    });
  }
  </script>
"""


def main() -> int:
    if not SRC.exists():
        print("找不到源文件:", SRC)
        return 1
    for p in ("icon-192.png", "icon-512.png", "icon-maskable-512.png"):
        if not (ICONS / p).exists():
            print("缺少图标:", ICONS / p)
            return 1

    html = SRC.read_text(encoding="utf-8")
    if "apple-mobile-web-app-title" in html:
        print("源文件里已有 PWA 注入，跳过（请检查是否重复）")
    else:
        if "</head>" not in html:
            print("源文件缺少 </head>")
            return 1
        html = html.replace("</head>", build_head_inject() + "</head>", 1)

    if "serviceWorker" not in html:
        if "</body>" not in html:
            print("源文件缺少 </body>")
            return 1
        html = html.replace("</body>", SW_INJECT + "</body>", 1)

    # 注入版本号（与 APK 同一份常量），设置页「应用更新」据此显示当前版本
    try:
        sys.path.insert(0, str(ROOT))
        import build_apk
        html = html.replace("__APP_VERSION__", build_apk.VERSION_NAME)
    except Exception as e:
        print("  版本号注入跳过:", e)

    APP.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")

    checks = {
        "运行时 manifest": "application/manifest+json" in html,
        "内联图标": "data:image/png;base64," in html,
        "start_url 动态": "start_url: location.href" in html,
        "SW 注册": "serviceWorker" in html,
        "viewport-fit": "viewport-fit=cover" in html,
        "主题色": 'name="theme-color"' in html,
    }
    print(f"已生成 {OUT}（{OUT.stat().st_size} 字节）")
    for k, v in checks.items():
        print(("  ✓ " if v else "  ✗ ") + k)
    return 0 if all(checks.values()) else 2


if __name__ == "__main__":
    sys.exit(main())
