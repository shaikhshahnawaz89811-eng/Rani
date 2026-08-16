# Phase 8 — Real Small Offline LLM

This build replaces the honest `UnavailableOfflineAI` runtime path with a real on-device GGUF inference adapter.

## Runtime (updated)

- `dev.ffmpegkit-maintained:llama-android:0.1.1` was never real — it does not exist on Maven Central or any other repository and was removed.
- Real replacement now wired in: `com.llamatik:library:1.10.0` — verified on Maven Central: https://central.sonatype.com/artifact/com.llamatik/library
- Kotlin Multiplatform wrapper around llama.cpp; Apache-2.0; Android minSdk 26 (matches this app's minSdk)
- Consumed via `com.llamatik.library.platform.LlamaBridge` from `LocalLlamaEngine` — no NDK/CMake setup in this module, the published AAR bundles the native llama.cpp build itself
- CPU-only in this build (`gpuLayers = 0`); no cloud/API requirement during inference
- no runtime model downloader

## Model

The app deliberately does **not** bundle a model. In Settings, use **Select GGUF model** to import a model you already have. The selected model is copied into app-private storage and its path is stored in normal app settings; no credentials are stored there.

A user-requested starting point is `qwen2.5-coder-3b-instruct-q4_k_m.gguf` (~1.9 GiB, under the app's 2 GiB local-model size limit). Actual RAM use depends on model/context/device.

## Routing

`ModelRouter` remains the single router:

- Groq configured and successful → Groq
- no key or Groq request failure → real local GGUF engine
- local model missing/unreadable → explicit `ModelUnavailable`

No fake/rule-based fallback is used. (Default-to-offline-unless-Groq-requested routing is a separate, not-yet-implemented change — see the chat history for this feature, it needs a design/risk pass before touching the working Groq tool-calling loop.)

## Resource handling

The local engine lazy-loads the model on first inference, serializes access through a mutex because `LlamaBridge` is a process-wide native singleton, releases the previous model via `LlamaBridge.shutdown()` before loading a different one, and exposes `unload()` to release native model memory.

## Limits

Default local settings:

- context: 2048
- CPU threads: 4
- output: 256 tokens
- maximum configured context: 4096
- maximum configured output: 1024
- CPU-only runtime (`gpuLayers = 0`)

These are conservative defaults, not claims about device-wide limits.

## Verification

**NOT VERIFIED.** This sandbox has no network access (Maven artifact could not be downloaded/resolved here), no Android SDK/NDK, and no device/emulator — so `com.llamatik:library` could not actually be resolved, compiled, or run against a real GGUF model here. The Kotlin source was written and manually traced against the three existing `LocalLlamaEngineTest` cases (all three stay green because none of them reach a loaded-model code path that calls into `LlamaBridge`), but a real `./gradlew build`/instrumented run on a device or emulator with the actual GGUF file is still required before this is marked done.
