# 上游来源与第三方声明

## SmartTube

- 项目：https://github.com/yuliskov/SmartTube
- 基线：32.53，提交 `c75606267ec948462818041879e3b7110fd918bc`
- Copyright (c) 2020-present yuliskov
- 原始 MIT 条款：[LICENSE](LICENSE)
- 本项目修改：内置代理风味、订阅与节点管理、电视代理中心、手机管理、启动等待、应用内路由及品牌/语言调整。此修改版不是上游官方发行版。
- `images/browse_home.png`、`images/video.png` 为继承的上游截图。文档中的代理中心示意图由本项目绘制，不是实机运行证明。

## Mihomo 与 Android 桥接

- 桥接源代码及构建脚本：https://github.com/oviron/libmihomo-android/tree/v0.3.3
- 原生核心源代码：https://github.com/MetaCubeX/mihomo/tree/v1.19.30
- 固定桥接版本：`v0.3.3`；其 `src/main/jni/core/go.mod` 固定核心 `v1.19.30`。
- AAR 来源：https://github.com/oviron/libmihomo-android/releases/tag/v0.3.3
- AAR SHA-256：`3bccc9020926b0b7bf1fd09553b41e4678c80cedc5cbe61d90a78fe4b71aca76`
- 许可证：GNU GPL version 3，见 [Mihomo LICENSE](https://github.com/MetaCubeX/mihomo/blob/v1.19.30/LICENSE) 与 [桥接库 LICENSE](https://github.com/oviron/libmihomo-android/blob/v0.3.3/LICENSE)。
- 本项目提取 AAR 中 `libclash.so` 和 `libmihomo-jni.so`，保留 JNI 包名/方法接口，使用本仓库 `smarttubetv/src/stproxy/java/io/github/oviron/libmihomo/` 中的 Java 8 封装；原生库不在本仓库中修改。

## 子模块与其他依赖

- `MediaServiceCore`：https://github.com/yuliskov/MediaServiceCore/tree/59795dfe138b6b44b4ac8ca639c1596bd59e17b8
- `SharedModules`：https://github.com/yuliskov/SharedModules/tree/13f5687dd6757b02fbcdf14c5403d0339e377db5
- 仓库继承的 ExoPlayer、Leanback、ChatKit 等模块及 Gradle 依赖继续保留各自的 LICENSE、NOTICE 和文件头声明。主许可不取消这些声明。

## 源码与二进制分发

本仓库提供修改版应用源码与构建脚本；固定子模块和上述原生核心/桥接源码共同组成构建所需来源。本次保留根目录的上游 MIT 条款，不重新授予或变更第三方组件的许可证；Mihomo 与桥接库仍分别采用 GPL-3.0。

发布 APK 时，应明确该 APK 对应的应用提交、子模块版本及桥接/核心版本，并随发布提供可取得的对应完整源码及构建材料。请同时保存相应源码归档，不应仅把“公开了应用仓库”当作已经覆盖所有二进制依赖的源码交付。当前 Actions 调试构建与正式发行的签名、源码归档和长期下载渠道是不同事项。
