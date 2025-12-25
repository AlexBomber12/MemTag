@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.diagnostics

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.core.logging.safeRedact
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.SecondaryButton

@Composable
fun DiagnosticsScreen(settingsStore: SettingsStore) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val packageInfo =
        remember {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, 0)
            }
        }

    val versionName = packageInfo.versionName ?: "unknown"
    val versionCode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    val sdkLevel = Build.VERSION.SDK_INT
    val maskedToken = safeRedact(settings.mementoToken)

    val diagnosticsText =
        remember(settings, versionName, versionCode, deviceModel, sdkLevel) {
            buildDiagnosticsText(
                settings = settings,
                versionName = versionName,
                versionCode = versionCode,
                deviceModel = deviceModel,
                sdkLevel = sdkLevel,
            )
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppCard(title = "App") {
            Text(text = "Version: $versionName ($versionCode)")
        }
        AppCard(title = "Device") {
            Text(text = "Model: $deviceModel")
            Text(text = "Android SDK: $sdkLevel")
        }
        AppCard(title = "Settings") {
            Text(text = "Base URL: ${settings.mementoBaseUrl}")
            Text(text = "Library ID: ${settings.mementoLibraryId}")
            Text(text = "Token: ${if (maskedToken.isEmpty()) "(empty)" else maskedToken}")
            Text(text = "UHF Region: ${settings.uhfRegion}")
            Text(text = "UHF Power: ${settings.uhfPower} dBm")
            Text(text = "Scan action: ${settings.scan2dAction}")
            Text(text = "Scan extra key: ${settings.scan2dExtraKey}")
        }
        SecondaryButton(
            text = "Copy diagnostics",
            onClick = {
                clipboardManager.setText(AnnotatedString(diagnosticsText))
                Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun buildDiagnosticsText(
    settings: AppSettings,
    versionName: String,
    versionCode: Long,
    deviceModel: String,
    sdkLevel: Int,
): String {
    val tokenMasked = safeRedact(settings.mementoToken).ifEmpty { "(empty)" }
    return buildString {
        appendLine("App version: $versionName ($versionCode)")
        appendLine("Device: $deviceModel")
        appendLine("Android SDK: $sdkLevel")
        appendLine("Base URL: ${settings.mementoBaseUrl}")
        appendLine("Library ID: ${settings.mementoLibraryId}")
        appendLine("Token: $tokenMasked")
        appendLine("UHF Region: ${settings.uhfRegion}")
        appendLine("UHF Power: ${settings.uhfPower} dBm")
        appendLine("Scan action: ${settings.scan2dAction}")
        appendLine("Scan extra key: ${settings.scan2dExtraKey}")
    }
}
