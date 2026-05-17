package com.damarquez.putz.data.remote

import com.damarquez.putz.data.model.AccountInfo
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.model.PutioTransfer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.source
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

    fun listFiles(token: String, parentId: Long = 0L): NetworkResult<Pair<List<PutioFile>, PutioFile?>> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/files/list?parent_id=$parentId&per_page=1000")
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
                        response.code
                    )
                }
                val parsed = json.decodeFromString<AddTransferResponse>(raw)
                if (parsed.status == "ERROR") {
                    return NetworkResult.Error(parsed.error ?: parsed.errorType ?: "API error")
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

        val call = okHttpClient.newCall(request)
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
}
