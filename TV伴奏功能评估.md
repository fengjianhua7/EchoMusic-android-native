# TV 伴奏功能评估

评估日期：2026-10-27。结论：**可采用轻量音轨切换方案实现，但需要真实分轨音源和电视解码验证；本次 0.10.0 没有开放未经验证的伴奏开关。**

## PC 版实际做法

PC 客户端将“人声/伴奏”效果映射为 `acappella`，向平台获取对应音源。遇到多音轨 MKV 时，探测音轨名称、类型和标识，选择伴奏轨；探测失败时还存在桌面特有的轨号回退。它没有在这个流程中使用本地 AI 模型实时分离人声。

依据：[PC 播放解析源码](https://github.com/hoowhoami/EchoMusic/blob/4a641804bad8b97d8951dab6916651e825a78115/src/renderer/stores/player/resolver.ts)；配套 API 的 [song_url_auth_merge 模块](https://github.com/MakcRe/KuGouMusicApi/blob/3bc182f50890820260963bff294ccd17d0183cec/module/song_url_auth_merge.js) 明确列出 `acappella` 为人声/伴奏分离音源类别。

## TV 可行路线

1. 在当前账号授权下请求平台的 `acappella` 音源，歌曲无此音源或账号无权限时明确提示。
2. 用播放器探测实际音轨，只从音频轨中选择伴奏；不直接照搬 PC 的轨号，因为安卓轨道索引与桌面标识并不相同。
3. 切换时保留进度、音量、暂停状态和歌词偏移。选轨失败时回退到原唱，并显示提示，不能把原唱标成“伴奏”。

Android 原生 [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer#selectTrack(int)) 提供音轨枚举与选择接口，现有 TV 播放器可优先复用它，避免引入模型及额外桌面音频引擎。这里的“开销小”是基于切换现成音轨的实现方式作出的推断，尚无电视 CPU/内存实测。

## 需要验证的条件

- 平台是否对当前歌曲和账号返回有效分轨音源，地址是否支持网络跳转和重新取流。
- 实际 MKV 内是何种音频编码，电视是否能解码及切换该轨。安卓官方说明[非手机/平板形态的媒体格式支持可能不同](https://developer.android.com/media/platform/supported-formats)。
- 电视音轨切换后是否保持正确时间轴，暂停/快进/下一首后是否仍处于正确模式。
- 多种品牌电视、不同 Android 版本和低内存机型的稳定性。

本次只完成源码与接口评估，没有取得真实账号的 `acappella` 文件，也没有接入电视测试。若原生解码不满足要求，再评估增加 Media3 解封装/解码支持；不宜一开始把桌面 Rust/FFmpeg 引擎整体搬入 TV。
