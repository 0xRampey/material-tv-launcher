# Android Use components

Source: https://github.com/austinintelligence/android-use
Commit: `48d8d2e9bc421bd1caff921c1e936330f33414b8`
License: MIT, copyright 2026 drperky20; see LICENSE and the bundled asset notice.

`SemanticCompiler.java` compacts accessibility nodes into conceptual screen rows.
`Targets.java` resolves labels with ambiguity checks. Both are copied from
`device/app/src/main/java/dev/codex/aubridge/`; the only source change is their
package declaration, to share the launcher's Java package.

The launcher supplies its own thin Android accessibility adapter and Gemini turn
loop. The upstream Rust host, ADB transport, socket server, companion APK,
recording, screenshots, and notification components are not included.
