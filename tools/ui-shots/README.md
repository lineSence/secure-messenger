# Android UI regression gate

Pinned toolchain: JDK 17, Gradle 9.8.1, Rust 1.99.0, SDK 37.0. Screens render with Robolectric 4.17 / native graphics, API 35, density xhdpi. Synthetic data only. HTML is not a baseline.

- `bash tools/ui-shots/run.sh verify` (default) renders production Compose sources and compares every PNG against `goldens.json`. Missing, unexpected and changed images fail. Verify cannot rewrite goldens.
- `bash tools/ui-shots/run.sh record` produces candidate goldens. Review the images, accepted mockup and before/after pairs before committing the manifest. Never record just to silence a failure.
- `python3 tools/ui-shots/verify.py verify` verifies an already rendered directory. CI publishes `sm-native-ui` images and test reports for review.

The versioned baseline is a PNG SHA256/dimension manifest (no binary source files in this repository). UI-only clock is fixed to 2026-10-14 UTC; cryptography, expiration and persistence never use that clock. Adaptive fixtures cover 320×500 dp / text 200%, including independently scrolling sheet body and accessible actions. Semantic tests cover a single switch action and selected tab roles. Actual SQLite tests use native Android SQLite and the production AES-GCM codec with explicitly injected synthetic test keys; this does not test Android Keystore hardware.

Regressions must be corrected or explicitly accepted as a design change, then visually reviewed. This gate does not replace TalkBack, IME, rotation/process-kill, notifications, Bluetooth/network or Keystore tests on devices.
