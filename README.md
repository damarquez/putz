# Putz - Put.io Client

This Android app is a Put.io client that allows you to browse files, manage transfers, and integrate with a remote Calibre instance.

## Features
- Browse Put.io files and folders.
- Manage active transfers.
- Add new transfers via magnet links.
- **Calibre Integration**: Send books and audiobooks directly to a remote Calibre library via Google Drive synchronization.

---

## Calibre Daemon Protocol

Putz communicates with a remote Calibre daemon (e.g., Sidekick) using JSON request and response files synchronized through a dedicated Google Drive folder.

### 1. Request Format (`ADD_BOOK_BATCH`)

All book-related requests (single files, audiobook packs, archives, or multi-format batches) use a unified batch format.

**Request Filename**: `req_<anchor_id>.json` (where `<anchor_id>` is the `putio_file_id` of the first item).

#### JSON Schema
| Field | Type | Required? | Description |
| :--- | :--- | :---: | :--- |
| `action` | String | Yes | Always `"ADD_BOOK_BATCH"`. |
| `putio_file_id` | Long | Yes | The **Anchor ID**. Used to identify the transfer record in the app. |
| `title` | String | Yes | The title of the book in Calibre. |
| `author` | String | Yes | The author of the book in Calibre. |
| `items` | Array | Yes | A list of `BatchItem` objects to process sequentially. |
| `is_probe` | Boolean | No | If `true`, the daemon should only check if the book exists, skipping downloads. |

#### `BatchItem` Object
| Field | Type | Description |
| :--- | :--- | :--- |
| `type` | String | `"SINGLE"`, `"PACK"`, or `"ARCHIVE"`. |
| `putio_file_id` | Long | The unique ID of the file on Put.io. |
| `fileName` | String | The display name or pack label. |
| `download_url` | String | The direct download URL (refreshed by the app on each attempt). Omitted if `is_probe` is true. |
| `files` | Array | **Required for `PACK`**. List of `{ fileName, download_url, putio_file_id }` for audiobook tracks. |
| `archiveMode` | String | **Optional for `ARCHIVE`**. `"default"` or `"audio"`. |

---

### 2. Status Probes
The app can check the daemon's health using a global status probe.

**Request Filename**: `req_global_status.json`
**Content**: `{"action": "GLOBAL_STATUS_PROBE"}`

---

### 3. Response Format

The daemon must write a response JSON file to the `responses/` folder on Google Drive.

**Response Filename**: `res_<anchor_id>.json` (or any unique name, as the app parses the content).

#### JSON Schema
| Field | Type | Description |
| :--- | :--- | :--- |
| `action` | String | The action processed (e.g., `"ADD_BOOK_BATCH"`). |
| `putio_file_id` | Long | The **Anchor ID** from the request. |
| `status` | String | `"PROCESSING"`, `"COMPLETED"`, or `"FAILED"`. |
| `error` | String | Error message if status is `"FAILED"`. |
| `daemon_status` | String | (Optional for global probes) `"IDLE"` or `"WORKING"`. |

---

### 4. Daemon Processing Logic
1.  **Atomic Creation**: The daemon should use the top-level `title` and `author` to create or find a single book record in Calibre before processing items.
2.  **Sequential Addition**: Iterate through the `items` list and attach each file/pack to the *same* book record identified in step 1.
3.  **Cleanup**: The daemon should delete its temporary working files after each item to save space.
4.  **Reporting**: Upload a response with `status: "COMPLETED"` only after all items have been attempted.


### Daemon and Putz

- Putz places requests to the daemon. Here is the current lifecycles of the requests:
  The Transfer Lifecycle (State Machine)

  Once a transfer request (Types 1-4) is initiated, it enters a state machine managed by CalibreTransferEntity.status
  and tracked in CalibreRepository.

    1. Initial State: PENDING or REQUESTED
    * Action: The app saves the transfer to its local Room database.
    * Network: It attempts to upload the req_[id].json file to the .calibre_integration/requests folder on Google Drive.
    * Outcome:
        * If upload succeeds: State becomes REQUESTED.
        * If upload fails: State becomes FAILED (with error "Failed to upload to GDrive").

    2. The Waiting Game & Polling
       While in REQUESTED state, the app runs a background polling loop (every 10 seconds) looking for two things:
    2.1. Responses: It scans for files like res_[id].json in the Drive root.
    2.2. Heartbeats: It reads heartbeat.json to know if the daemon is currently IDLE or WORKING.

    3. State Progression via Daemon Responses
       When the daemon processes a request, it writes a response file. The app reads this and updates the transfer state:
    * PROCESSING: The daemon has picked up the request and is currently downloading the file from put.io or crunching
      data.
    * COMPLETED: The daemon successfully added the book, updated the cover, or modified the comments.
    * FAILED: The daemon encountered an error (e.g., file not found, bad format, existing book duplicate).

    4. Automated Interventions (The "Watchdog")
       The app actively monitors active transfers for anomalies:
    * Automatic Retry (Upload Failures): If a transfer failed during the initial upload to Google Drive, the app will
      automatically attempt to resend it up to 3 times, waiting at least 30 seconds between attempts.
    * Automatic Retry (Daemon Missed It): If the daemon responds that the file was "not found", the app assumes the
      daemon missed the request file (a race condition). It will automatically retry the upload up to 3 times with a
      random delay.
    * Probing Stuck Transfers: If a transfer sits in PENDING, REQUESTED, or PROCESSING for more than 5 minutes without
      an update, the app generates a "Probe" request (is_probe: true). This reminds the daemon to check on that specific
      ID without forcing it to restart the whole download process.

    5. User Interventions
    * Manual Retry: If a transfer is FAILED, the user can tap the retry button. This resets the error, increments the
      retry counter, and re-uploads the original request JSON to Drive.
    * Manual Probe: The user can long-press or tap the probe button to force the app to send a probe request
      immediately.
  ---

  End of Life: Completion and Deletion

  A transfer's lifecycle ends when the user decides to clean it up. The repercussions depend on how the transfer was
  created:

  Deletion Scenarios:
    1. Successful Completion (COMPLETED)
        * Cleanup: If the transfer involved a temporary file uploaded solely for this request (like a pasted clipboard
          cover), the app automatically deletes that temporary file from put.io upon receiving the COMPLETED signal.
        * User Action: The user taps "Remove". They are asked if they want to also delete the source file from put.io
          (or detach it from Putz if it was a local file).
    2. Duplicate Failure (FAILED - "already has format")
        * User Action: The user taps "Remove". Because the file effectively exists in Calibre already, the app offers
          the same cleanup options (delete from put.io/detach) as a completed transfer.
    3. Standard Failure (FAILED)
        * User Action: The user taps "Remove". The transfer is simply deleted from the app's list. No remote files are
          touched, assuming the user might want to try again later or fix the source file.

  Once removed, the transfer entity is deleted from the local Room database, concluding its lifecycle.