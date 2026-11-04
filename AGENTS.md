# Magora Android Node

## Repository status

This repository is an experimental prototype, not a promoted Magora Android build. Do not add code to the promoted portal, Pi firmware, or `BUILD.md`.

## Process

- Create feature branches and PRs to `main`; do not push directly to `main`.
- Do not commit credentials, `service_role`, `PROVISION_SECRET`, personal data, or node configuration files.
- Keep capture, inference, ingest, and sensor features out of scaffold commits unless a task explicitly authorizes them.
- The Android application is a single module named `app`.

## Build

CI runs `gradle :app:assembleDebug` with Java 17. Keep the Gradle build compatible with Gradle 8.13.

## Prototype constraints

- Audio is analyzed locally and discarded.
- Do not implement camera capture.
- Factory-reset devices used as nodes; no personal accounts or data belong on the device.
- Phase 1 must not write to Supabase.
- Phase 2 ingest must use one node's per-node credentials only.
- Sensor and health data remain local unless the prototype task explicitly changes the contract.
