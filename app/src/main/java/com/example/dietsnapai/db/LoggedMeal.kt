package com.example.dietsnapai.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "logged_meals")
data class LoggedMeal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val volumeCm3: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MealDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeal(meal: LoggedMeal)

    @Query("SELECT * FROM logged_meals ORDER BY timestamp DESC")
    fun getAllMeals(): Flow<List<LoggedMeal>>

    @Query("SELECT SUM(calories) FROM logged_meals WHERE timestamp >= :startOfDayTimestamp")
    suspend fun getDailyCalories(startOfDayTimestamp: Long): Int?
}