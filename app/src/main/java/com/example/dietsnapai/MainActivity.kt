package com.example.dietsnapai

import com.example.dietsnapai.db.DatabaseInitializer
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.dietsnapai.db.AppDatabase
import com.example.dietsnapai.db.FoodNutritionRepository
import com.example.dietsnapai.db.LoggedMeal
import com.example.dietsnapai.db.MacroResult
import com.example.dietsnapai.ml.Calibrator
import com.example.dietsnapai.ml.ImageUtils
import com.example.dietsnapai.ml.ModelEngine
import com.example.dietsnapai.ml.VolumeEstimator
import com.example.dietsnapai.ml.YoloPostProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

data class FoodDetectionItem(
    val foodName: String,
    val className: String,
    val volumeCm3: Float,
    val estimatedCalories: Int,
    val confidence: Float,
    val macros: MacroResult,
    val boundingBox: RectF,
    val isOccluded: Boolean = false,
    val occlusionRatio: Float = 0f
)

data class NutritionResult(
    val primaryFood: FoodDetectionItem,
    val allDetectedFoods: List<FoodDetectionItem>,
    val totalCalories: Int,
    val totalVolumeCm3: Float,
    val totalMacros: MacroResult,
    val isCalibrated: Boolean = true
)

class MainActivity : ComponentActivity() {

    private var modelEngine: ModelEngine? = null
    private lateinit var database: AppDatabase
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val resultState = mutableStateOf<NutritionResult?>(null)
    private val isProcessingState = mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Log.e("DietSnapAI", "Camera permission denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = AppDatabase.getDatabase(this)
        DatabaseInitializer.populateInitialData(database)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor.execute {
            try {
                val engine = ModelEngine(applicationContext)
                kotlinx.coroutines.runBlocking {
                    engine.initSessions()
                }
                modelEngine = engine
                Log.d("DietSnapAI", "Models loaded successfully.")
            } catch (e: Exception) {
                Log.e("DietSnapAI", "Model init failure", e)
            }
        }

        setContent {
            MaterialTheme {
                CameraScreen(
                    database = database,
                    result = resultState.value,
                    isProcessing = isProcessingState.value,
                    onAnalyzeClicked = { bitmap ->
                        processFrame(bitmap)
                    },
                    onLogMealClicked = { data ->
                        logMealToDatabase(data)
                    },
                    onDismissCardClicked = {
                        resultState.value = null
                    }
                )
            }
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        val engine = modelEngine
        if (engine == null) {
            Toast.makeText(this, "AI models initializing, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessingState.value = true

        cameraExecutor.execute {
            try {
                // 1. Run YOLOv8 detection
                val yoloBuffer = ImageUtils.bitmapToYoloFloatBuffer(bitmap)
                val rawDetections = engine.detectFood(yoloBuffer)
                val detections = if (rawDetections != null) {
                    YoloPostProcessor.processOutput(rawDetections, confThreshold = 0.20f)
                } else emptyList()

                // 2. Isolate verified food candidates
                val foodCandidates = YoloPostProcessor.findFoodObjects(detections)

                Log.d("DietSnapAI", "Food candidates detected: ${foodCandidates.map { "${it.className} (${(it.confidence * 100).toInt()}%)" }}")

                if (foodCandidates.isEmpty()) {
                    runOnUiThread {
                        isProcessingState.value = false
                        resultState.value = null
                        Toast.makeText(
                            this@MainActivity,
                            "No food detected — center plate and try again",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@execute
                }

                // 3. Compute optical camera metric scale (mm/px)
                val distanceMeters = 0.33f // Calibrated operating distance (~33 cm)
                val intrinsics = Calibrator.getCameraIntrinsics(this@MainActivity)
                val mmPerPixel = Calibrator.calculateMmPerPixel(
                    distanceMeters = distanceMeters,
                    focalLengthMm = intrinsics.focalLengthMm,
                    sensorWidthMm = intrinsics.sensorWidthMm,
                    imageWidthPx = 640
                )

                // 4. Run Depth estimation
                val depthBuffer = ImageUtils.bitmapToDepthFloatBuffer(bitmap)
                val depthMap = engine.estimateDepth(depthBuffer)

                // 5. Process all detected food items simultaneously with full multi-fruit & occlusion awareness
                val safeDepth = depthMap ?: Array(0) { FloatArray(0) }
                val sceneResults = VolumeEstimator.processScene(safeDepth, foodCandidates, intrinsics)

                val detectedFoodItems = sceneResults.map { sceneItem ->
                    val candidate = sceneItem.candidate
                    val volumeResult = sceneItem.volumeResult
                    val profile = FoodNutritionRepository.getNutritionalProfile(candidate.className)
                    val macros = FoodNutritionRepository.calculateMacroBreakdown(profile, volumeResult.volumeCm3)

                    FoodDetectionItem(
                        foodName = profile.name,
                        className = candidate.className,
                        volumeCm3 = volumeResult.volumeCm3,
                        estimatedCalories = macros.calories,
                        confidence = candidate.confidence,
                        macros = macros,
                        boundingBox = candidate.box,
                        isOccluded = volumeResult.isOccluded,
                        occlusionRatio = volumeResult.occlusionRatio
                    )
                }

                val primary = detectedFoodItems.first()
                val totalCalories = detectedFoodItems.sumOf { it.estimatedCalories }
                val totalVolumeCm3 = detectedFoodItems.sumOf { it.volumeCm3.toDouble() }.toFloat()
                val totalProtein = detectedFoodItems.sumOf { it.macros.proteinG.toDouble() }.toFloat()
                val totalCarbs = detectedFoodItems.sumOf { it.macros.carbsG.toDouble() }.toFloat()
                val totalFat = detectedFoodItems.sumOf { it.macros.fatG.toDouble() }.toFloat()
                val totalWeight = detectedFoodItems.sumOf { it.macros.weightGrams.toDouble() }.toFloat()

                val aggregatedMacros = MacroResult(
                    weightGrams = totalWeight,
                    calories = totalCalories,
                    proteinG = totalProtein,
                    carbsG = totalCarbs,
                    fatG = totalFat
                )

                runOnUiThread {
                    resultState.value = NutritionResult(
                        primaryFood = primary,
                        allDetectedFoods = detectedFoodItems,
                        totalCalories = totalCalories,
                        totalVolumeCm3 = totalVolumeCm3,
                        totalMacros = aggregatedMacros,
                        isCalibrated = true
                    )
                    isProcessingState.value = false
                }
            } catch (e: Exception) {
                Log.e("DietSnapAI", "Inference error", e)
                runOnUiThread {
                    isProcessingState.value = false
                    Toast.makeText(this@MainActivity, "Inference error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun logMealToDatabase(data: NutritionResult) {
        CoroutineScope(Dispatchers.IO).launch {
            data.allDetectedFoods.forEach { item ->
                val meal = LoggedMeal(
                    name = item.foodName,
                    calories = item.estimatedCalories,
                    proteinG = item.macros.proteinG,
                    carbsG = item.macros.carbsG,
                    fatG = item.macros.fatG,
                    volumeCm3 = item.volumeCm3
                )
                database.mealDao().insertMeal(meal)
            }
            withContext(Dispatchers.Main) {
                val itemNames = data.allDetectedFoods.joinToString(", ") { it.foodName }
                Toast.makeText(
                    this@MainActivity,
                    "Logged $itemNames (${data.totalCalories} kcal)!",
                    Toast.LENGTH_SHORT
                ).show()
                resultState.value = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        modelEngine?.close()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraScreen(
    database: AppDatabase,
    result: NutritionResult?,
    isProcessing: Boolean,
    onAnalyzeClicked: (Bitmap) -> Unit,
    onLogMealClicked: (NutritionResult) -> Unit,
    onDismissCardClicked: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // State for uploaded gallery image
    var uploadedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Launcher for selecting an image from the gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val bitmap = ImageUtils.loadSquareBitmapFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        uploadedImageBitmap = bitmap
                        onAnalyzeClicked(bitmap)
                    } else {
                        Toast.makeText(context, "Could not load selected image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Observe meals list from Room database
    val mealsState by database.mealDao().getAllMeals().collectAsState(initial = emptyList())
    var showHistorySheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Viewfinder
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    controller.bindToLifecycle(context as ComponentActivity)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 1b. Display Uploaded Image when selected from gallery
        if (uploadedImageBitmap != null) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val squareSide = minOf(maxWidth, maxHeight)
                Image(
                    bitmap = uploadedImageBitmap!!.asImageBitmap(),
                    contentDescription = "Uploaded Food Image",
                    modifier = Modifier.size(squareSide),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Return to Live Camera Button (Top-Left) when viewing an uploaded photo
        if (uploadedImageBitmap != null) {
            Button(
                onClick = {
                    uploadedImageBitmap = null
                    onDismissCardClicked()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = "📷 Live Camera", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 2. History Toggle Button (Top-Right)
        IconButton(
            onClick = { showHistorySheet = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF1E1E1E).copy(alpha = 0.85f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "📋", fontSize = 20.sp)
                }
            }
        }

        // 3. Bounding Box Canvas Overlay for all detected food items with uniform aspect ratio scaling
        val itemColorPalette = remember {
            listOf(
                Color(0xFFFFD600), // Yellow (Banana)
                Color(0xFFFF5252), // Red (Apple)
                Color(0xFFFF9100), // Orange
                Color(0xFF00E676), // Green
                Color(0xFF00B0FF), // Blue
                Color(0xFFE040FB)  // Purple
            )
        }

        result?.allDetectedFoods?.let { foods ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val squareSide = minOf(size.width, size.height)
                val offsetX = (size.width - squareSide) / 2f
                val offsetY = (size.height - squareSide) / 2f
                val scale = squareSide / 640f

                foods.forEachIndexed { index, food ->
                    val color = itemColorPalette[index % itemColorPalette.size]
                    val box = food.boundingBox
                    val left = box.left * scale + offsetX
                    val top = box.top * scale + offsetY
                    val width = (box.right - box.left) * scale
                    val height = (box.bottom - box.top) * scale

                    drawRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 6f)
                    )
                }
            }
        }

        // 4. Nutrition & Macro Breakdown Card
        result?.let { data ->
            val foodTitle = if (data.allDetectedFoods.size == 1) {
                val occ = if (data.primaryFood.isOccluded) " (Partially Occluded)" else ""
                "${data.primaryFood.foodName}$occ"
            } else {
                "${data.allDetectedFoods.size} Food Items Detected"
            }

            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 104.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.94f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header Row with Title, Calibration Tag, and Dismiss ('✕') Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = foodTitle,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Text(
                            text = if (data.isCalibrated) "Optical Calibrated" else "${(data.primaryFood.confidence * 100).toInt()}% match",
                            color = if (data.isCalibrated) Color(0xFF64B5F6) else Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Cross / Close ('✕') Button to dismiss the card UI
                        IconButton(
                            onClick = { onDismissCardClicked() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "✕",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Prominent Weight & Volume Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Weight: ${data.totalMacros.weightGrams.toInt()}g",
                                color = Color(0xFF81C784),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Volume: ${"%.1f".format(Locale.US, data.totalVolumeCm3)} cm³",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = "${data.totalCalories} kcal",
                            color = Color(0xFFFFB74D),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Multi-item Breakdown List if multiple items detected (e.g. Orange + Banana)
                    if (data.allDetectedFoods.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))

                        data.allDetectedFoods.forEachIndexed { index, item ->
                            val color = itemColorPalette[index % itemColorPalette.size]
                            val occludedBadge = if (item.isOccluded) " (occluded)" else ""
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = color,
                                        modifier = Modifier.size(8.dp)
                                    ) {}
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${item.foodName}$occludedBadge",
                                        color = Color.LightGray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "${item.macros.weightGrams.toInt()}g (${"%.0f".format(Locale.US, item.volumeCm3)} cm³) — ${item.estimatedCalories} kcal",
                                    color = Color(0xFFFFCC80),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Macro Breakdown Indicators (Weight, Protein, Carbs, Fats)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        MacroBadge(
                            label = "Weight",
                            value = "${data.totalMacros.weightGrams.toInt()}g",
                            color = Color(0xFF81C784)
                        )
                        MacroBadge(
                            label = "Protein",
                            value = "${"%.1f".format(Locale.US, data.totalMacros.proteinG)}g",
                            color = Color(0xFF42A5F5)
                        )
                        MacroBadge(
                            label = "Carbs",
                            value = "${"%.1f".format(Locale.US, data.totalMacros.carbsG)}g",
                            color = Color(0xFFFFCA28)
                        )
                        MacroBadge(
                            label = "Fats",
                            value = "${"%.1f".format(Locale.US, data.totalMacros.fatG)}g",
                            color = Color(0xFFEF5350)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { onLogMealClicked(data) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Log Meal to Daily Diary",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 5. Controls (Capture / Gallery / Status)
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isProcessing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF64B5F6),
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Estimating Volume & Macros...",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (uploadedImageBitmap != null) {
                    // Controls when viewing an uploaded photo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                uploadedImageBitmap = null
                                onDismissCardClicked()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                containerColor = Color.White.copy(alpha = 0.08f)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF78909C))
                        ) {
                            Text("📷 Live Camera", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                galleryLauncher.launch("image/*")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("🖼️ New Photo", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Controls when in live camera mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                controller.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val rotationDegrees = image.imageInfo.rotationDegrees
                                            val rawBitmap = image.toBitmap()
                                            val rotatedBitmap = if (rotationDegrees != 0) {
                                                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                            } else {
                                                rawBitmap
                                            }

                                            // Crucial: Square center-crop before AI inference to preserve 1:1 metric scale!
                                            val minDim = minOf(rotatedBitmap.width, rotatedBitmap.height)
                                            val cropX = (rotatedBitmap.width - minDim) / 2
                                            val cropY = (rotatedBitmap.height - minDim) / 2
                                            val squareBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, minDim, minDim)

                                            uploadedImageBitmap = null
                                            onAnalyzeClicked(squareBitmap)
                                            image.close()
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("DietSnapAI", "Capture failed", exception)
                                        }
                                    }
                                )
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("📸 Capture", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                galleryLauncher.launch("image/*")
                            },
                            modifier = Modifier
                                .weight(1.1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                containerColor = Color.White.copy(alpha = 0.08f)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF64B5F6))
                        ) {
                            Text("🖼️ Gallery", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 6. History Bottom Sheet
        if (showHistorySheet) {
            HistoryBottomSheet(
                meals = mealsState,
                onDismissRequest = { showHistorySheet = false }
            )
        }
    }
}

@Composable
fun MacroBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
        Text(text = value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
