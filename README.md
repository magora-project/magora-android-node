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

See `AGENTS.md` for repository process and prototype rules.
