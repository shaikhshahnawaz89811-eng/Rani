# Security Model

No credentials are embedded in source. Git push is treated as a sensitive operation and the initial adapter returns a confirmation-required result. Future GitHub tokens/SSH keys must be stored using Android secure storage/Keystore-backed mechanisms and must never be logged.

The initial terminal intentionally blocks arbitrary shell execution. A real runtime adapter must be permission-scoped and isolated from the AI tool layer.
