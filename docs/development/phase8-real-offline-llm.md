# Phase 8 — Real Offline LLM Integration

Implemented on top of the existing Phase 1–7 architecture.

## Runtime

The project now uses `dev.ffmpegkit-maintained:llama-android:0.1.1`, a prebuilt Android AAR wrapping llama.cpp for local GGUF inference. The runtime is CPU/NEON and arm64-v8a, with no Groq/web API required during inference.

## Model

The selected compact model is **SmolLM2-135M-Instruct Q4_K_M**. It is approximately 105 MB and is Apache-2.0 licensed according to its model card. It is intentionally small and is not expected to match Groq/Claude-level reasoning.

The model is **not bundled** and the app does not contain a hidden downloader. The user imports the GGUF once from Settings. The file is copied into app-private storage and checked for a GGUF header before it becomes available to the local runtime.

Model reference: https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF

## Existing architecture reused

- `ModelRouter`
- `AIService` / `LocalModelEngine`
- existing `AISettingsStore`
- existing tool/permission architecture
- existing task engine
- existing AI UI

No second router, task engine, terminal, browser, SecureStore, or permission system was introduced.

## Runtime lifecycle

- model is loaded lazily on the first local request
- inference is bounded to a 2,048-token context and 256 generated tokens
- native model memory can be released through `LocalChatEngine.unload()`
- model loading/inference errors are surfaced as real `ModelUnavailable`/`Execution` errors
- no fake offline response exists

## Routing

- Groq configured and working → Groq
- no Groq key → local model when installed
- Groq request fails → local model when installed
- local model missing/invalid → honest `ModelUnavailable`

Live browser/GitHub/AI-website operations remain network-dependent and are not fabricated by the local model.
