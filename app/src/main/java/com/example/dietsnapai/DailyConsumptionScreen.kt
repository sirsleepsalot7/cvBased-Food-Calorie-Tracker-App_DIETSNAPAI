package com.example.dietsnapai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dietsnapai.db.LoggedMeal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DailyConsumptionScreen(
    meals: List<LoggedMeal>,
    onDeleteMeal: (LoggedMeal) -> Unit,
    onNavigateToScan: () -> Unit
) {
    // Calculate boundaries for "Today"
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startOfToday = calendar.timeInMillis
    val endOfToday = startOfToday + 24 * 60 * 60 * 1000L

    // Filter meals strictly for today
    val todayMeals = remember(meals) {
        meals.filter { it.timestamp in startOfToday until endOfToday }
    }

    val totalCaloriesToday = todayMeals.sumOf { it.calories }
    val totalProteinToday = todayMeals.sumOf { it.proteinG.toDouble() }.toFloat()
    val totalCarbsToday = todayMeals.sumOf { it.carbsG.toDouble() }.toFloat()
    val totalFatToday = todayMeals.sumOf { it.fatG.toDouble() }.toFloat()
    val dailyCalorieGoal = 2000

    val todayFormattedDate = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Header Section ---
        item {
            Column {
                Text(
                    text = "Today's Consumption",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = todayFormattedDate,
                    color = Color(0xFF90A4AE),
                    fontSize = 14.sp
                )
            }
        }

        // --- 1. Calorie Target Card ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Calories Consumed",
                                color = Color(0xFFB0BEC5),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$totalCaloriesToday",
                                    color = Color(0xFFFFB74D),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = " / $dailyCalorieGoal kcal",
                                    color = Color(0xFF78909C),
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(bottom = 3.dp, start = 4.dp)
                                )
                            }
                        }

                        val percent = ((totalCaloriesToday.toFloat() / dailyCalorieGoal) * 100).toInt()
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (percent > 100) Color(0xFFEF5350).copy(alpha = 0.2f) else Color(0xFF81C784).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "$percent%",
                                color = if (percent > 100) Color(0xFFEF5350) else Color(0xFF81C784),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val progress = (totalCaloriesToday.toFloat() / dailyCalorieGoal).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = if (totalCaloriesToday > dailyCalorieGoal) Color(0xFFEF5350) else Color(0xFF00E676),
                        trackColor = Color(0xFF2C2C2C),
                        strokeCap = StrokeCap.Round
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val remaining = dailyCalorieGoal - totalCaloriesToday
                    Text(
                        text = if (remaining >= 0) "$remaining kcal remaining for today" else "${-remaining} kcal over daily goal",
                        color = if (remaining >= 0) Color(0xFF90A4AE) else Color(0xFFEF5350),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // --- 2. Macronutrient Distribution Donut Chart (Today's Macros) ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Macronutrient Breakdown",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Donut Chart
                        MacroDonutChart(
                            proteinG = totalProteinToday,
                            carbsG = totalCarbsToday,
                            fatG = totalFatToday,
                            totalCalories = totalCaloriesToday,
                            modifier = Modifier.size(130.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Macro Legend
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MacroLegendItem(
                                label = "Protein",
                                grams = totalProteinToday,
                                color = Color(0xFF42A5F5),
                                calPercent = if (totalCaloriesToday > 0) ((totalProteinToday * 4f / totalCaloriesToday) * 100).toInt() else 0
                            )
                            MacroLegendItem(
                                label = "Carbs",
                                grams = totalCarbsToday,
                                color = Color(0xFFFFCA28),
                                calPercent = if (totalCaloriesToday > 0) ((totalCarbsToday * 4f / totalCaloriesToday) * 100).toInt() else 0
                            )
                            MacroLegendItem(
                                label = "Fats",
                                grams = totalFatToday,
                                color = Color(0xFFEF5350),
                                calPercent = if (totalCaloriesToday > 0) ((totalFatToday * 9f / totalCaloriesToday) * 100).toInt() else 0
                            )
                        }
                    }
                }
            }
        }

        // --- 3. Today's Meals Section Header ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Today's Logged Meals (${todayMeals.size})",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                if (todayMeals.isNotEmpty()) {
                    TextButton(onClick = onNavigateToScan) {
                        Text("+ Scan Food", color = Color(0xFF64B5F6), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // --- 4. Meals List or Empty State ---
        if (todayMeals.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "🥗", fontSize = 44.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No meals logged yet today",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scan or upload a picture in the Scan tab to calculate volume, macros, and calories automatically.",
                            color = Color(0xFF78909C),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = onNavigateToScan,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Scan Food Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(todayMeals, key = { it.id }) { meal ->
                MealItemCard(
                    meal = meal,
                    onDelete = { onDeleteMeal(meal) }
                )
            }
        }
    }
}

@Composable
fun MacroDonutChart(
    proteinG: Float,
    carbsG: Float,
    fatG: Float,
    totalCalories: Int,
    modifier: Modifier = Modifier
) {
    val proteinKcal = proteinG * 4f
    val carbsKcal = carbsG * 4f
    val fatKcal = fatG * 9f
    val totalKcal = proteinKcal + carbsKcal + fatKcal

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val strokeWidth = 22f

            if (totalKcal <= 0f) {
                // Empty state ring
                drawCircle(
                    color = Color(0xFF2A2A2A),
                    style = Stroke(width = strokeWidth)
                )
            } else {
                val proteinAngle = (proteinKcal / totalKcal) * 360f
                val carbsAngle = (carbsKcal / totalKcal) * 360f
                val fatAngle = (fatKcal / totalKcal) * 360f

                var startAngle = -90f

                // Protein Arc (Blue)
                drawArc(
                    color = Color(0xFF42A5F5),
                    startAngle = startAngle,
                    sweepAngle = proteinAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                startAngle += proteinAngle

                // Carbs Arc (Amber)
                drawArc(
                    color = Color(0xFFFFCA28),
                    startAngle = startAngle,
                    sweepAngle = carbsAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                startAngle += carbsAngle

                // Fat Arc (Coral)
                drawArc(
                    color = Color(0xFFEF5350),
                    startAngle = startAngle,
                    sweepAngle = fatAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Center Content
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$totalCalories",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "kcal",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun MacroLegendItem(label: String, grams: Float, color: Color, calPercent: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(50),
                color = color,
                modifier = Modifier.size(10.dp)
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${"%.1f".format(Locale.US, grams)}g",
                color = Color(0xFFCFD8DC),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "($calPercent%)",
                color = Color(0xFF78909C),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun MealItemCard(
    meal: LoggedMeal,
    onDelete: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.US) }
    val foodIcon = remember(meal.name) {
        val lower = meal.name.lowercase(Locale.US)
        when {
            "banana" in lower -> "🍌"
            "apple" in lower -> "🍎"
            "orange" in lower -> "🍊"
            "sandwich" in lower -> "🥪"
            "pizza" in lower -> "🍕"
            "salad" in lower -> "🥗"
            "bottle" in lower || "drink" in lower -> "🥤"
            else -> "🍽️"
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF2A2A2A),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = foodIcon, fontSize = 22.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = meal.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${timeFormatter.format(Date(meal.timestamp))} • ${"%.0f".format(Locale.US, meal.volumeCm3)} cm³",
                        color = Color(0xFF78909C),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "P: ${"%.1f".format(Locale.US, meal.proteinG)}g • C: ${"%.1f".format(Locale.US, meal.carbsG)}g • F: ${"%.1f".format(Locale.US, meal.fatG)}g",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${meal.calories} kcal",
                    color = Color(0xFFFFB74D),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(text = "🗑️", fontSize = 14.sp)
                }
            }
        }
    }
}
