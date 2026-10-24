package com.example.dietsnapai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dietsnapai.db.LoggedMeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryBottomSheet(
    meals: List<LoggedMeal>,
    onDismissRequest: () -> Unit
) {
    val totalCalories = meals.sumOf { it.calories }
    val totalProtein = meals.map { it.proteinG }.sum()
    val totalCarbs = meals.map { it.carbsG }.sum()
    val totalFat = meals.map { it.fatG }.sum()

    val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Today's Intake",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Daily Summary Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Total Calories", color = Color.LightGray, fontSize = 14.sp)
                        Text(
                            text = "$totalCalories kcal",
                            color = Color(0xFFFFB74D),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        MacroBadge("Protein", "${"%.1f".format(Locale.US, totalProtein)}g", Color(0xFF42A5F5))
                        MacroBadge("Carbs", "${"%.1f".format(Locale.US, totalCarbs)}g", Color(0xFFFFCA28))
                        MacroBadge("Fats", "${"%.1f".format(Locale.US, totalFat)}g", Color(0xFFEF5350))
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Logged Items (${meals.size})",
                color = Color.LightGray,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (meals.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No meals recorded yet today.", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(meals) { meal ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF252525)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(meal.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text(
                                        "${timeFormatter.format(Date(meal.timestamp))} • ${"%.0f".format(Locale.US, meal.volumeCm3)} cm³",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    "${meal.calories} kcal",
                                    color = Color(0xFFFFB74D),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}