package com.example.smartcook.data

data class Recipe(
    val id: Int,
    val name: String,
    val emoji: String,
    val duration: Int, // in minutes
    val isSelected: Boolean = false
)

enum class CookingStatus {
    IN_PROGRESS,
    NOT_STARTED,
    COMPLETED
}