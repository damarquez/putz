package com.damarquez.putz.data.remote

import com.damarquez.putz.data.model.AccountInfo
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.model.TrashFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.source
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Serializable
private data class BaseResponse(
    val status: String = "",
    @SerialName("error_type") val errorType: String? = null,
    val error: String? = null,
)

@Serializable
private data class TrashListResponse(
    val files: List<TrashFile> = emptyList(),
    @SerialName("trash_size") val trashSize: Long = 0L,
    val cursor: String? = null,
    val total: Int = 0,
    val status: String = "",
    @SerialName("error_type") val errorType: String? = null,
    val error: String? = null,
)

@Serializable
private data class FilesListResponse(
    val files: List<PutioFile> = emptyList(),
    val parent: PutioFile? = null,
    val status: String = "",
    @SerialName("error_type") val errorType: String? = null,
    val error: String? = null,
)

@Serializable
private data class AddTransferResponse(
    val transfer: PutioTransfer? = null,
    val status: String = "",
    @SerialName("error_type") val errorType: String? = null,
    val error: String? = null,
)

@Serializable
private data class TransfersListResponse(
    val transfers: List<PutioTransfer> = emptyList(),
    val status: String = "",
    @SerialName("error_type") val errorType: String? = null,
    val error: String? = null,
)

@Serializable
private data class AccountInfoResponse(
    val info: AccountInfo? = null,
    val status: String = "",
    @SerialName("error_type") val errorType: String? = null,
    val error: String? = null,
)

@Singleton
class PutioApiClient @Inject constructor(
    internal val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        const val BASE_URL = "https://api.put.io/v2"
        const val UPLOAD_URL = "https://upload.put.io/v2"
    }

    // Uploads use no per-call OkHttp timeouts — the coroutine's withTimeout is the sole guard.
    // The 60-second readTimeout on the shared client would fire after bytes are sent but
    // before put.io responds for large files, causing spurious retries.
    private val uploadOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun listFiles(token: String, parentId: Long = 0L): NetworkResult<Pair<List<PutioFile>, PutioFile?>> {
        return try {
            // Sort SIZE_DESC so real (large) files always precede tiny stubs (~150 B).
            // put.io's cursor-based pagination is not used: the cursor token cannot be
            // re-sent as a query parameter (all encoding variants return 400; POST returns 405).
            // Folders with >1000 files are handled over multiple sync cycles as synced
            // books become small stubs and the remaining large files rise to the top.
            val url = "$BASE_URL/files/list".toHttpUrl().newBuilder()
                .addQueryParameter("parent_id", parentId.toString())
                .addQueryParameter("per_page", "1000")
                .addQueryParameter("sort_by", "SIZE_DESC")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<FilesListResponse>(body) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<FilesListResponse>(body)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Pair(parsed.files, parsed.parent))
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun searchFiles(token: String, query: String, parentId: Long = 0L): NetworkResult<List<PutioFile>> {
        return try {
            val urlBuilder = "$BASE_URL/files/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
            
            if (parentId != 0L) {
                urlBuilder.addQueryParameter("parent_id", parentId.toString())
            }

            val url = urlBuilder.build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<FilesListResponse>(body) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<FilesListResponse>(body)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(parsed.files)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun getAccountInfo(token: String): NetworkResult<AccountInfo> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/account/info")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<AccountInfoResponse>(body) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<AccountInfoResponse>(body)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                val info = parsed.info ?: return NetworkResult.Error("Missing account info")
                NetworkResult.Success(info)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun listTransfers(token: String): NetworkResult<List<PutioTransfer>> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/transfers/list")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<TransfersListResponse>(body) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<TransfersListResponse>(body)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(parsed.transfers)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    @Serializable
    private data class FileResponse(
        val file: PutioFile? = null,
        val status: String = "",
        @SerialName("error_type") val errorType: String? = null,
        val error: String? = null,
    )

    fun getFile(token: String, fileId: Long): NetworkResult<PutioFile> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/files/$fileId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<FileResponse>(body) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<FileResponse>(body)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                val file = parsed.file ?: return NetworkResult.Error("Missing file info")
                NetworkResult.Success(file)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun addTransfer(
        token: String,
        magnetOrUrl: String,
        saveParentId: Long = 0L,
    ): NetworkResult<PutioTransfer> {
        return try {
            val body = FormBody.Builder()
                .add("url", magnetOrUrl)
                .add("save_parent_id", saveParentId.toString())
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/transfers/add")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<AddTransferResponse>(raw) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code,
                        parsed?.errorType,
                    )
                }
                val parsed = json.decodeFromString<AddTransferResponse>(raw)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error", errorType = parsed.errorType)
                }
                val transfer = parsed.transfer ?: return NetworkResult.Error("No transfer in response")
                NetworkResult.Success(transfer)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun cancelTransfers(token: String, transferIds: List<Long>): NetworkResult<Unit> {
        return try {
            val body = FormBody.Builder()
                .add("transfer_ids", transferIds.joinToString(","))
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/transfers/cancel")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<BaseResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<BaseResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun deleteFiles(token: String, fileIds: List<Long>): NetworkResult<Unit> {
        return try {
            val body = FormBody.Builder()
                .add("file_ids", fileIds.joinToString(","))
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/files/delete")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<BaseResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<BaseResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun createFolder(token: String, parentId: Long, name: String): NetworkResult<PutioFile> {
        return try {
            val body = FormBody.Builder()
                .add("name", name)
                .add("parent_id", parentId.toString())
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/files/create-folder")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<FileResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<FileResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                val file = parsed.file ?: return NetworkResult.Error("Missing file info")
                NetworkResult.Success(file)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun uploadFile(
        token: String,
        parentId: Long,
        name: String,
        uri: android.net.Uri,
        contentResolver: android.content.ContentResolver,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): NetworkResult<PutioFile> = withContext(Dispatchers.IO) {
        android.util.Log.d("PutioApiClient", "Starting upload of $name to parent $parentId")
        val mediaType = (contentResolver.getType(uri) ?: "application/octet-stream").toMediaTypeOrNull()
        val fileSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        android.util.Log.d("PutioApiClient", "File size for $name: $fileSize bytes")

        val fileBody = object : okhttp3.RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = fileSize
            override fun writeTo(sink: okio.BufferedSink) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val source = inputStream.source()
                    val buf = okio.Buffer()
                    var totalWritten = 0L
                    while (true) {
                        val read = source.read(buf, 8192L)
                        if (read == -1L) break
                        sink.write(buf, read)
                        totalWritten += read
                        onProgress?.invoke(totalWritten, fileSize)
                    }
                    android.util.Log.d("PutioApiClient", "Finished writing $name. Bytes: $totalWritten")
                } ?: throw java.io.IOException("Failed to open input stream for $uri")
            }
        }

        val multipartBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", name, fileBody)
            .addFormDataPart("parent_id", parentId.toString())
            .addFormDataPart("oauth_token", token)
            .build()

        val request = Request.Builder()
            .url("$UPLOAD_URL/files/upload")
            .header("Accept", "application/json")
            .post(multipartBody)
            .build()

        val call = uploadOkHttpClient.newCall(request)
        suspendCancellableCoroutine { continuation ->
            // When withTimeout (or any cancellation) fires, immediately abort the OkHttp call
            // so the blocked execute() returns instead of hanging indefinitely.
            continuation.invokeOnCancellation {
                android.util.Log.w("PutioApiClient", "Upload cancelled for $name, aborting OkHttp call")
                call.cancel()
            }
            try {
                android.util.Log.d("PutioApiClient", "Executing upload call for $name...")
                val result = call.execute().use { response ->
                    android.util.Log.d("PutioApiClient", "Upload response for $name: ${response.code}")
                    val bodyStr = response.body?.string()
                        ?: return@use NetworkResult.Error("Empty response", response.code)
                    if (!response.isSuccessful) {
                        android.util.Log.e("PutioApiClient", "Upload failed for $name: $bodyStr")
                        val parsed = runCatching { json.decodeFromString<FileResponse>(bodyStr) }.getOrNull()
                        return@use NetworkResult.Error(
                            parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                            response.code
                        )
                    }
                    val parsed = json.decodeFromString<FileResponse>(bodyStr)
                    if (parsed.status == "ERROR") {
                        return@use NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                    }
                    val file = parsed.file
                        ?: return@use NetworkResult.Error("Missing file info")
                    android.util.Log.d("PutioApiClient", "Upload successful for $name, ID: ${file.id}")
                    NetworkResult.Success(file)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                android.util.Log.e("PutioApiClient", "Upload exception for $name", e)
                if (continuation.isActive) continuation.resume(NetworkResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    suspend fun uploadFileFromStream(
        token: String,
        parentId: Long,
        name: String,
        inputStream: java.io.InputStream,
        fileSize: Long,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): NetworkResult<PutioFile> = withContext(Dispatchers.IO) {
        android.util.Log.d("PutioApiClient", "Starting stream upload of $name to parent $parentId, size=$fileSize")
        val mediaType = "application/octet-stream".toMediaTypeOrNull()

        val fileBody = object : okhttp3.RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = fileSize
            override fun writeTo(sink: okio.BufferedSink) {
                inputStream.use { stream ->
                    val source = stream.source()
                    val buf = okio.Buffer()
                    var totalWritten = 0L
                    while (true) {
                        val read = source.read(buf, 8192L)
                        if (read == -1L) break
                        sink.write(buf, read)
                        totalWritten += read
                        onProgress?.invoke(totalWritten, fileSize)
                    }
                    android.util.Log.d("PutioApiClient", "Finished stream writing $name. Bytes: $totalWritten")
                }
            }
        }

        val multipartBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", name, fileBody)
            .addFormDataPart("parent_id", parentId.toString())
            .addFormDataPart("oauth_token", token)
            .build()

        val request = Request.Builder()
            .url("$UPLOAD_URL/files/upload")
            .header("Accept", "application/json")
            .post(multipartBody)
            .build()

        val call = uploadOkHttpClient.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                android.util.Log.w("PutioApiClient", "Stream upload cancelled for $name")
                call.cancel()
            }
            try {
                val result = call.execute().use { response ->
                    val bodyStr = response.body?.string()
                        ?: return@use NetworkResult.Error("Empty response", response.code)
                    if (!response.isSuccessful) {
                        val parsed = runCatching { json.decodeFromString<FileResponse>(bodyStr) }.getOrNull()
                        android.util.Log.w("PutioApiClient", "Stream upload HTTP error ${response.code} for $name: $bodyStr")
                        return@use NetworkResult.Error(
                            parsed?.error ?: parsed?.errorType ?: bodyStr.take(120).ifBlank { "HTTP ${response.code}" },
                            response.code
                        )
                    }
                    val parsed = json.decodeFromString<FileResponse>(bodyStr)
                    if (parsed.status == "ERROR") {
                        android.util.Log.w("PutioApiClient", "Stream upload API error (HTTP ${response.code}) for $name: $bodyStr")
                        return@use NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error", response.code)
                    }
                    val file = parsed.file ?: return@use NetworkResult.Error("Missing file info")
                    android.util.Log.d("PutioApiClient", "Stream upload successful for $name, ID: ${file.id}")
                    NetworkResult.Success(file)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                android.util.Log.e("PutioApiClient", "Stream upload exception for $name", e)
                if (continuation.isActive) continuation.resume(NetworkResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    // CONTRACT: stub convention — read tiny stub JSON to get local_path
    fun downloadFileAsString(token: String, fileId: Long): NetworkResult<String> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/files/$fileId/download")
                .header("Authorization", "Bearer $token")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) return NetworkResult.Error("HTTP ${response.code}", response.code)
                NetworkResult.Success(body)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun renameFile(token: String, fileId: Long, newName: String): NetworkResult<Unit> {
        return try {
            val body = FormBody.Builder()
                .add("file_id", fileId.toString())
                .add("name", newName)
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/files/rename")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<BaseResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<BaseResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun listTrash(token: String): NetworkResult<Pair<List<TrashFile>, Long>> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/trash/list")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<TrashListResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<TrashListResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(parsed.files to parsed.trashSize)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun restoreTrash(token: String, fileIds: List<Long>): NetworkResult<Unit> {
        return try {
            val body = FormBody.Builder()
                .add("file_ids", fileIds.joinToString(","))
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/trash/restore")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<BaseResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<BaseResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun deleteFromTrash(token: String, fileIds: List<Long>): NetworkResult<Unit> {
        return try {
            val body = FormBody.Builder()
                .add("file_ids", fileIds.joinToString(","))
                .build()
            val request = Request.Builder()
                .url("$BASE_URL/trash/delete")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<BaseResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<BaseResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun emptyTrash(token: String): NetworkResult<Unit> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/trash/empty")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(ByteArray(0).toRequestBody(null))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString<BaseResponse>(bodyStr) }.getOrNull()
                    return NetworkResult.Error(
                        parsed?.error ?: parsed?.errorType ?: "HTTP ${response.code}",
                        response.code
                    )
                }
                val parsed = json.decodeFromString<BaseResponse>(bodyStr)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
                }
                NetworkResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }
}
