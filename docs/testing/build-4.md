# Build 4 Testing

Unit coverage added for:

- editor dirty-state tracking
- bounded undo/redo history
- case-insensitive replace-all
- line-count behavior

UI/manual verification target:

1. Open Developer Workspace.
2. Open multiple files from Explorer.
3. Switch tabs.
4. Edit a file and verify the dirty dot/status.
5. Save and verify the saved state.
6. Undo and redo edits.
7. Search and verify match count.
8. Replace all matches.
9. Use Go to line.
10. Close a clean tab and verify the active tab changes.
