# 星核代理

基于 sing-box 的 Android 代理客户端，WebView 界面 + 原生桥，轻量、浅色极简风。

> 本 App 本身**不含任何节点**，需要你自备订阅链接。

## 功能

- **订阅导入**：支持订阅链接导入、粘贴导入、扫码添加；支持 Base64 链接清单、Clash YAML、JSON 三种订阅格式
- **节点列表与测速**：节点延迟测试、未连接时显示 TCP 直连延迟、真延迟测试（连接后走核心测速）
- **代理模式**：规则 / 全局 / 直连三段切换，首页可直接切换
- **自动选最快节点**：自动选节点组（url-test），一键切到当前最快节点
- **订阅自动更新与提醒**：打开 App 时按设置自动检查更新；流量剩余不足或即将到期时首页提醒
- **按 App 分流**：按应用选择走代理还是直连
- **实时连接视图**：进行中的连接 + 最近连接列表（最多 20 条）
- **核心启动失败诊断**：启动失败时弹出日志最后 15 行，方便定位问题
- **订阅解析诊断**：某次拉取解析出的节点明显偏少时，订阅卡下方显示红色诊断行（格式、原始行数、成功数、跳过数、首个跳过原因）

## 技术栈

| 部分 | 说明 |
|---|---|
| 代理核心 | sing-box 1.14.4（官方 Android 二进制，构建时自动下载校验） |
| VPN 转发 | hev-socks5-tunnel 2.19.3（JNI，`tools/dl/hev.aar`） |
| 界面 | WebView + `app/assets/index.html`（单页）+ Java 原生桥 |
| 配置 | sing-box JSON 配置，走其 Clash 兼容控制接口（127.0.0.1:9090） |
| 扫码 | ZXing（`tools/dl/zxing-core.jar`） |

## 目录结构

```
.
├── app/                        # App 源码
│   ├── AndroidManifest.xml     # minSdk 24，targetSdk 28（见注意事项）
│   ├── assets/
│   │   ├── index.html          # 全部界面与业务逻辑（单页 WebView）
│   │   └── geoip-cn.srs        # sing-box 本地规则集（中国 IP 段）
│   ├── res/mipmap-*/           # 启动图标
│   └── src/
│       ├── com/dabao/proxy/    # MainActivity / ScanActivity / ProxyVpnService
│       └── hev/htproxy/        # hev-socks5-tunnel 的 JNI 桥接类
├── tools/dl/
│   ├── hev.aar                 # hev-socks5-tunnel 2.19.3
│   └── zxing-core.jar          # ZXing 扫码库
├── tests/
│   └── test-v31.js             # 界面逻辑桩测（Node 运行）
├── build-nextin.sh             # 通用版构建（4 ABI：arm64-v8a / armeabi-v7a / x86 / x86_64）
└── build-arm64.sh              # arm64-only 精简版构建
```

构建产物（`work/`、`dist/`、`.apk`、核心二进制、keystore）一律不进仓库，见 `.gitignore`。

## 构建

### 环境准备

- JDK 17（Temurin 17 可直接用）
- Android SDK：build-tools **34.0.0**、platforms **android-34**（`android.jar`）
- `curl` 或 `wget`、`unzip`、`python3`

SDK 位置依次按 `ANDROID_SDK_ROOT` → `ANDROID_HOME` → `~/Android/Sdk` 查找；JDK 按 `JAVA_HOME` → PATH 里的 `java` 查找。

### 签名 keystore（只做一次）

仓库里**没有** keystore，也没有任何密码。首次构建前，自己生成一个，之后每次构建复用同一个（丢了就无法给已安装用户做覆盖升级）：

```bash
keytool -genkeypair -keystore xinghe.keystore -alias xinghe \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$XINGHE_KEYSTORE_PASS" -keypass "$XINGHE_KEY_ALIAS_PASS" \
  -dname "CN=XingHe Proxy, O=XingHe, C=CN"
```

构建时把密码通过环境变量传入（不要写进任何文件）：

```bash
export XINGHE_KEYSTORE_PASS='你的 keystore 密码'
export XINGHE_KEY_ALIAS_PASS='你的 key 别名密码'
```

### 构建

```bash
bash build-nextin.sh   # 通用版（4 ABI，约 104 MB）：dist/xinghe-proxy-universal-v3.1.apk
bash build-arm64.sh    # arm64-only（约 25 MB）：dist/xinghe-proxy-arm64-v3.1.apk
```

构建脚本会检查 `app/assets/sing-box-<abi>.gz` 是否存在，缺失时自动从 sing-box 官方 GitHub Releases 下载对应架构的 `sing-box-1.14.4-android-*.tar.gz`，逐个校验 SHA256（校验值写在脚本里），解压后 gzip 成 App 需要的格式。**核心二进制不随源码发布**，请勿把下载好的 `.gz` 提交进仓库。

### 桩测

```bash
node tests/test-v31.js
```

## 使用

1. 安装 APK，打开 App。
2. 在「订阅」页添加你的订阅链接（或粘贴导入、扫码添加）。
3. 在「节点」页选一个节点，回到首页点电源按钮连接。
4. 代理模式可在首页节点行下方切换（规则 / 全局 / 直连）。

## 注意事项

- **targetSdk 是 28，不要擅自往上改。** Android 10 开始，targetSdk ≥ 29 的应用无法从应用数据目录直接执行程序文件，而 sing-box 核心正是解压到应用数据目录后执行的。升到 29+ 会导致核心报 `Permission denied`、无法启动。这是 v2.3 专门修过的问题。
- 构建不走 Gradle，是 aapt2 + javac + d8 的手动流程（见两个构建脚本），需要上面列出的 SDK 组件齐全。
- 订阅链接里含账号口令，**不要把真实订阅链接、token 或节点信息提交到公开仓库**，也不要贴到 Issue 里。测试数据一律用 `*.synthetic.invalid` 这类假域名。
- 实时连接页只显示**进行中**的连接——短请求结束瞬间就会从列表消失，这是 sing-box `/connections` 接口本身的语义，不是 bug；App 另有「最近连接」列表补记已结束的连接。

## 第三方组件与致谢

- [sing-box](https://github.com/SagerNet/sing-box)（SagerNet，GPLv3，核心二进制按其协议分发，源码见其仓库）
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（heiher，GPLv3，VPN 转发层）
- [ZXing](https://github.com/zxing/zxing)（Apache 2.0，扫码）
- `geoip-cn.srs` 规则集来自 [sing-geoip](https://github.com/SagerNet/sing-geoip)

本仓库自身的代码（界面、Java 桥接、构建脚本）按 MIT 协议开源，见 [LICENSE](LICENSE)。
