# Phase 3 — File System

Build 3 replaces the demo-only file service path with a real app-private project workspace.

## Workspace

The installed app creates its workspace under its private `filesDir`, so Termux is not required and no broad external-storage permission is required for the core developer workspace.

## Operations

`FileService` now exposes controlled contracts for:

- create file/folder
- read/write
- delete
- copy
- move
- directory listing
- name search

All paths are canonicalized and checked against the workspace root to prevent path traversal.

## UI

File Manager supports navigation, refresh, search, selection, create file/folder and delete. Developer Workspace reads the same persistent filesystem and can save edited files.

## Security boundary

The app-private workspace is the default. Access to user-selected external folders can be added later through Android's Storage Access Framework without weakening the core workspace boundary.
