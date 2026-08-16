package com.example.smartcook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DrawerContent(
    onCloseDrawer: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    ModalDrawerSheet(
        modifier = Modifier
            .width(300.dp)
            .windowInsetsPadding(WindowInsets(0.dp)), // Remove default insets
        drawerContainerColor = MaterialTheme.colorScheme.primary
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header 
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .windowInsetsPadding(WindowInsets.statusBars) // Handle status bar
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Restaurant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "SmartCook",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // White background layer for menu items
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Menu Items
                DrawerMenuItem(
                    icon = Icons.Default.Home,
                    title = "Home",
                    onClick = onNavigateToHome
                )

                DrawerMenuItem(
                    icon = Icons.Default.Favorite,
                    title = "Favorites",
                    onClick = onNavigateToFavorites
                )

                DrawerMenuItem(
                    icon = Icons.Default.Category,
                    title = "Categories",
                    onClick = { onCloseDrawer() }
                )

                DrawerMenuItem(
                    icon = Icons.Default.ShoppingCart,
                    title = "Shopping List",
                    onClick = { onCloseDrawer() }
                )

                DrawerMenuItem(
                    icon = Icons.Default.CalendarToday,
                    title = "Meal Planner",
                    onClick = { onCloseDrawer() }
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                        )

                DrawerMenuItem(
                    icon = Icons.Default.Settings,
                    title = "Settings",
                    onClick = onNavigateToSettings
                )

                DrawerMenuItem(
                    icon = Icons.Default.Info,
                    title = "About",
                    onClick = { onCloseDrawer() }
                )
            }
        }
    }
}

@Composable
fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(32.dp))
        Text(
            text = title,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}