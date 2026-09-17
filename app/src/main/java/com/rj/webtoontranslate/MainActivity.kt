package com.rj.webtoontranslate

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rj.webtoontranslate.ui.theme.BrandMuted
import com.rj.webtoontranslate.ui.theme.BrandSurface
import com.rj.webtoontranslate.ui.theme.WebtoonTranslateTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private var pendingTargetLanguage: String = LanguageOptions.DEFAULT_TARGET_CODE
    private var serviceRunning by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
                    putExtra(OverlayService.EXTRA_TARGET_LANGUAGE, pendingTargetLanguage)
                }
                ContextCompat.startForegroundService(this, intent)
                serviceRunning = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            WebtoonTranslateTheme {
                var hasOverlayPermission by remember { mutableStateOf(hasOverlayPermission()) }
                var hasNotifPermission by remember { mutableStateOf(hasNotificationPermission()) }
                val targetLanguage by prefs.targetLanguageCode.collectAsState(
                    initial = LanguageOptions.DEFAULT_TARGET_CODE
                )
                val scope = rememberCoroutineScope()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasOverlayPermission = hasOverlayPermission()
                            hasNotifPermission = hasNotificationPermission()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        hasOverlayPermission = hasOverlayPermission,
                        hasNotifPermission = hasNotifPermission,
                        serviceRunning = serviceRunning,
                        targetLanguageCode = targetLanguage,
                        onRequestOverlayPermission = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            overlayPermissionLauncher.launch(intent)
                        },
                        onRequestNotifPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onLanguageSelected = { code ->
                            scope.launch { prefs.setTargetLanguage(code) }
                        },
                        onToggleService = {
                            if (serviceRunning) {
                                sendBroadcast(Intent(OverlayService.ACTION_STOP))
                                serviceRunning = false
                            } else {
                                pendingTargetLanguage = targetLanguage
                                val manager = getSystemService(MediaProjectionManager::class.java)
                                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun HomeScreen(
    hasOverlayPermission: Boolean,
    hasNotifPermission: Boolean,
    serviceRunning: Boolean,
    targetLanguageCode: String,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotifPermission: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onToggleService: () -> Unit
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    val readyToStart = hasOverlayPermission && hasNotifPermission

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Webtoon Translate", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Live, on-device translation overlaid right on top of the original text.",
            style = MaterialTheme.typography.bodyMedium,
            color = BrandMuted,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(28.dp))

        if (!hasOverlayPermission) {
            PermissionCard(
                title = "Display over other apps",
                description = "Needed so the scan button and translations can appear on top of your webtoon reader.",
                actionLabel = "Grant permission",
                onClick = onRequestOverlayPermission
            )
            Spacer(Modifier.height(12.dp))
        }
        if (!hasNotifPermission) {
            PermissionCard(
                title = "Notifications",
                description = "Android requires a visible notification while the translation overlay is running.",
                actionLabel = "Allow notifications",
                onClick = onRequestNotifPermission
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))

        LanguageRow(
            label = "Translate into",
            valueLabel = LanguageOptions.labelFor(targetLanguageCode),
            onClick = { showLanguagePicker = true }
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onToggleService,
            enabled = readyToStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                if (serviceRunning) "Stop translation overlay" else "Start translation overlay",
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            if (serviceRunning)
                "Tap the floating bubble to scan. Long-press it to clear a translation."
            else
                "Open your webtoon app after starting -- the bubble stays on top.",
            style = MaterialTheme.typography.bodyMedium,
            color = BrandMuted,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            selected = targetLanguageCode,
            onDismiss = { showLanguagePicker = false },
            onSelect = {
                onLanguageSelected(it)
                showLanguagePicker = false
            }
        )
    }
}

@Composable
private fun PermissionCard(title: String, description: String, actionLabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BrandSurface)
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        Button(onClick = onClick) { Text(actionLabel) }
    }
}

@Composable
private fun LanguageRow(label: String, valueLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BrandSurface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClick) { Text(valueLabel, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun LanguagePickerDialog(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translate into") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(LanguageOptions.ALL) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (entry.code == selected) BrandSurface else Color.Transparent)
                            .clickable { onSelect(entry.code) }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                    ) {
                        Text(entry.label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
