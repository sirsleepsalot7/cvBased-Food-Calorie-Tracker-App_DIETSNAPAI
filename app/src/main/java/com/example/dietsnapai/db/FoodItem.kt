package com.example.dietsnapai.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "food_items")
data class FoodItem(
    @PrimaryKey val name: String,
    val densityGPerCm3: Float,      // Physical density (g/cm³)
    val caloriesPer100g: Float,     // Energy density (kcal / 100g)
    val proteinPer100g: Float,      // Protein (g / 100g)
    val carbsPer100g: Float,        // Carbs (g / 100g)
    val fatPer100g: Float           // Fats (g / 100g)
)