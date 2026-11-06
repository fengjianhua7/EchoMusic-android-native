# TV 0.10.3 — API23 ARMv7

See TV-0.10.3.md for current changes and real-device validation. Earlier compatibility notes below refer to prior artifacts.

# API23 ARMv7 compatibility 0.10.2

See ARMv7兼容说明.md and VERIFICATION-ARMV7.txt. The prior 0.10.1 API26 notes below describe the earlier artifact.

# ARMv7 compatibility 0.10.1

32-bit TV target: `assembleTv32TvOptimized lintTv32TvOptimized`. This variant embeds Node.js Mobile 18.20.4 (EOL); see ARMv7兼容说明.md / VERIFICATION-ARMV7.txt for limitations. Prebuilt ARMv7 libraries and full source/attribution are retained. JNI bridge rebuild: scripts/build_armv7_bridge.ps1 with an Android NDK root.

# EchoMusic Android / TV 0.10.0

Phone/tablet: `assembleHandheldDebug`; 64-bit TV: `assembleTvTvOptimized` (R8, ARM64, TV launcher, debug signing).
Checks: `lintHandheldDebug lintTvTvOptimized lanContractTest authContractTest lyricsContractTest cloudContractTest queueContractTest downloadContractTest lyricWindowContractTest`.
JDK17 / SDK35. Rebuild private API: `python scripts/prepare_embedded.py`. Rebuild attributed remote UI: `node scripts/prepare_remote.cjs`.
LAN interface is separate from the account API and requires pairing. See 使用说明.md / THIRD_PARTY_REMOTE.md / VERIFICATION.txt for usage, attribution and the untested device boundaries.
