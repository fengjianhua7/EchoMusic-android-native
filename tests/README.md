# Host-side checks

- `./gradlew authContractTest lyricsContractTest`: login/verification contracts and lyric timing; no emulator required.
- `node --test tests/embedded-api.test.cjs`: private local API transport with explicit fixture modules; no SMS, account login or network calls.
- `EmbeddedRuntimeSmoke.java`: optional real Javet/Node smoke check. Use the official Javet core and matching desktop-native 6.0.2 jars, plus the test-only JSON jar. Extract `app/src/main/assets/echo-api.zip` and pass that absolute directory to the smoke program. `--live-qr` additionally requests an official KuGou QR key without printing its content. It does not scan, authorize an account, or send SMS.

The real-engine host smoke checks startup, private authentication, unavailable static files and the original PNG QR generator. A successful desktop smoke run is not an Android device test.

Official desktop test artifacts:

- https://repo.maven.apache.org/maven2/com/caoccao/javet/javet/6.0.2/javet-6.0.2.jar
- https://repo.maven.apache.org/maven2/com/caoccao/javet/javet-node-windows-x86_64/6.0.2/javet-node-windows-x86_64-6.0.2.jar

Desktop native jars are optional test tools and are not included in the APK or source archive. Android uses the separately included AAR under app/libs.
