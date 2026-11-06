# EchoMusic TV 0.10.2：Android 6.0 / ARMv7 兼容测试版

安装 EchoMusic-TV-0.10.2-armeabi-v7a-api23-compat.apk。包名 cc.echomusic.android.tv，versionCode 15，最低 API 23，唯一架构 armeabi-v7a。与此前版本使用同一测试签名，可先尝试覆盖安装保留账号；出现签名冲突时先保存账号信息再处理，避免直接卸载丢失数据。

本版实装旧系统兼容代码：音频焦点、前台服务、通知、状态栏、文件夹跳转均按系统版本分支；Java Base64/Stream 使用核心库回移；异步等待替换为 API23 可用实现。ARMv7 内置接口引擎增加网络接口枚举和线程屏障兼容库，并修正六个 API24 导入的 ELF 版本元数据，避免仅降低 minSdk 后依然找不到符号。原始二进制及补丁后哈希记录于 ARMV7_RUNTIME_PROVENANCE.json，补丁脚本和 C 源码随源码包提供。

构建和静态检查通过，APK 只包含四个 ARM32 原生库。尚未连接 Android 6.0 的 ARMv7 电视，未验证安装、首次启动、扫码登录、连续播放及遥控器操作；静态检查不能代替这些实机验证。安装后先测试上述流程，再测试伴奏、收藏和局域网远控。

Node.js Mobile 18.20.4 基于已结束维护的 Node 18，仅作为旧电视兼容测试方案。未验证整个运行库的 16KB 页面支持。不将此版本标为正式稳定版。

构建：JDK17 / SDK35 / Gradle8.11.1，执行 assembleTv32TvOptimized lintTv32TvOptimized。重新制作原生库：从官方 Node.js Mobile v18.20.4 Android 归档提取 bin/armeabi-v7a/libnode.so，运行 scripts/patch_node_api23.py 原始库 输出库，再用 scripts/build_armv7_bridge.ps1 -NdkRoot 指定 NDK 根目录构建兼容库与 JNI 桥。保留归档许可证和来源记录。
