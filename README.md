# 黑猫记账（XiaoZhangBen）

一个「打工人专属记账工作台」，以**单文件 HTML** 形态存在，可三种方式使用：

| 形态 | 入口 | 说明 |
|---|---|---|
| 网页 | `小账本.html` | 直接用浏览器打开即可记账，数据存 localStorage |
| 安卓 App | `build_apk.py` 产出 `小账本.apk` | WebView 外壳 + JS Bridge，支持跑马灯式状态栏、多闹钟提醒、应用内更新 |
| PWA | `build_pwa.py` 产出 `k70app/` | 可添加到手机桌面当 App 用 |

> 应用主页本身就是 `小账本.html`，APK 只是把它装进 WebView。改功能只需要改这一个 HTML。

---

## 目录结构

```
.
├── 小账本.html              # 应用主体（HTML/CSS/JS 全内联，改功能改这里）
├── android/                 # 安卓外壳工程
│   ├── AndroidManifest.xml
│   ├── icon.svg             # 图标矢量母版
│   ├── res/values/          # styles.xml / strings.xml
│   └── src/com/muhan/xiaozhangben/
│       ├── MainActivity.java      # WebView + JS Bridge（导出/提醒/状态栏）
│       ├── UpdateManager.java     # 应用内检查与安装更新
│       ├── ReminderReceiver.java  # 每日记账提醒
│       ├── BootReceiver.java      # 开机后重建提醒
│       └── ApkProvider.java       # 安装包 FileProvider
├── k70app/                  # PWA 产物（index.html / sw.js / manifest / icons）
├── docs/releases/           # 各版本发布说明
├── build_apk.py             # 打 APK
├── build_pwa.py             # 生成 PWA
├── make_icon.py             # 生成图标 PNG 母版
└── release_apk.py           # 构建 + 推送 Release 一键发版
```

## 构建

> 所有脚本都用 `Path(__file__).parent` 作为工程根目录，**必须在仓库根目录执行，也不要把脚本移到子目录**（`build_pwa.py` 还会 `import build_apk`）。

依赖：

- Python 3
- Android SDK（脚本内默认路径 `/Users/muhan/.workbuddy/android-sdk`，需含 `build-tools/35.0.1` 与 `platforms-tmp/android-34-ext11/android.jar`）
- Google Chrome（无头模式用于渲染图标，路径写在 `build_apk.py` 的 `CHROME` 常量）
- GitHub CLI `gh`（仅 `release_apk.py` 发版需要）

### 打 APK

```bash
python3 build_apk.py
```

产物 `小账本.apk`。流程：aapt2 compile → link → javac → d8 → zipalign → apksigner 签名。

**签名密钥**：脚本要求工程根目录存在 `xiaozhangben.jks`（默认别名与口令均为 `xiaozhangben`）。
该密钥**刻意不入库**（已在 `.gitignore` 排除），新环境首次构建需要自备或重建密钥。
⚠️ 一旦更换密钥，已安装用户将无法覆盖升级，只能卸载重装。

### 生成 PWA

```bash
python3 build_pwa.py
```

### 发版

1. 改 `build_apk.py` 顶部的 `VERSION_NAME` / `VERSION_CODE`（**两者都要改**，`VERSION_CODE` 必须递增）
2. 在 `docs/releases/` 新增对应版本的说明
3. 提交后执行：

```bash
python3 release_apk.py
```

它会构建 APK、创建 Git tag、并发布 GitHub Release。
应用内「检查更新」用 `VERSION_NAME` 与 GitHub Release 的 tag（`vX.Y.Z`）比较来判断新旧。

## 数据安全

- 记账数据存在 App 私有空间 / localStorage，**不上传服务器**
- 应用内提供「导出 JSON 备份」「导出 CSV」到 Download 目录，以及「导入恢复」
- 建议定期导出备份

## 历史版本

见 [`docs/releases/`](docs/releases)：

- [v1.6.2](docs/releases/v1.6.2.md) — 设置整合为页面内版块（隐藏今日统计）、明细表格列宽优化 + 点击事项弹浮窗
- [v1.6.1](docs/releases/v1.6.1.md) — 设置面板改为标题栏下方轻下拉
- [v1.6.0](docs/releases/v1.6.0.md) — 明细可编辑、多提醒 + 连续时钟、修复公告/导出重复弹窗、时间沿用、水滴屏适配
