# Build 8 Security Hardening

- Credentials use Android Keystore-backed AES-GCM storage through `SecureStore`.
- AI tools retain risk classifications and approval boundaries.
- `DefaultSecurityPolicy` provides workspace-path and command-policy checks.
- Sensitive values must never be written to logs, source code, or UI state.
- Runtime/file/Git backends return structured results instead of exposing raw exceptions.
- Microphone access is declared but must be requested at runtime before speech recognition is added.

# Voice boundary

`SpeechToText` and `TextToSpeechEngine` are independent interfaces. The included Android TTS adapter can speak Sara responses without coupling the AI engine to Android APIs. A future offline STT engine can implement `SpeechToText` without changing the desktop or AI contracts.
