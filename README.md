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
| `isSynced` | File on put.io was downloaded to the local mirror; its put.io name ends in `.sk_synced`. |
| `isLocal` | File lives on the Android device. |
| `isLan` | File accessible over the LAN mount. |
| `isRegularRemote` | put.io file not yet synced — shown dimmed at 45 % alpha. |

### displayName vs name

`file.name` for a synced file is e.g. `Chapter1.mp3.sk_synced`.
`file.displayName` strips the `.sk_synced` suffix.

**Always pass `file.displayName` to `MetadataUtils.isX()` calls — never `file.name`.**
The `MetadataUtils` object is annotated with a `// CONTRACT:` reminder.

### File source in requests

When building a request for a synced file (`file.isSynced == true`), set `use_local = true`
instead of `download_url`.  The daemon will read the file from the local mirror via its
sync index, avoiding a redundant re-download.

---

## Daemon Actions (summary)

Putz sends JSON files to `.calibre_integration/requests/` on Google Drive.
The daemon picks them up within 15 s, processes them, and writes a response to
`.calibre_integration/responses/`.

| Action | Sent from | What happens |
|--------|-----------|-------------|
| `ADD_BOOK_BATCH` | `CalibreRepository.sendBatchRequest()` | Adds one or more formats to a Calibre book. Item types: SINGLE, PACK (MP3→M4B), ARCHIVE, ARCHIVE_ENTRY. |
| `REPLACE_COVER` | `CalibreRepository.sendReplaceCoverRequest()` | Replaces the cover of an existing Calibre book. |
| `UPDATE_COMMENTS` | `CalibreRepository.sendUpdateCommentsRequest()` | Updates comments, title, author, and/or tags. |
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

- COMPLETED transfers: user taps "Remove"; optionally deletes the source file from put.io.
- Duplicate-format failures: treated like COMPLETED for cleanup purposes.
- Other failures: transfer deleted from Room DB; remote files untouched.

---

## Key Source Files

| File | Role |
|------|------|
| `data/model/PutioFile.kt` | File state flags (`isSynced`, `isRegularRemote`, `displayName`) |
| `data/repository/CalibreRepository.kt` | All request data classes and send functions |
| `data/remote/GDriveManager.kt` | Drive upload/download primitives |
| `ui/components/FileItem.kt` | Per-file menu; visibility of every item is gated on file state — see `CONTRACTS.md §19` |
| `ui/files/FilesViewModel.kt` | Business logic for all file actions |
| `ui/files/FilesScreen.kt` | Screen composition; contains the audiobook file filter — **must use `it.displayName`**, not `it.name` (stubs end in `.sk_synced`; see `CONTRACTS.md §2`) |
| `ui/files/AudiobookPackSheet.kt` | "Select files for audiobook" dialog; receives the pre-filtered list from `FilesScreen.kt` |
| `util/MetadataUtils.kt` | Extension-based file type checks — **all callers must pass `file.displayName`, never `file.name`** |

Search for `// CONTRACT:` in the source to find every integration boundary.
