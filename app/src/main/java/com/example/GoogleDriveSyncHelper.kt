package com.example

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.GoogleAuthUtil
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SyncPayload(
    val thoughts: List<SyncThought>,
    val dayConfigs: List<SyncDayConfig>,
    val lastSyncTime: Long
)

data class SyncThought(
    val title: String,
    val type: String,
    val isDeadline: Boolean,
    val dueDate: String?,
    val status: String,
    val placedDayIndex: Int?,
    val placedSlotId: String?,
    val createdAt: Long,
    val completedAt: Long,
    val customOrder: Int,
    val placedDate: String?
)

data class SyncDayConfig(
    val dayIndex: Int,
    val dayName: String,
    val dayType: String
)

fun Thought.toSyncThought(): SyncThought = SyncThought(
    title = title,
    type = type,
    isDeadline = isDeadline,
    dueDate = dueDate,
    status = status,
    placedDayIndex = placedDayIndex,
    placedSlotId = placedSlotId,
    createdAt = createdAt,
    completedAt = completedAt,
    customOrder = customOrder,
    placedDate = placedDate
)

fun SyncThought.toThought(localId: Int = 0): Thought = Thought(
    id = localId,
    title = title,
    type = type,
    isDeadline = isDeadline,
    dueDate = dueDate,
    status = status,
    placedDayIndex = placedDayIndex,
    placedSlotId = placedSlotId,
    createdAt = createdAt,
    completedAt = completedAt,
    customOrder = customOrder,
    placedDate = placedDate
)

fun DayConfig.toSyncDayConfig(): SyncDayConfig = SyncDayConfig(
    dayIndex = dayIndex,
    dayName = dayName,
    dayType = dayType
)

fun SyncDayConfig.toDayConfig(): DayConfig = DayConfig(
    dayIndex = dayIndex,
    dayName = dayName,
    dayType = dayType
)

object GoogleDriveSyncHelper {
    private const val TAG = "GoogleDriveSync"
    private val client = OkHttpClient()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val payloadAdapter = moshi.adapter(SyncPayload::class.java)

    // Drive AppData Scope
    private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    fun getGoogleSignInClient(context: Context): com.google.android.gms.auth.api.signin.GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun getAccessToken(context: Context, account: GoogleSignInAccount): String? = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.getToken(context, account.account ?: return@withContext null, "oauth2:$DRIVE_APPDATA_SCOPE")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token", e)
            null
        }
    }

    sealed class SyncResult {
        object Success : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    suspend fun sync(
        context: Context,
        accessToken: String,
        dao: LuodiDao
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val localThoughts = dao.getAllThoughtsOnce()
            val localConfigs = dao.getAllDayConfigsOnce()

            val fileId = findSyncFile(accessToken)

            if (fileId == null) {
                val newPayload = SyncPayload(
                    thoughts = localThoughts.map { it.toSyncThought() },
                    dayConfigs = localConfigs.map { it.toSyncDayConfig() },
                    lastSyncTime = System.currentTimeMillis()
                )
                val jsonString = payloadAdapter.toJson(newPayload)
                val createdId = createSyncFile(accessToken, jsonString)
                if (createdId != null) {
                    Log.d(TAG, "Successfully created sync file on Drive: $createdId")
                    SyncResult.Success
                } else {
                    SyncResult.Error("無法在雲端建立同步檔案")
                }
            } else {
                val remoteJson = downloadSyncFile(accessToken, fileId)
                if (remoteJson == null) {
                    return@withContext SyncResult.Error("無法下載雲端同步檔案")
                }

                val remotePayload = try {
                    payloadAdapter.fromJson(remoteJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse remote JSON", e)
                    null
                }

                if (remotePayload == null) {
                    return@withContext SyncResult.Error("雲端同步檔案格式錯誤")
                }

                val mergedThoughts = mutableListOf<Thought>()
                val remoteThoughtsMap = remotePayload.thoughts.associateBy { it.createdAt }
                val localThoughtsMap = localThoughts.associateBy { it.createdAt }

                val allCreatedAts = (remoteThoughtsMap.keys + localThoughtsMap.keys).toSet()

                for (createdAt in allCreatedAts) {
                    val localT = localThoughtsMap[createdAt]
                    val remoteT = remoteThoughtsMap[createdAt]

                    if (localT != null && remoteT != null) {
                        val isLocalCompleted = localT.status == "COMPLETED"
                        val isRemoteCompleted = remoteT.status == "COMPLETED"
                        
                        val chosenThought = if (isLocalCompleted && !isRemoteCompleted) {
                            localT
                        } else if (!isLocalCompleted && isRemoteCompleted) {
                            remoteT.toThought(localId = localT.id)
                        } else {
                            val isLocalPlaced = localT.status == "PLACED"
                            val isRemotePlaced = remoteT.status == "PLACED"
                            if (isLocalPlaced && !isRemotePlaced) {
                                localT
                            } else if (!isLocalPlaced && isRemotePlaced) {
                                remoteT.toThought(localId = localT.id)
                            } else {
                                localT
                            }
                        }
                        mergedThoughts.add(chosenThought)
                    } else if (localT != null) {
                        mergedThoughts.add(localT)
                    } else if (remoteT != null) {
                        mergedThoughts.add(remoteT.toThought())
                    }
                }

                val mergedConfigs = mutableListOf<DayConfig>()
                val remoteConfigsMap = remotePayload.dayConfigs.associateBy { it.dayIndex }
                val localConfigsMap = localConfigs.associateBy { it.dayIndex }

                for (i in 0..6) {
                    val localC = localConfigsMap[i]
                    val remoteC = remoteConfigsMap[i]
                    if (localC != null && remoteC != null) {
                        val defaultTypes = listOf("NON_SPORT", "SPORT", "NON_SPORT", "SPORT", "NON_SPORT", "SATURDAY", "SUNDAY")
                        val defaultType = defaultTypes[i]
                        val chosenConfig = if (remoteC.dayType != defaultType) {
                            remoteC.toDayConfig()
                        } else {
                            localC
                        }
                        mergedConfigs.add(chosenConfig)
                    } else if (localC != null) {
                        mergedConfigs.add(localC)
                    } else if (remoteC != null) {
                        mergedConfigs.add(remoteC.toDayConfig())
                    }
                }

                for (thought in mergedThoughts) {
                    if (thought.id == 0) {
                        dao.insertThought(thought)
                    } else {
                        dao.updateThought(thought)
                    }
                }

                dao.insertDayConfigs(mergedConfigs)

                val updatedThoughts = dao.getAllThoughtsOnce()
                val updatedConfigs = dao.getAllDayConfigsOnce()
                val mergedPayload = SyncPayload(
                    thoughts = updatedThoughts.map { it.toSyncThought() },
                    dayConfigs = updatedConfigs.map { it.toSyncDayConfig() },
                    lastSyncTime = System.currentTimeMillis()
                )
                val updatedJsonString = payloadAdapter.toJson(mergedPayload)
                val success = updateSyncFile(accessToken, fileId, updatedJsonString)
                if (success) {
                    Log.d(TAG, "Successfully updated sync file on Drive")
                    SyncResult.Success
                } else {
                    SyncResult.Error("無法更新雲端同步檔案")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            SyncResult.Error(e.message ?: "未知同步錯誤")
        }
    }

    private fun findSyncFile(accessToken: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name='luodi_sync_backup.json'&fields=files(id,name)"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Query file response failed: ${response.code} ${response.message}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val files = json.getJSONArray("files")
                if (files.length() > 0) {
                    return files.getJSONObject(0).getString("id")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding sync file", e)
        }
        return null
    }

    private fun downloadSyncFile(accessToken: String, fileId: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return response.body?.string()
                } else {
                    Log.e(TAG, "Download response failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading sync file", e)
        }
        return null
    }

    private fun createSyncFile(accessToken: String, jsonContent: String): String? {
        val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        
        val metadata = JSONObject()
            .put("name", "luodi_sync_backup.json")
            .put("parents", listOf("appDataFolder"))
            .toString()

        val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()
        
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(
                metadata.toRequestBody(mediaTypeJson)
            )
            .addPart(
                jsonContent.toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: return null
                    val json = JSONObject(respBody)
                    return json.getString("id")
                } else {
                    Log.e(TAG, "Create response failed: ${response.code} ${response.message} ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating sync file", e)
        }
        return null
    }

    private fun updateSyncFile(accessToken: String, fileId: String, jsonContent: String): Boolean {
        val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
        val body = jsonContent.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .patch(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return true
                } else {
                    Log.e(TAG, "Update response failed: ${response.code} ${response.message} ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating sync file", e)
        }
        return false
    }
}
