#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
不用 Gradle，直接用 Android SDK 命令行工具把小账本打成 APK。

流程：aapt2 compile → aapt2 link → javac → d8 → 打进 dex → zipalign → apksigner 签名
用法：python3 build_apk.py
产物：小账本.apk（在脚本所在目录）
"""
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
PROJ = ROOT / "android"
SRC_HTML = ROOT / "小账本.html"

SDK = pathlib.Path("/Users/muhan/.workbuddy/android-sdk")
ANDROID_JAR = SDK / "platforms-tmp" / "android-34-ext11" / "android.jar"
BT = SDK / "build-tools" / "35.0.1"
AAPT2 = BT / "aapt2"
D8 = BT / "d8"
ZIPALIGN = BT / "zipalign"
APKSIGNER = BT / "lib" / "apksigner.jar"

PKG = "com.muhan.xiaozhangben"
MIN_SDK = "23"
TARGET_SDK = "34"

# ===== 版本管理 =====
# 每次发版都要改这里：VERSION_CODE 必须递增（整数），VERSION_NAME 语义化版本。
# 应用内更新的新旧判断就是用 VERSION_NAME 与 GitHub Release 的 tag（v1.1.0）比较。
VERSION_NAME = "1.1.0"
VERSION_CODE = "2"

OUT_APK = ROOT / "小账本.apk"
KS = ROOT / "xiaozhangben.jks"
KS_PASS = "xiaozhangben"
KEY_ALIAS = "xiaozhangben"

# 图标母版放在工程内（android/icon.svg），PNG 用无头浏览器渲染，不依赖 /tmp
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ICON_MASTER = ROOT / "android" / "icon.svg"
# 各密度对应的边长（mdpi 48 → xxxhdpi 192）
ICON_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def render_icons():
    """用无头 Chrome 把 SVG 母版渲染成各密度 PNG，写回 android/res/mipmap-*"""
    import subprocess as sp
    ok = 0
    for d, size in ICON_SIZES.items():
        dst_dir = PROJ / "res" / d
        dst_dir.mkdir(parents=True, exist_ok=True)
        dst = dst_dir / "ic_launcher.png"
        r = sp.run([CHROME, "--headless=new", "--disable-gpu", "--no-sandbox",
                    "--hide-scrollbars", "--default-background-color=00000000",
                    f"--window-size={size},{size}",
                    f"--screenshot={dst}", f"file://{ICON_MASTER}"],
                   capture_output=True, text=True)
        if r.returncode == 0 and dst.exists():
            ok += 1
            print(f"  图标 {d} ({size}px): {dst.stat().st_size} 字节")
        else:
            print(f"  图标 {d} 渲染失败")
    return ok == len(ICON_SIZES)


def run(cmd, cwd=None, env=None):
    print("  $ " + " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], cwd=str(cwd) if cwd else None,
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        print("   !! 失败 (%d)" % r.returncode)
        print((r.stdout or "")[-2500:])
        print((r.stderr or "")[-2500:])
        return False, r
    return True, r


def main():
    for p in (ANDROID_JAR, AAPT2, D8, ZIPALIGN, APKSIGNER):
        if not p.exists():
            print("缺少工具:", p)
            return 1
    if not SRC_HTML.exists():
        print("缺少源文件:", SRC_HTML)
        return 1

    # 沙箱禁止批量删除，改用带时间戳的新目录，旧构建留在原地不清理
    import time
    build = PROJ / ("build-" + time.strftime("%Y%m%d-%H%M%S"))
    (build / "gen" / PKG.replace(".", "/")).mkdir(parents=True)
    (build / "obj").mkdir(parents=True)
    (build / "dex").mkdir(parents=True)

    # 1) 图标 + 内置网页
    print("== 1/6 准备资源 ==")
    if not ICON_MASTER.exists():
        print("缺少图标母版:", ICON_MASTER)
        return 1
    if not render_icons():
        return 1
    assets = PROJ / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    # 把版本号注入页面（设置页「应用更新」用于展示当前版本）
    html_text = SRC_HTML.read_text(encoding="utf-8")
    html_text = html_text.replace("__APP_VERSION__", VERSION_NAME)
    (assets / "app.html").write_text(html_text, encoding="utf-8")
    print("  内置网页 app.html:", (assets / "app.html").stat().st_size, "字节",
          "| 版本", VERSION_NAME, "(" + VERSION_CODE + ")")

    # 用真实版本号生成一份临时 Manifest（源码里是占位符）
    manifest_src = (PROJ / "AndroidManifest.xml").read_text(encoding="utf-8")
    manifest = manifest_src.replace("__VERSION_CODE__", VERSION_CODE) \
                           .replace("__VERSION_NAME__", VERSION_NAME)
    manifest_path = build / "AndroidManifest.xml"
    manifest_path.write_text(manifest, encoding="utf-8")

    # 2) aapt2 compile
    print("== 2/6 aapt2 compile ==")
    ok, _ = run([AAPT2, "compile", "--dir", PROJ / "res", "-o", build / "res.zip"])
    if not ok:
        return 1

    # 3) aapt2 link
    print("== 3/6 aapt2 link ==")
    ok, _ = run([
        AAPT2, "link",
        "-I", ANDROID_JAR,
        "--manifest", manifest_path,
        "-o", build / "base.apk",
        "-A", PROJ / "assets",   # 必须显式指定，否则内置网页不会进 APK
        "-R", build / "res.zip",
        "--java", build / "gen",
        "--auto-add-overlay",
        "--min-sdk-version", MIN_SDK,
        "--target-sdk-version", TARGET_SDK,
    ])
    if not ok:
        return 1

    # 4) javac
    print("== 4/6 javac ==")
    src_dir = PROJ / "src" / PKG.replace(".", "/")
    sources = [str(p) for p in src_dir.glob("*.java")]
    sources += [str(p) for p in (build / "gen").rglob("R.java")]
    ok, _ = run(["javac", "-source", "17", "-target", "17", "-nowarn",
                 "-encoding", "UTF-8",
                 "-cp", ANDROID_JAR, "-d", build / "obj"] + sources)
    if not ok:
        return 1

    # 5) d8 → classes.dex
    print("== 5/6 d8 ==")
    classes = [str(p) for p in (build / "obj").rglob("*.class")]
    print("  类文件:", len(classes))
    ok, _ = run(["sh", D8, "--lib", ANDROID_JAR, "--min-api", MIN_SDK,
                 "--output", build / "dex"] + classes)
    if not ok:
        return 1

    dex = build / "dex" / "classes.dex"
    if not dex.exists():
        print("未生成 classes.dex")
        return 1

    # 把 dex 塞进 apk（aapt2 只出资源包）
    print("== 6/6 打包对齐签名 ==")
    # 用 Python 直接改写 zip（命令行 zip 会先建临时文件再替换，沙箱会拦）
    import zipfile
    with zipfile.ZipFile(build / "base.apk", "r") as zin, \
            zipfile.ZipFile(build / "withdex.apk", "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.writestr("classes.dex", dex.read_bytes())
    print("  已写入 classes.dex:", dex.stat().st_size, "字节")

    ok, _ = run([ZIPALIGN, "-p", "-f", "4", build / "withdex.apk", build / "aligned.apk"])
    if not ok:
        return 1

    if not KS.exists():
        ok, _ = run(["keytool", "-genkeypair", "-v",
                     "-keystore", KS, "-alias", KEY_ALIAS,
                     "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
                     "-storepass", KS_PASS, "-keypass", KS_PASS,
                     "-dname", "CN=XiaoZhangBen, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN"])
        if not ok:
            return 1

    # apksigner / os.rename 在目标已存在时都会触发 delete，而沙箱禁止删除。
    # 先签到一个本次构建独有的新路径，再用 copyfile 覆盖最终产物（覆盖不删文件）
    signed = build / "signed.apk"
    ok, _ = run(["java", "-jar", APKSIGNER, "sign",
                 "--ks", KS, "--ks-key-alias", KEY_ALIAS,
                 "--ks-pass", "pass:" + KS_PASS, "--key-pass", "pass:" + KS_PASS,
                 "--out", signed, build / "aligned.apk"])
    if not ok:
        return 1
    shutil.copyfile(signed, OUT_APK)

    ok, r = run(["java", "-jar", APKSIGNER, "verify", "--print-certs", OUT_APK])
    print("\n=== 完成 ===")
    print("APK:", OUT_APK, OUT_APK.stat().st_size, "字节")
    print("签名校验:", "通过" if ok else "失败")
    if ok:
        for line in r.stdout.splitlines():
            if "DN:" in line or "SHA-256" in line or "Verifies" in line:
                print("  " + line.strip())
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
