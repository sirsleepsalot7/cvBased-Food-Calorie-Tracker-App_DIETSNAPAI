package com.example.dietsnapai.db

import java.util.Locale

object FoodNutritionRepository {
    private val nutritionTable = mapOf(
        "banana" to FoodItem("Banana", 0.95f, 62f, 1.1f, 15.5f, 0.2f),
        "apple" to FoodItem("Apple", 0.85f, 52f, 0.3f, 13.8f, 0.2f),
        "orange" to FoodItem("Orange", 0.95f, 40f, 0.9f, 9.5f, 0.1f),
        "sandwich" to FoodItem("Sandwich", 0.55f, 250f, 10.0f, 30.0f, 9.0f),
        "pizza" to FoodItem("Pizza", 0.60f, 266f, 11.0f, 33.0f, 10.0f),
        "hot dog" to FoodItem("Hot Dog", 0.70f, 290f, 10.5f, 25.0f, 16.0f),
        "donut" to FoodItem("Donut", 0.40f, 452f, 4.9f, 51.0f, 25.0f),
        "cake" to FoodItem("Cake / Pastry", 0.45f, 371f, 4.0f, 53.0f, 15.0f),
        "bowl" to FoodItem("Rice / Curry Bowl", 0.85f, 130f, 2.7f, 28.0f, 0.3f),
        "cup" to FoodItem("Coffee / Beverage", 1.00f, 40f, 1.2f, 4.5f, 1.8f),
        "bottle" to FoodItem("Beverage Bottle", 1.00f, 0f, 0.0f, 0.0f, 0.0f),
        "broccoli" to FoodItem("Broccoli", 0.37f, 34f, 2.8f, 7.0f, 0.4f),
        "carrot" to FoodItem("Carrot", 0.64f, 41f, 0.9f, 9.6f, 0.2f),
        "default" to FoodItem("Mixed Food Meal", 0.75f, 150f, 5.0f, 20.0f, 5.0f)
    )

    fun getNutritionalProfile(detectedKey: String): FoodItem {
        val normalized = detectedKey.trim().lowercase(Locale.US)
        return nutritionTable[normalized] ?: FoodItem(
            name = detectedKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() },
            densityGPerCm3 = 0.75f,
            caloriesPer100g = 150f,
            proteinPer100g = 5.0f,
            carbsPer100g = 20.0f,
            fatPer100g = 5.0f
        )
    }

    fun calculateMacroBreakdown(profile: FoodItem, volumeCm3: Float): MacroResult {
        val safeVolume = volumeCm3.coerceAtLeast(10f)
        val totalWeightGrams = safeVolume * profile.densityGPerCm3
        val multiplier = totalWeightGrams / 100f

        return MacroResult(
            weightGrams = totalWeightGrams,
            calories = (profile.caloriesPer100g * multiplier).toInt(),
            proteinG = profile.proteinPer100g * multiplier,
            carbsG = profile.carbsPer100g * multiplier,
            fatG = profile.fatPer100g * multiplier
        )
    }
}

data class MacroResult(
    val weightGrams: Float,
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float
)
