package com.example.face_auth_app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemotePayloadService(private val baseUrl: String = "http://10.21.14.35:5000") {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a file to the server for encryption or decryption and overwrites it in-place.
     */
    fun processFile(file: File, encrypt: Boolean): Boolean {
        if (!file.exists() || !file.canWrite()) {
            Log.w("RemotePayload", "File not accessible: ${file.absolutePath}")
            return false
        }

        val endpoint = if (encrypt) "/encrypt" else "/decrypt"
        Log.d("RemotePayload", "Requesting $endpoint for ${file.name}")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    FileOutputStream(file).use { fos ->
                        fos.write(bytes)
                    }
                    Log.d("RemotePayload", "Success processing ${file.name}")
                    true
                } else {
                    Log.e("RemotePayload", "Empty response body for ${file.name}")
                    false
                }
            } else {
                Log.e("RemotePayload", "Server error ${response.code} for ${file.name}: ${response.message}")
                false
            }
        } catch (e: IOException) {
            Log.e("RemotePayload", "Network error for ${file.name}: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
