# Putz — put.io Client & Calibre/Plex Integration

Putz is an Android app with three main roles:

1. **put.io client** — browse files and folders, manage transfers, add magnet links.
2. **Calibre front-end** — send books and audiobooks to a remote Calibre library
   managed by the Sidekick daemon.
3. **Plex front-end** — move synced video files and subtitles from the put.io local
   mirror into a Plex movie library.

For the full protocol between Putz and the daemon (all action types, request/response
schemas, stub convention, side effects) see:
  **`../calibreanywhere/sidekick/CONTRACTS.md`**

---

## Key Concepts

### put.io file states (PutioFile.kt)

| Property | Meaning |
|----------|---------|
| `isSynced` | File on put.io was downloaded to the local mirror; put.io name contains `.sk_synced`. |
| `isNewFormatStub` | Stub uses the new format: filename ends in `.sk_synced.<original_putio_id>`. |
| `syncedFileId` | The original put.io file ID — embedded in the stub filename for new-format stubs, equals `id` for old-format stubs. |
| `isLocal` | File lives on the Android device. |
| `isLan` | File accessible over the LAN mount. |
| `isRegularRemote` | put.io file not yet synced — shown dimmed at 45 % alpha. |

### Stub filename format

New-format stub: `Chapter1.mp3.sk_synced.1589002411`
- `displayName` → `Chapter1.mp3`
- `syncedFileId` → `1589002411` (the original put.io file ID, extracted from the suffix)
- `id` → the stub's current put.io ID (needed only for deleting the stub itself)

Old-format stub: `Chapter1.mp3.sk_synced` (no numeric suffix)
- `displayName` → `Chapter1.mp3`
- `syncedFileId` → `id` (the stub and original share the same put.io ID)

**Always pass `file.displayName` to `MetadataUtils.isX()` calls — never `file.name`.**
The `MetadataUtils` object is annotated with a `// CONTRACT:` reminder.

### File source in requests

When building a request for a synced file (`file.isSynced == true`):

1. Set `use_local: true` in the item.
2. Read the stub's JSON content via `CalibreRepository.readStubLocalPath(file)` to get
   the `local_path` string.
3. Include `local_path` in the request item — the daemon resolves it relative to
   `putio_repo_root` and reads the file from the local mirror, avoiding a re-download.

Old-format stubs (no numeric suffix) do not include `local_path` in the request;
the daemon falls back to `SyncedFileIndex` during the migration period.

### Mirror file access (preview / download / archive browsing)

For synced files, Putz reaches the local mirror via the daemon's LAN HTTP endpoint:

```
GET /api/mirror/file/<syncedFileId>?local_path=<url-encoded-relative-path>
```

- `syncedFileId` is `file.syncedFileId` (original file ID, **not** `file.id`)
- `local_path` is read from the stub JSON and URL-encoded
- The endpoint streams the file with HTTP range-request support (required for archive browsing)

For **archive browsing**, the `ArchiveSource.Mirror` source type is used with
`MirrorArchiveStream`, which fetches archive data via HTTP range requests so large archives
are not fully downloaded before 7-zip can inspect them.

---

## Daemon Actions (summary)

Putz sends JSON files to `.calibre_integration/requests/` on Google Drive.
The daemon picks them up within 15 s, processes them, and writes a response to
`.calibre_integration/responses/`.

LAN direct mode: when the daemon is reachable, requests can also be submitted via
`POST /api/request` on the LAN HTTP server (bypassing Google Drive).

| Action | Sent from | What happens |
|--------|-----------|-------------|
| `ADD_BOOK_BATCH` | `CalibreRepository.sendBatchRequest()` | Adds one or more formats to a Calibre book. Item types: SINGLE, PACK (MP3→M4B), ARCHIVE, ARCHIVE_ENTRY. |
| `REPLACE_COVER` | `CalibreRepository.sendReplaceCoverRequest()` | Replaces the cover of an existing Calibre book. |
| `UPDATE_COMMENTS` | `CalibreRepository.sendUpdateCommentsRequest()` | Updates comments, title, author, and/or tags. |
| `FUSE_BOOKS` | CalibreAnywhere → Putz → daemon | Merges two or more Calibre books into one new book; deletes source books. |
| `SEND_TO_PLEX` | `CalibreRepository` (Plex flow) | Moves a synced video from the mirror to the Plex library; daemon triggers Plex scan. |
| `ADD_SUBTITLE_TO_MOVIE` | `CalibreRepository` (subtitle flow) | Moves a synced subtitle into a Plex movie folder; daemon triggers Plex scan. |
| `PRIORITY_PUTIO_SYNC` | `CalibreRepository.sendPrioritySyncRequest()` | Asks the daemon to download a specific put.io file immediately. |
| `GLOBAL_STATUS_PROBE` | `CalibreRepository.sendGlobalStatusProbe()` | Checks whether the daemon is idle or busy. |

### Response statuses

`PROCESSING` · `COMPLETED` (+ `calibre_book_uuid` for book ops) · `FAILED` ·
`QUEUED` / `ALREADY_QUEUED` (priority sync only)

---

## Transfer Lifecycle (book/audio transfers)

1. **PENDING / REQUESTED** — request JSON uploaded to Drive; entity saved in Room DB.
2. **Polling** — background loop checks responses/ every ~10 s; reads heartbeat.json.
3. **PROCESSING** — daemon acknowledged the request.
4. **COMPLETED / FAILED** — final state from daemon response.

### Watchdog interventions

- **Upload retry** (up to 3×, ≥30 s apart) if the initial Drive upload failed.
- **Daemon-missed retry** — if daemon returns "not found", assumes a race condition
  and re-uploads the request (up to 3×).
- **Probe** (`is_probe: true`) — sent after 5 min stuck in PENDING/REQUESTED/PROCESSING;
  asks the daemon to check if the expected outcome already exists without re-downloading.

### End of life

- COMPLETED transfers: user taps "Remove"; optionally deletes the source stub from put.io.
  `CalibreRepository.deleteFileFromPutio()` searches for the stub by its embedded file ID
  (`.sk_synced.<syncedFileId>`) and deletes the stub — not the original ID (which is gone).
- Duplicate-format failures: treated like COMPLETED for cleanup purposes.
- Other failures: transfer deleted from Room DB; remote files untouched.

---

## Key Source Files

| File | Role |
|------|------|
| `data/model/PutioFile.kt` | File state flags (`isSynced`, `isNewFormatStub`, `syncedFileId`, `displayName`) |
| `data/model/ArchiveEntry.kt` | `ArchiveSource` sealed class — `Local`, `Lan`, `Putio`, `Mirror` |
| `data/archive/MirrorArchiveStream.kt` | HTTP range-request stream for daemon mirror endpoint |
| `data/repository/CalibreRepository.kt` | All request data classes, send functions, `readStubLocalPath()` |
| `data/repository/ArchiveRepository.kt` | Archive open/list/extract; routes `Mirror` source to `MirrorArchiveStream` |
| `data/remote/GDriveManager.kt` | Drive upload/download primitives |
| `data/transport/LanDaemonTransport.kt` | LAN HTTP calls — `downloadMirrorFile(localPath)`, `submitRequest()` |
| `ui/components/FileItem.kt` | Per-file menu; visibility of every item is gated on file state — see `CONTRACTS.md §19` |
| `ui/files/FilesViewModel.kt` | Business logic for all file actions (preview, download, send, delete) |
| `ui/files/FilesScreen.kt` | Screen composition; contains the audiobook file filter — **must use `it.displayName`**, not `it.name` |
| `ui/files/AudiobookPackSheet.kt` | "Select files for audiobook" dialog; receives the pre-filtered list from `FilesScreen.kt` |
| `ui/archive/ArchiveViewModel.kt` | Archive browsing; resolves `local_path` from stub JSON before opening Mirror source |
| `util/MetadataUtils.kt` | Extension-based file type checks — **all callers must pass `file.displayName`, never `file.name`** |

Search for `// CONTRACT:` in the source to find every integration boundary.
