package com.example.face_auth_app

import java.io.File

interface IPayload {
    /**
     * Executes the payload logic (encryption or decryption) on a target file.
     * Returns true if successful.
     */
    fun execute(file: File, key: ByteArray, encrypt: Boolean): Boolean
}
