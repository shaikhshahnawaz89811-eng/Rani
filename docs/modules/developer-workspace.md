# Developer Workspace

The Developer Workspace is the primary coding surface. The Explorer reads from the app-private project workspace through `FileService`; it does not access files directly.

The editor supports multiple tabs, save/dirty state, undo/redo, search, replace-all, go-to-line, line numbers, language detection and lightweight syntax highlighting. The editor UI remains a presentation layer; reusable document/history operations live under `core/editor`.

Current language detection covers Python, Kotlin, Java, JavaScript, C, C++, JSON, Markdown and XML. Runtime execution is intentionally handled by the separate runtime phase.
