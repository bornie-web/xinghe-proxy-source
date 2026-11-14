# 源码包清理记录（v3.1）

本文件记录从开发工程整理出可公开的源码包时，删除与修改了哪些内容，
以及最终的残留扫描结果。生成时间：2026-11-13。

## 删除的文件（未进源码包）

| 文件 | 原因 |
|---|---|
| `xinghe.keystore`（签名密钥库，根目录） | 私钥与证书，泄露后任何人都能冒名签发同包名应用 |
| `app/assets/sing-box-*.gz`（4 个，约 104 MB） | sing-box 官方核心二进制，按 GPLv3 应从官方渠道获取；构建脚本改为构建时自动下载并校验 SHA256 |
| `tools/dl/mihomo-*.gz`、`tools/dl/mihomo-test/`、`work/singbox/*.tar.gz` | 旧版 Mihomo 核心与测试用二进制，不再是当前版本依赖 |
| `tools/dl/hev.tar.xz`、`tools/dl/hev-aar/`、`tools/dl/hev-android/`、`tools/dl/hev-classes/`、`tools/dl/hev-tar/` | hev 的中间解包产物；只保留 `tools/dl/hev.aar`（构建脚本从中解 `.so`） |
| `tools/dl/xz-1.10.jar` | 构建不再使用的解压库 |
| `work/` 全部（构建中间件、`.apk`、截图、复现脚本、开发日志、`verify.txt`） | 与源码无关的开发记录与构建产物 |
| 全部 `.apk` 文件 | 构建产物，不进源码仓库 |

## 修改的文件

| 文件 | 改动 |
|---|---|
| `build-nextin.sh`（新建，替代原脚本） | 写死的 keystore 密码与别名密码全部删除，改为环境变量 `XINGHE_KEYSTORE_PASS` / `XINGHE_KEY_ALIAS_PASS` 读取；缺失时打印 keytool 生成命令并退出。keystore 不存在时自动生成（用环境变量密码）。新增 `download_core`：缺失核心时从 `github.com/SagerNet/sing-box/releases/download/v1.14.4/` 下载对应架构 tar.gz，校验 SHA256 与文件大小，解压后 gzip 成 `app/assets/sing-box-<abi>.gz`。JDK/SDK 改为按 `JAVA_HOME` / `ANDROID_SDK_ROOT` 等环境变量定位（保留本地旧路径作后备）。产物目录改为 `dist/`，校验日志写 `work/verify.txt` |
| `build-arm64.sh`（新建，替代 `work/arm64-only/build-arm64.sh`） | 同上：密码改环境变量；keystore 缺失时提示用通用版生成；仅下载 arm64 核心 |
| `README.md`（新建） | 项目介绍、功能、技术栈、构建与签名方法、使用说明、targetSdk 28 注意事项 |
| `LICENSE`（新建） | MIT 协议，版权行“星核代理” |
| `.gitignore`（新建） | 忽略 `*.apk`、`*.keystore`、`*.jks`、`sing-box-*.gz`、`work/`、`dist/` 等 |
| `tools/dl/README.md`（新建） | 说明 `hev.aar` 与 `zxing-core.jar` 的来源与用途 |

## 保留的第三方二进制（已确认无敏感内容）

| 文件 | 说明 |
|---|---|
| `tools/dl/hev.aar`（596 KB） | hev-socks5-tunnel 2.19.3 官方 AAR，仅含 JNI `.so` 与桥接类 |
| `tools/dl/zxing-core.jar`（596 KB） | ZXing 扫码库 |
| `app/assets/geoip-cn.srs`（36 KB） | sing-box 中国 IP 规则集数据文件 |

## 残留扫描（对源码包全树执行）

扫描关键词：`password`、`keystore`、`storepass`、`keypass`、`api_key`、`secret`、
`token=`、`ttti`、真实订阅域名、`.keystore`、`.jks`、`.apk`。

- `password`：仅出现在两个构建脚本的**环境变量名**与注释（`XINGHE_KEYSTORE_PASS` /
  `XINGHE_KEY_ALIAS_PASS`）、`index.html` 里的节点配置**字段名**（解析器读 `n.password` 等，
  无任何密码值）与本文件，**无任何密码明文值**。
- `keystore`：仅出现在构建脚本的变量名、注释与 README 的生成命令中；**无 keystore 文件**。
- `token=`：无匹配（订阅 token 只存在于用户设备本地数据中，从不进源码）。
- 真实订阅域名 / 真实节点信息：无匹配。测试文件 `tests/test-v31.js` 里的域名
  全部为 `*.synthetic.invalid` 合成数据。
- 源码里的 URL 只剩两类：`https://www.gstatic.com/generate_204`（延迟测试用公共地址）
  与 `http://127.0.0.1:9090`（本地 sing-box 控制接口）。
- 扫描命令（可复现）：
  `grep -rniE "password|keystore|storepass|keypass|api_key|secret|token=|ttti|\.apk" <源码包根目录>`
  另用 `find` 确认包内无 `.keystore` / `.jks` / `.apk` / `sing-box-*.gz` 文件。

## 校验值（供构建脚本使用）

sing-box v1.14.4 官方 tar.gz（GitHub API asset digest）：

| ABI | Asset | SHA256 |
|---|---|---|
| arm64-v8a | sing-box-1.14.4-android-arm64.tar.gz | fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f |
| armeabi-v7a | sing-box-1.14.4-android-arm.tar.gz | a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9 |
| x86 | sing-box-1.14.4-android-386.tar.gz | 6b715f5ee3d1ad1738da99e7c207db807a06560f623a2a44105dd3c34339fd93 |
| x86_64 | sing-box-1.14.4-android-amd64.tar.gz | e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac |
