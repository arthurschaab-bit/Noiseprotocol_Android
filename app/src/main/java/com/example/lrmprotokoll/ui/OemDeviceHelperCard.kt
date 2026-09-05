package com.example.lrmprotokoll.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

const val OEM_HELPER_CARD_TAG = "oem_helper_card"
const val OEM_BATTERY_OPTIMIZATION_BUTTON_TAG = "oem_battery_optimization_button"
const val OEM_EXACT_ALARM_BUTTON_TAG = "oem_exact_alarm_button"
const val OEM_NOTIFICATION_SETTINGS_BUTTON_TAG = "oem_notification_settings_button"

/**
 * Erkennt OEM-Besonderheiten (z.B. Xiaomi Pad 6 / HyperOS / MIUI, Tablets ohne Vibrationsmotor)
 * und bietet direkte One-Tap-Lösungen für Berechtigungen, Akku-Ausnahmen und Autostart.
 *
 * Die drei Override-Parameter sind reine Test-Hooks. In Produktion sind sie null und der reale
 * Systemzustand wird unverändert verwendet. Instrumentierte Tests können damit die normalerweise
 * geräteabhängige Sichtbarkeit der System-Intent-Buttons deterministisch erzwingen.
 */
@Composable
fun OemDeviceHelperCard(
    modifier: Modifier = Modifier,
    notificationPermissionOverride: Boolean? = null,
    exactAlarmPermissionOverride: Boolean? = null,
    batteryOptimizedOverride: Boolean? = null,
) {
    val context = LocalContext.current
    val manufacturer = Build.MANUFACTURER
    val model = Build.MODEL
    val isXiaomi = remember {
        manufacturer.contains("Xiaomi", ignoreCase = true) ||
            manufacturer.contains("Redmi", ignoreCase = true) ||
            manufacturer.contains("POCO", ignoreCase = true)
    }
    val oemHinweis = remember { leiteOemAutostartHinweisAb(manufacturer) }

    val hasVibrator = remember {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        v?.hasVibrator() == true
    }

    val realNotificationPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }
    val hasNotificationPermission = notificationPermissionOverride ?: realNotificationPermission

    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    val realCanExactAlarm = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else true
    }
    val canExactAlarm = exactAlarmPermissionOverride ?: realCanExactAlarm

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val realBatteryOptimized = remember {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true
    }
    val isBatteryOptimized = batteryOptimizedOverride ?: realBatteryOptimized

    val hasIssues = !hasNotificationPermission || !canExactAlarm || isBatteryOptimized ||
        (!hasVibrator && isXiaomi) || oemHinweis != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(OEM_HELPER_CARD_TAG),
        colors = CardDefaults.cardColors(
            containerColor = if (hasIssues) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasIssues) Icons.Default.Warning else Icons.Default.Check,
                        contentDescription = null,
                        tint = if (hasIssues) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Geräte- & Alarm-Diagnose",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasIssues) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9)
                ) {
                    Text(
                        text = if (hasIssues) "Prüfung nötig" else "Optimal konfiguriert",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasIssues) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Modell: $manufacturer $model (Android ${Build.VERSION.RELEASE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "• Hardware-Vibration: " + (if (hasVibrator) "Vorhanden" else "Nicht vorhanden (Akustischer Alarmton wird forciert)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!hasVibrator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "• Benachrichtigungen: " + (if (hasNotificationPermission) "Erlaubt" else "Blockiert"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!hasNotificationPermission) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "• Exakte Alarme: " + (if (canExactAlarm) "Erlaubt" else "Eingeschränkt"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!canExactAlarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "• Akku-Optimierung: " + (if (!isBatteryOptimized) "Ausgenommen (Keine Einschränkungen)" else "Eingeschränkt (kann Alarme verzögern)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isBatteryOptimized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (hasIssues) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Empfohlene Aktionen für zuverlässige Alarme:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (!hasNotificationPermission) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .testTag(OEM_NOTIFICATION_SETTINGS_BUTTON_TAG)
                    ) {
                        Text("Benachrichtigungen erlauben")
                    }
                }

                if (isBatteryOptimized) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .testTag(OEM_BATTERY_OPTIMIZATION_BUTTON_TAG)
                    ) {
                        Text("Akku-Optimierung aufheben")
                    }
                }

                if (!canExactAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .testTag(OEM_EXACT_ALARM_BUTTON_TAG)
                    ) {
                        Text("Exakte Alarme freischalten")
                    }
                }

                if (oemHinweis != null) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent().apply {
                                    setClassName(oemHinweis.intentPackage, oemHinweis.intentActivity)
                                }
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(oemHinweis.hinweistext)
                    }
                }
            }
        }
    }
}
