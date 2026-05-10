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
