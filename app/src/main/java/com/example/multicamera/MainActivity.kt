package com.example.multicamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.multicamera.ui.theme.MultiCameraTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var permissionsGranted = false
    private var plan: LensPlan? = null
    private var recorder: MultiLensRecorder? = null
    private var sdCard: SdCard? = null
    private val previewContainers = LinkedHashMap<String, FrameLayout>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshPreview() }
    private val prefs by lazy { getSharedPreferences("multicam", MODE_PRIVATE) }

    private var uiState by mutableStateOf(MultiCamUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uiState = uiState.copy(status = getString(R.string.status_waiting_permission))

        setContent {
            MultiCameraTheme(dynamicColor = false) {
                MultiCamScreen(
                    state = uiState,
                    onPermissionsChanged = ::onPermissionsChanged,
                    onPreviewReady = { id, frame ->
                        previewContainers[id] = frame
                        scheduleRefresh()
                    },
                    onPreviewRelease = { id, frame ->
                        if (previewContainers[id] === frame) previewContainers.remove(id)
                    },
                    onRecordToggle = { if (uiState.isRecording) stopRecording() else startRecording() },
                    onToggleLens = ::toggleLens,
                    onPresetUltraTele = { selectLenses(ultraTeleSelection(plan?.lenses.orEmpty())) },
                    onPresetAll = { selectLenses(plan?.lenses.orEmpty().map { it.physicalId }.toSet()) },
                    onFpsChanged = { uiState = uiState.copy(videoSettings = uiState.videoSettings.copy(fps = it)) },
                    onQualityChanged = { id, q ->
                        val s = uiState.videoSettings
                        uiState = uiState.copy(videoSettings = s.copy(qualities = s.qualities + (id to q)))
                    },
                    onStorageChanged = ::changeStorage,
                    onToggleDiagnostics = {
                        uiState = uiState.copy(
                            diagnostics = if (uiState.diagnostics == null)
                                runCatching { LensDetector.diagnostics(this) }.getOrNull() else null
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorage()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        recorder?.release()
        super.onDestroy()
    }

    // ── 権限・レンズ検出 ────────────────────────────────────────────────────

    private fun onPermissionsChanged(granted: Boolean) {
        permissionsGranted = granted
        uiState = uiState.copy(
            hasPermissions = granted,
            status = getString(if (granted) R.string.status_preparing else R.string.status_need_permission)
        )
        if (granted) setupLenses()
    }

    private fun setupLenses() {
        val detected = try {
            LensDetector.detect(this)
        } catch (t: Throwable) {
            uiState = uiState.copy(
                error = getString(R.string.error_detect_failed, t.localizedMessage ?: ""),
                diagnostics = runCatching { LensDetector.diagnostics(this) }.getOrNull()
            )
            return
        }
        if (detected == null) {
            uiState = uiState.copy(
                error = getString(R.string.error_unsupported_device),
                diagnostics = runCatching { LensDetector.diagnostics(this) }.getOrNull()
            )
            return
        }
        plan = detected

        val ids = detected.lenses.map { it.physicalId }.toSet()
        val saved = prefs.getStringSet(PREF_LENSES, null)?.filter { it in ids }?.toSet().orEmpty()
        val selected = saved.ifEmpty { ultraTeleSelection(detected.lenses) }

        if (recorder == null) recorder = MultiLensRecorder(this, recorderCallback)
        val noTele = detected.lenses.none { it.type == LensType.TELE }
        uiState = uiState.copy(
            lenses = detected.lenses,
            selectedIds = selected,
            error = if (noTele) getString(R.string.error_no_tele) else null,
            diagnostics = if (noTele) runCatching { LensDetector.diagnostics(this) }.getOrNull() else null
        )
        scheduleRefresh()
    }

    /** 超広角＋望遠。どちらかが無ければ、画角の両端の2本（1本なら その1本）。 */
    private fun ultraTeleSelection(lenses: List<LensInfo>): Set<String> {
        val ultra = lenses.firstOrNull { it.type == LensType.ULTRA_WIDE }
        val tele = lenses.lastOrNull { it.type == LensType.TELE }
        return if (ultra != null && tele != null) setOf(ultra.physicalId, tele.physicalId)
        else listOfNotNull(lenses.firstOrNull(), lenses.lastOrNull()).map { it.physicalId }.toSet()
    }

    // ── レンズ選択 ──────────────────────────────────────────────────────────

    private fun toggleLens(id: String) {
        val current = uiState.selectedIds
        val next = if (id in current) current - id else current + id
        if (next.isNotEmpty()) selectLenses(next)   // 最低1本は選択
    }

    private fun selectLenses(ids: Set<String>) {
        if (uiState.isRecording || ids.isEmpty() || ids == uiState.selectedIds) return
        uiState = uiState.copy(selectedIds = ids)
        prefs.edit().putStringSet(PREF_LENSES, ids).apply()
        scheduleRefresh()
    }

    /** プレビュー用の View が揃うのを待ってから、まとめて1回だけ再構築する。 */
    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 60)
    }

    private fun refreshPreview() {
        if (!permissionsGranted || uiState.isRecording) return
        val p = plan ?: return
        val rec = recorder ?: return
        val selected = p.lenses.filter { it.physicalId in uiState.selectedIds }
        if (selected.isEmpty() || selected.any { previewContainers[it.physicalId] == null }) return
        uiState = uiState.copy(isCameraReady = false, status = getString(R.string.status_preparing))
        rec.startPreview(p, selected, previewContainers.toMap())
    }

    // ── 保存先 ──────────────────────────────────────────────────────────────

    private fun refreshStorage() {
        sdCard = StorageHelper.findSdCard(this)
        val wanted = when (prefs.getString(PREF_STORAGE, null)) {
            StorageChoice.INTERNAL.name -> StorageChoice.INTERNAL
            else -> StorageChoice.SD            // 未設定ならSDカード優先
        }
        if (uiState.isRecording) {
            uiState = uiState.copy(sdName = sdCard?.description)
            return
        }
        uiState = uiState.copy(
            sdName = sdCard?.description,
            storage = if (wanted == StorageChoice.SD && sdCard != null) StorageChoice.SD else StorageChoice.INTERNAL
        )
    }

    private fun changeStorage(choice: StorageChoice) {
        if (uiState.isRecording) return
        if (choice == StorageChoice.SD && sdCard == null) return
        prefs.edit().putString(PREF_STORAGE, choice.name).apply()
        uiState = uiState.copy(storage = choice)
    }

    // ── 録画 ────────────────────────────────────────────────────────────────

    private fun startRecording() {
        if (!permissionsGranted) return
        val target = when (uiState.storage) {
            StorageChoice.SD -> {
                val sd = StorageHelper.findSdCard(this)
                sdCard = sd
                if (sd == null) {
                    uiState = uiState.copy(sdName = null, error = getString(R.string.error_sd_missing))
                    return
                }
                StorageHelper.sdTarget(this, sd)
            }
            StorageChoice.INTERNAL -> StorageHelper.internalTarget(this)
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recorder?.startRecording(target, timestamp, uiState.videoSettings)
    }

    private fun stopRecording() {
        uiState = uiState.copy(status = getString(R.string.status_saving))
        recorder?.stopRecording()
    }

    private val recorderCallback = object : MultiLensRecorder.Callback {
        override fun onReady(lensCount: Int) = runOnUiThread {
            uiState = uiState.copy(
                isCameraReady = true,
                status = getString(R.string.status_ready, lensCount),
                error = null
            )
        }

        override fun onError(message: String, diagnostics: String) = runOnUiThread {
            uiState = uiState.copy(
                isCameraReady = false,
                isRecording = false,
                status = message,
                error = "$message\n$diagnostics"
            )
        }

        override fun onRecordingStarted(summary: String) = runOnUiThread {
            uiState = uiState.copy(
                isRecording = true,
                status = getString(R.string.status_recording, summary),
                error = null,
                lastFiles = emptyList()
            )
        }

        override fun onRecordingFinished(files: List<String>, message: String) = runOnUiThread {
            uiState = uiState.copy(isRecording = false, status = message, error = null, lastFiles = files)
            refreshStorage()
        }
    }

    private companion object {
        const val PREF_LENSES = "selected_lenses"
        const val PREF_STORAGE = "storage"
    }
}

// ── UI State ──────────────────────────────────────────────────────────────────

private data class MultiCamUiState(
    val hasPermissions: Boolean = false,
    val isCameraReady: Boolean = false,
    val isRecording: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val lenses: List<LensInfo> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val storage: StorageChoice = StorageChoice.INTERNAL,
    val sdName: String? = null,
    val diagnostics: String? = null,
    val lastFiles: List<String> = emptyList(),
    val videoSettings: VideoSettings = VideoSettings()
)

private val LensColors = listOf(Color(0xFFBFE7FF), Color(0xFF8EE7B5), Color(0xFFFFD27F), Color(0xFFE3B8FF), Color(0xFFFFB3A9))
private fun lensColor(index: Int) = LensColors[index % LensColors.size]

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
private fun MultiCamScreen(
    state: MultiCamUiState,
    onPermissionsChanged: (Boolean) -> Unit,
    onPreviewReady: (String, FrameLayout) -> Unit,
    onPreviewRelease: (String, FrameLayout?) -> Unit,
    onRecordToggle: () -> Unit,
    onToggleLens: (String) -> Unit,
    onPresetUltraTele: () -> Unit,
    onPresetAll: () -> Unit,
    onFpsChanged: (FpsPreference) -> Unit,
    onQualityChanged: (String, QualityPreference) -> Unit,
    onStorageChanged: (StorageChoice) -> Unit,
    onToggleDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionsChanged(
            results[Manifest.permission.CAMERA] == true &&
                results[Manifest.permission.RECORD_AUDIO] == true
        )
    }

    LaunchedEffect(Unit) {
        val granted = RequiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) onPermissionsChanged(true)
        else permissionLauncher.launch(RequiredPermissions)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF111315)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF171A1E), Color(0xFF0C0D0F))))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(state)
                PreviewLayout(
                    state = state,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onPreviewReady = onPreviewReady,
                    onPreviewRelease = onPreviewRelease
                )
                SettingsPanel(
                    state = state,
                    onToggleLens = onToggleLens,
                    onPresetUltraTele = onPresetUltraTele,
                    onPresetAll = onPresetAll,
                    onFpsChanged = onFpsChanged,
                    onQualityChanged = onQualityChanged,
                    onStorageChanged = onStorageChanged,
                    onToggleDiagnostics = onToggleDiagnostics
                )
                ControlDock(state = state, onRecordToggle = onRecordToggle)
            }
        }
    }
}

@Composable
private fun Header(state: MultiCamUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.status,
                color = if (state.error == null) Color(0xFFB7C3C9) else Color(0xFFFFB3A9),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        StatusPill(
            text = stringResource(
                if (state.isRecording) R.string.pill_rec else if (state.isCameraReady) R.string.pill_ready else R.string.pill_wait
            ),
            color = if (state.isRecording) Color(0xFFE94141) else if (state.isCameraReady) Color(0xFF35D07F) else Color(0xFFFFC857)
        )
    }
}

@Composable
private fun PreviewLayout(
    state: MultiCamUiState,
    modifier: Modifier = Modifier,
    onPreviewReady: (String, FrameLayout) -> Unit,
    onPreviewRelease: (String, FrameLayout?) -> Unit
) {
    val selected = state.lenses.filter { it.physicalId in state.selectedIds }

    BoxWithConstraints(modifier = modifier) {
        val horizontal = maxWidth > maxHeight
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                selected.forEach { lens ->
                    key(lens.physicalId) {
                        PreviewTile(
                            lens = lens,
                            color = lensColor(state.lenses.indexOf(lens)),
                            recording = state.isRecording,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onPreviewReady = onPreviewReady,
                            onPreviewRelease = onPreviewRelease
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                selected.forEach { lens ->
                    key(lens.physicalId) {
                        PreviewTile(
                            lens = lens,
                            color = lensColor(state.lenses.indexOf(lens)),
                            recording = state.isRecording,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onPreviewReady = onPreviewReady,
                            onPreviewRelease = onPreviewRelease
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(
    lens: LensInfo,
    color: Color,
    recording: Boolean,
    modifier: Modifier = Modifier,
    onPreviewReady: (String, FrameLayout) -> Unit,
    onPreviewRelease: (String, FrameLayout?) -> Unit
) {
    val frameHolder = remember { arrayOfNulls<FrameLayout>(1) }
    DisposableEffect(lens.physicalId) {
        onDispose { onPreviewRelease(lens.physicalId, frameHolder[0]) }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090A0C)),
        border = BorderStroke(1.dp, if (recording) Color(0xFFE94141) else Color(0xFF2C343A))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).also { frame ->
                        frame.layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                        frameHolder[0] = frame
                        onPreviewReady(lens.physicalId, frame)
                    }
                }
            )
            // レンズ名
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC111315))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(lens.label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(lens.detail, color = Color(0xFFC9D3D8), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (recording) {
                StatusPill(
                    text = stringResource(R.string.pill_rec),
                    color = Color(0xFFE94141),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xBB111315))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.preview_disabled),
                        color = Color(0xFFB7C3C9),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ── 設定パネル ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(
    state: MultiCamUiState,
    onToggleLens: (String) -> Unit,
    onPresetUltraTele: () -> Unit,
    onPresetAll: () -> Unit,
    onFpsChanged: (FpsPreference) -> Unit,
    onQualityChanged: (String, QualityPreference) -> Unit,
    onStorageChanged: (StorageChoice) -> Unit,
    onToggleDiagnostics: () -> Unit
) {
    val enabled = !state.isRecording
    val hasUltra = state.lenses.any { it.type == LensType.ULTRA_WIDE }
    val hasTele = state.lenses.any { it.type == LensType.TELE }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B1F)),
        border = BorderStroke(1.dp, Color(0xFF2C343A))
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 230.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // レンズ選択
            SectionTitle(stringResource(R.string.lens_select_title))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.lenses.forEachIndexed { index, lens ->
                    Chip(
                        text = lens.label,
                        active = lens.physicalId in state.selectedIds,
                        accent = lensColor(index),
                        enabled = enabled
                    ) { onToggleLens(lens.physicalId) }
                }
                Spacer(Modifier.width(6.dp))
                Chip(
                    text = stringResource(R.string.preset_ultra_tele),
                    active = false,
                    accent = Color(0xFFFFD27F),
                    enabled = enabled && hasUltra && hasTele,
                    onClick = onPresetUltraTele
                )
                Chip(
                    text = stringResource(R.string.preset_all),
                    active = state.lenses.isNotEmpty() && state.selectedIds.size == state.lenses.size,
                    accent = Color(0xFFFFD27F),
                    enabled = enabled,
                    onClick = onPresetAll
                )
                Chip(
                    text = "診断",
                    active = state.diagnostics != null,
                    accent = Color(0xFFB7C3C9),
                    enabled = true,
                    onClick = onToggleDiagnostics
                )
            }

            // 保存先
            SectionTitle(stringResource(R.string.storage_title))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    text = stringResource(R.string.storage_internal),
                    active = state.storage == StorageChoice.INTERNAL,
                    accent = Color(0xFF35D07F),
                    enabled = enabled
                ) { onStorageChanged(StorageChoice.INTERNAL) }
                Chip(
                    text = if (state.sdName != null) stringResource(R.string.storage_sd) else stringResource(R.string.storage_sd_missing),
                    active = state.storage == StorageChoice.SD,
                    accent = Color(0xFF35D07F),
                    enabled = enabled && state.sdName != null
                ) { onStorageChanged(StorageChoice.SD) }
            }

            // フレームレート
            SectionTitle(stringResource(R.string.fps_title))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FpsPreference.entries.forEach { f ->
                    Chip(
                        text = "${f.value}fps",
                        active = f == state.videoSettings.fps,
                        accent = Color(0xFF69C8FF),
                        enabled = enabled
                    ) { onFpsChanged(f) }
                }
            }

            // レンズごとの画質
            state.lenses.forEachIndexed { index, lens ->
                if (lens.physicalId !in state.selectedIds) return@forEachIndexed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lens.label,
                        color = lensColor(index),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(92.dp),
                        maxLines = 2
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QualityPreference.entries.forEach { q ->
                            Chip(
                                text = stringResource(q.labelRes),
                                active = q == state.videoSettings.qualityFor(lens.physicalId),
                                accent = Color(0xFF35D07F),
                                enabled = enabled
                            ) { onQualityChanged(lens.physicalId, q) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color(0xFF8FA0A8), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Chip(
    text: String,
    active: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent.copy(alpha = 0.18f) else Color(0xFF1E262B))
            .border(1.dp, if (active) accent else Color(0xFF2C343A), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) accent else if (enabled) Color(0xFFB7C3C9) else Color(0xFF4A5560),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ControlDock(state: MultiCamUiState, onRecordToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2024)),
        border = BorderStroke(1.dp, Color(0xFF303A41))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xFF8EE7B5), modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.audio_note),
                        color = Color(0xFFEAF1F4),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    enabled = state.hasPermissions && state.isCameraReady,
                    onClick = onRecordToggle,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRecording) Color(0xFFE94141) else Color(0xFF35D07F),
                        contentColor = Color(0xFF07100B),
                        disabledContainerColor = Color(0xFF3A4146),
                        disabledContentColor = Color(0xFF9BA5AA)
                    ),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (state.isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = stringResource(if (state.isRecording) R.string.cd_stop else R.string.cd_record),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            AnimatedVisibility(visible = state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    color = Color(0xFFFFB3A9),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (state.diagnostics != null) {
                SelectionContainer {
                    Text(
                        text = state.diagnostics,
                        color = Color(0xFFC9D3D8),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState())
                    )
                }
            }

            AnimatedVisibility(visible = state.lastFiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.lastFiles.forEach { path ->
                        Text(path, color = Color(0xFFB7C3C9), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private val RequiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
