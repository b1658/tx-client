# tx-client

The SDK's privileged CAN **transmit** transport, plus the one validated write path.

## What it does

- **`CanTx`** runs the gated root executor `co.screenmate.can.tx.SmCanTx` as **uid 0** over the
  box's **own loopback root adbd** — the same `dadb` channel the `injector` uses — because TX needs
  root (the `screenmate_car` binder is guarded by `SCREENMATE_INTERNAL`, which no app process holds;
  see the parent repo's `docs/TX.md`). Nothing persistent is written: only a ~20 KB dex staged to
  `/data/local/tmp`, shipped as an asset so consumers merge it in automatically.
- **The validated write path** is the **exact TACC set-speed scroll**: it emulates the
  steering-wheel scroll (`VCLEFT_switchStatus` 0x3C2) — a *driver input* the car's own TACC logic
  accumulates, **not** a `DAS_control` override — so it is safe while moving and needs no
  checksum. `exactFrames`/`scrollExact` encode two on-car realities: a `±5` frame **snaps** to the
  next multiple of 5 (it does not add 5), and two identical frames **collapse** (read as a held
  wheel). The `0x213 UI_cruiseControl` path was tried and abandoned (the box is out-voted at the
  gateway — see `docs/EXPLORATIONS.md`).
- **Consumer boundary:** `CanTx` does **not** read vehicle signals. `scrollExact` is given the
  *current* set-speed by the caller, because only a consumer of the vendor broadcast
  (`privileged-client` / the injector) can see `DI_CRUISE_SET_SPEED`. Every safety gate (ack token,
  deny-by-default allowlist, blocklist, live speed gate, audit log) lives in `SmCanTx`.

## Build

Android library module. Requirements: **JDK 17**, Android SDK **platform 34**, Gradle **8.9**
(AGP 8.5.2, Kotlin 1.9.24). `minSdk` 29, `compileSdk` 34. Dependency: `dev.mobile:dadb:1.2.9`.

```bash
./gradlew :tx-client:assembleRelease   # AAR
```

Consumers must add `packaging { resources.excludes += "META-INF/*" }` and, on the app manifest,
`INTERNET` (the loopback socket needs the `inet` group; loopback is **not** exempt).

> **Generated asset not included.** `assets/smcan-tx.dex` is built by `tools/build-tx-dex.sh` from
> `tools/tx/src/.../SmCanTx.java` and is gitignored; rebuild it whenever `SmCanTx.java` changes.
> Without it, `CanTx` has no root executor to run.

## Note

Extracted from a multi-module monorepo; needs a root build/wrapper with the plugin versions above.
