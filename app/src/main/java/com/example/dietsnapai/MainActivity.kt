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
                DietSnapMainScreen(
                    database = database,
                    result = resultState.value,
                    isProcessing = isProcessingState.value,
                    onAnalyzeClicked = { bitmap ->
                        processFrame(bitmap)
                    },
                    onLogMealClicked = { data ->
                        logMealToDatabase(data)
                    },
                    onDismissResultClicked = {
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

enum class AppTab {
    SCANNER,
    CONSUMPTION
}

@Composable
fun DietSnapMainScreen(
    database: AppDatabase,
    result: NutritionResult?,
    isProcessing: Boolean,
    onAnalyzeClicked: (Bitmap) -> Unit,
    onLogMealClicked: (NutritionResult) -> Unit,
    onDismissResultClicked: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(AppTab.SCANNER) }
    val mealsState by database.mealDao().getAllMeals().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1E1E1E),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.SCANNER,
                    onClick = { selectedTab = AppTab.SCANNER },
                    icon = { Text("📸", fontSize = 20.sp) },
                    label = {
                        Text(
                            "Scan Food",
                            fontWeight = if (selectedTab == AppTab.SCANNER) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color(0xFF64B5F6),
                        indicatorColor = Color(0xFF263238),
                        unselectedTextColor = Color(0xFF78909C)
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.CONSUMPTION,
                    onClick = { selectedTab = AppTab.CONSUMPTION },
                    icon = { Text("📊", fontSize = 20.sp) },
                    label = {
                        Text(
                            "Daily Diary",
                            fontWeight = if (selectedTab == AppTab.CONSUMPTION) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color(0xFF64B5F6),
                        indicatorColor = Color(0xFF263238),
                        unselectedTextColor = Color(0xFF78909C)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                AppTab.SCANNER -> {
                    FoodScannerScreen(
                        result = result,
                        isProcessing = isProcessing,
                        onAnalyzeClicked = onAnalyzeClicked,
                        onLogMealClicked = onLogMealClicked,
                        onDismissResultClicked = onDismissResultClicked,
                        onNavigateToDiary = { selectedTab = AppTab.CONSUMPTION }
                    )
                }
                AppTab.CONSUMPTION -> {
                    DailyConsumptionScreen(
                        meals = mealsState,
                        onDeleteMeal = { meal ->
                            coroutineScope.launch(Dispatchers.IO) {
                                database.mealDao().deleteMeal(meal)
                            }
                        },
                        onNavigateToScan = { selectedTab = AppTab.SCANNER }
                    )
                }
            }
        }
    }
}

@Composable
fun FoodScannerScreen(
    result: NutritionResult?,
    isProcessing: Boolean,
    onAnalyzeClicked: (Bitmap) -> Unit,
    onLogMealClicked: (NutritionResult) -> Unit,
    onDismissResultClicked: () -> Unit,
    onNavigateToDiary: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // Holds the frozen photo — whether captured live or picked from gallery!
    var activeImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isMealLogged by remember { mutableStateOf(false) }

    // Launcher for selecting an image from the gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val bitmap = ImageUtils.loadSquareBitmapFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        activeImageBitmap = bitmap
                        isMealLogged = false
                        onAnalyzeClicked(bitmap)
                    } else {
                        Toast.makeText(context, "Could not load selected image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. If no photo is active, show the Live Camera Viewfinder
        if (activeImageBitmap == null) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        this.controller = controller
                        controller.bindToLifecycle(context as ComponentActivity)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Live Camera Framing Guide (Center Reticle)
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val squareSide = minOf(maxWidth, maxHeight) * 0.82f
                Surface(
                    modifier = Modifier.size(squareSide),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Text(
                                text = "Align plate inside frame (~30cm away)",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // 2. Picture uploaded or taken live IS SHOWN HERE!
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val squareSide = minOf(maxWidth, maxHeight)
                Image(
                    bitmap = activeImageBitmap!!.asImageBitmap(),
                    contentDescription = "Captured Food Image",
                    modifier = Modifier.size(squareSide),
                    contentScale = ContentScale.Fit
                )
            }

            // Top-Left "Retake / New Scan" button
            Button(
                onClick = {
                    activeImageBitmap = null
                    isMealLogged = false
                    onDismissResultClicked()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E1E1E).copy(alpha = 0.88f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = "New Scan", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 3. Bounding Box Canvas Overlay (Drawn right on top of the image when detected)
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

        if (activeImageBitmap != null && result != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val squareSide = minOf(size.width, size.height)
                val offsetX = (size.width - squareSide) / 2f
                val offsetY = (size.height - squareSide) / 2f
                val scale = squareSide / 640f

                result.allDetectedFoods.forEachIndexed { index, food ->
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

        // 4. Processing Progress Overlay (Shows over the captured image while analyzing)
        if (isProcessing) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, Color(0xFF42A5F5).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF64B5F6),
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Analyzing Food & Metric Depth...",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Estimating volume, grams & calories",
                            color = Color(0xFF90A4AE),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 5. Analysis & Nutrition Card (Appears after analysis on the photo)
        if (activeImageBitmap != null && result != null && !isProcessing) {
            val data = result
            val foodTitle = if (data.allDetectedFoods.size == 1) {
                val occ = if (data.primaryFood.isOccluded) " (Partially Occluded)" else ""
                "${data.primaryFood.foodName}$occ"
            } else {
                "${data.allDetectedFoods.size} Food Items Detected"
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header Row with Title, Badge, and Close Button
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

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF64B5F6).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Optical Calibrated",
                                color = Color(0xFF64B5F6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                activeImageBitmap = null
                                isMealLogged = false
                                onDismissResultClicked()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text(text = "✕", color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Prominent Calories & Weight
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
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Multi-food items breakdown
                    if (data.allDetectedFoods.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(6.dp))

                        data.allDetectedFoods.forEachIndexed { index, item ->
                            val color = itemColorPalette[index % itemColorPalette.size]
                            val occTag = if (item.isOccluded) " (occluded)" else ""
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.size(8.dp)) {}
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${item.foodName}$occTag",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "${item.macros.weightGrams.toInt()}g • ${item.estimatedCalories} kcal",
                                    color = Color(0xFFFFCC80),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Macros Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        MacroBadge(label = "Weight", value = "${data.totalMacros.weightGrams.toInt()}g", color = Color(0xFF81C784))
                        MacroBadge(label = "Protein", value = "${"%.1f".format(Locale.US, data.totalMacros.proteinG)}g", color = Color(0xFF42A5F5))
                        MacroBadge(label = "Carbs", value = "${"%.1f".format(Locale.US, data.totalMacros.carbsG)}g", color = Color(0xFFFFCA28))
                        MacroBadge(label = "Fats", value = "${"%.1f".format(Locale.US, data.totalMacros.fatG)}g", color = Color(0xFFEF5350))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons (Log Meal or Scan Another)
                    if (isMealLogged) {
                        Surface(
                            color = Color(0xFF1B5E20).copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF00E676)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "✓ Meal Logged Successfully!",
                                    color = Color(0xFF00E676),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            activeImageBitmap = null
                                            isMealLogged = false
                                            onDismissResultClicked()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                    ) {
                                        Text("Scan Another", fontSize = 13.sp)
                                    }
                                    Button(
                                        onClick = onNavigateToDiary,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                    ) {
                                        Text("View Daily Diary", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    activeImageBitmap = null
                                    isMealLogged = false
                                    onDismissResultClicked()
                                },
                                modifier = Modifier.weight(0.9f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color(0xFF78909C))
                            ) {
                                Text("Retake", fontSize = 14.sp)
                            }

                            Button(
                                onClick = {
                                    onLogMealClicked(data)
                                    isMealLogged = true
                                },
                                modifier = Modifier.weight(1.3f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            ) {
                                Text("Log Meal", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 6. Live Mode Bottom Controls (Capture or Upload)
        if (activeImageBitmap == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (!isProcessing) {
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

                                            val minDim = minOf(rotatedBitmap.width, rotatedBitmap.height)
                                            val cropX = (rotatedBitmap.width - minDim) / 2
                                            val cropY = (rotatedBitmap.height - minDim) / 2
                                            val squareBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, minDim, minDim)

                                            // SHOW the captured picture immediately!
                                            activeImageBitmap = squareBitmap
                                            isMealLogged = false
                                            onAnalyzeClicked(squareBitmap)
                                            image.close()
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("DietSnapAI", "Capture failed", exception)
                                        }
                                    }
                                )
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Capture & Analyze", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch("image/*")
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            containerColor = Color.White.copy(alpha = 0.08f)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF64B5F6))
                    ) {
                        Text("Upload from Gallery", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
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
