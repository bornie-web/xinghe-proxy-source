# tools/dl/ 第三方依赖说明

本目录只放构建必需的第三方库（源码不含这些库的源码，只含编译产物）：

| 文件 | 来源 | 用途 |
|---|---|---|
| `hev.aar`（596 KB） | [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 2.19.3 发布的 AAR | VPN 转发层的 JNI `.so`，构建脚本从中按 ABI 解出 `libhev-socks5-tunnel.so` |
| `zxing-core.jar`（596 KB） | [ZXing](https://github.com/zxing/zxing) zxing-core | 扫码添加订阅（`ScanActivity`） |

sing-box 核心二进制**不在**本目录：构建脚本在构建时从 sing-box 官方
GitHub Releases（SagerNet/sing-box v1.14.4）自动下载 `sing-box-1.14.4-android-*.tar.gz`
并校验 SHA256，解压后压成 `app/assets/sing-box-<abi>.gz`。
不要把下载好的核心二进制提交进仓库。
