#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键发版：构建 APK → 上传为 GitHub Release → 应用内「检查更新」即可发现。

前置：build_apk.py 里的 VERSION_NAME / VERSION_CODE 必须先手动递增（code 必须变大）。
用法：
    python3 release_apk.py                 # 发版（会打印更新说明让你确认）
    python3 release_apk.py --notes "..."   # 直接指定更新说明
    python3 release_apk.py --dry-run       # 只构建不上传

仓库：https://github.com/muhanruxue/xiaozhangben
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

REPO = "muhanruxue/xiaozhangben"


def sh(cmd, check=True):
    print("  $ " + " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True, cwd=str(ROOT))
    if r.returncode != 0 and check:
        print("!! 失败 (%d)" % r.returncode)
        print((r.stdout or "")[-2000:])
        print((r.stderr or "")[-2000:])
        sys.exit(1)
    return r


def main():
    args = sys.argv[1:]
    dry = "--dry-run" in args
    notes = ""
    if "--notes" in args:
        i = args.index("--notes")
        if i + 1 < len(args):
            notes = args[i + 1]

    import build_apk
    ver = build_apk.VERSION_NAME
    code = build_apk.VERSION_CODE
    tag = "v" + ver
    print("== 版本 %s (code %s) ==" % (ver, code))

    # 1) 构建
    print("== 1/3 构建 APK ==")
    r = subprocess.run([sys.executable, str(ROOT / "build_apk.py")],
                       capture_output=True, text=True, cwd=str(ROOT))
    tail = (r.stdout or "").strip().splitlines()[-8:]
    for line in tail:
        print("  " + line)
    if r.returncode != 0:
        print((r.stdout or "")[-2000:])
        print((r.stderr or "")[-2000:])
        sys.exit(1)

    apk = ROOT / "小账本.apk"
    if not apk.exists():
        print("未找到 APK")
        sys.exit(1)

    # 发版文件名用纯 ASCII，避免中文文件名在下载 URL 里被转义
    asset = ROOT / ("xiaozhangben-%s.apk" % ver)
    if asset.exists():
        asset.unlink()
    shutil_copy = __import__("shutil").copyfile
    shutil_copy(apk, asset)
    print("  附件:", asset.name, asset.stat().st_size, "字节")

    if dry:
        print("\n--dry-run：已构建，未上传。")
        return 0

    # 2) 该 tag 是否已存在
    print("== 2/3 检查 tag ==")
    r = subprocess.run(["gh", "release", "view", tag, "--repo", REPO, "--json", "tagName"],
                       capture_output=True, text=True)
    if r.returncode == 0:
        print("  tag %s 已存在，先删除旧 release 再重建" % tag)
        sh(["gh", "release", "delete", tag, "--repo", REPO, "--yes"], check=False)
        subprocess.run(["git", "push", "--delete", "origin", tag],
                       capture_output=True, text=True, cwd=str(ROOT))

    # 3) 创建 release 并上传 APK
    print("== 3/3 创建 Release 并上传 ==")
    if not notes:
        notes = "黑猫记账 %s\n\n详见提交记录。" % ver
    sh(["gh", "release", "create", tag, str(asset),
        "--repo", REPO,
        "--title", "黑猫记账 %s" % ver,
        "--notes", notes])

    print("\n=== 发布完成 ===")
    print("版本:", ver, "(code %s)" % code)
    print("地址: https://github.com/%s/releases/tag/%s" % (REPO, tag))
    print("App 内「设置 → 应用更新 → 检查更新」即可发现该版本。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
