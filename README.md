# Magora Android Node

**Experimental prototype. Not a promoted Magora build.**

This repository explores whether an unused Android phone can serve as a temporary Magora acoustic listening node. It is separate from the promoted Pi builds, `BUILD.md`, and the Magora portal.

## Current state

The repository currently contains a minimal single-module Kotlin Android scaffold and CI build check. It does not yet capture audio, run inference, access Supabase, or read sensors.

## Prototype constraints

- No camera capture.
- No server-side audio upload or retention.
- Audio is analyzed locally and discarded.
- Do not commit per-node credentials or provisioning secrets.
- Node ingest, when added, must use the node's own authenticated account.
- Sensor and health data remain local unless a later approved task changes the contract.

## Build

The CI build expects Java 17 and runs:

```text
gradle :app:assembleDebug
```

## Install on the Moto g 5G (2022)

1. Factory-reset the phone and avoid personal accounts. Magora Android Node is a place node, not a personal device.
2. On Android 13, open **Settings > About phone > Build number** and tap **Build number** seven times.
3. Open **Settings > System > Developer options** and enable **USB debugging**.
4. Connect the phone to the build computer and accept the USB debugging authorization prompt.
5. In Android Studio, open this repository, select the phone, and choose **Run > Run 'app'**.

To sideload a GitHub Actions debug APK instead:

1. Download the `app-debug` artifact and extract `app/build/outputs/apk/debug/app-debug.apk`.
2. Transfer the APK to the phone.
3. Open it with Files, allow installation from that source under **Install unknown apps**, then tap **Install**.
4. Launch **Magora Prototype Node**, grant microphone permission, and request the battery-optimization exemption from the status screen.

See `AGENTS.md` for repository process and prototype rules.
