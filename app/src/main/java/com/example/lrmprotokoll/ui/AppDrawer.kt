package com.example.lrmprotokoll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.R

data class DrawerMenuItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
)

@Composable
fun AppDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit
) {
    val items = listOf(
        DrawerMenuItem("main", stringResource(R.string.nav_start), Icons.Default.Home),
        DrawerMenuItem("meter", stringResource(R.string.nav_meter), AppIcons.Sensors),
        DrawerMenuItem("protokoll", stringResource(R.string.nav_protocol), AppIcons.BarChart),
        DrawerMenuItem("diagnose", stringResource(R.string.nav_diagnose), AppIcons.Diagnose),
        DrawerMenuItem("settings", stringResource(R.string.nav_settings), Icons.Default.Settings),
        DrawerMenuItem("trash", stringResource(R.string.nav_trash), AppIcons.Trash),
    )

    ModalDrawerSheet(
        modifier = Modifier.width(280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "PCE-323 BLE & Audio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        items.forEach { item ->
            val selected = currentRoute == item.route || (item.route == "protokoll" && currentRoute?.startsWith("protokoll") == true)
            NavigationDrawerItem(
                label = { Text(item.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                icon = { Icon(item.icon, contentDescription = null) },
                selected = selected,
                onClick = {
                    onCloseDrawer()
                    onNavigate(item.route)
                },
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .heightIn(min = 48.dp)
            )
        }
    }
}
