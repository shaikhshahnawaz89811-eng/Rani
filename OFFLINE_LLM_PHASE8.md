# Phase 8 — Real Small Offline LLM

This build replaces the honest `UnavailableOfflineAI` runtime path with a real on-device GGUF inference adapter.

## Runtime

- `dev.ffmpegkit-maintained:llama-android:0.1.1`
- llama.cpp-backed Android AAR
- CPU/NEON, `arm64-v8a`
- no cloud/API requirement during inference
- no runtime model downloader

## Model

The app deliberately does **not** bundle a 400+ MiB model. In Settings, use **Select GGUF model** to import a model you already have. The selected model is copied into app-private storage and its path is stored in normal app settings; no credentials are stored there.

A practical starting point is a small Q4 GGUF such as Qwen2.5 0.5B. The runtime documentation lists it at about 400 MiB for Q4_K_M. Actual RAM use depends on model/context/device.

## Routing

`ModelRouter` remains the single router:

- Groq configured and successful → Groq
- no key or Groq request failure → real local GGUF engine
- local model missing/unreadable → explicit `ModelUnavailable`

No fake/rule-based fallback is used.

## Resource handling

The local engine lazy-loads the model on first inference, serializes access because a model instance is not thread-safe, and exposes `unload()` to release native model memory.

## Limits

Default local settings:

- context: 2048
- CPU threads: 4
- output: 256 tokens
- maximum configured context: 4096
- maximum configured output: 1024
- CPU-only runtime in this free AAR

These are conservative defaults, not claims about device-wide limits.

## Verification

The project build/test could not be executed in this environment because the Gradle wrapper distribution could not be downloaded: `services.gradle.org` was unreachable. Therefore build/tests are **NOT VERIFIED**, not marked PASS.
