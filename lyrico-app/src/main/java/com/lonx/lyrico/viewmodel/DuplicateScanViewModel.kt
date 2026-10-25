package com.lonx.lyrico.viewmodel

import android.content.IntentSender
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.utils.DuplicateDetector
import com.lonx.lyrico.utils.DuplicateGroup
import com.lonx.lyrico.utils.DuplicateItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.min

/** 查重模式：按文件名（相似度分组）或按内容（同大小 + 采样指纹一致）。 */
enum class DuplicateScanMode { FILENAME, CONTENT }

data class DuplicateScanUiState(
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val mode: DuplicateScanMode = DuplicateScanMode.FILENAME,
    val groups: List<DuplicateGroup> = emptyList(),
    val selectedForDeletion: Set<String> = emptySet(),
    val deleting: Boolean = false,
    /** 非 null 时表示正在等用户在系统删除确认框里点头，确认/拒绝见 [DuplicateScanViewModel.onPermissionResult] */
    val permissionIntentSender: IntentSender? = null
) {
    val selectedCount: Int get() = selectedForDeletion.size
}

class DuplicateScanViewModel(
    private val songLibraryRepository: SongLibraryRepository,
    private val deleteSongsUseCase: DeleteSongsUseCase,
    private val audioFileAccess: AudioFileAccess
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicateScanUiState())
    val uiState: StateFlow<DuplicateScanUiState> = _uiState.asStateFlow()

    /** 切换查重模式并立即重扫（重置为该模式下的默认勾选）。 */
    fun setMode(mode: DuplicateScanMode) {
        if (_uiState.value.mode == mode || _uiState.value.scanning) return
        _uiState.update { it.copy(mode = mode) }
        scan()
    }

    /**
     * 扫描整个曲库，按文件名找出重复分组，默认勾选「除建议保留外的所有重复项」。
     *
     * [resetSelection] 传 false 用于删除后的刷新：保留用户勾选里仍出现在分组中的项，
     * 已删掉的文件不再成组、勾选自然掉出；整体重置会把勾选打回默认值。
     */
    fun scan(resetSelection: Boolean = true) {
        if (_uiState.value.scanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val songs = songLibraryRepository.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first()
            val mode = _uiState.value.mode
            val (groups, selected) = withContext(Dispatchers.Default) {
                val items = songs.map { song ->
                    DuplicateItem(
                        id = song.uri,
                        fileName = song.fileName,
                        size = song.fileSize,
                        relativePath = song.filePath,
                        durationMs = song.durationMilliseconds.toLong(),
                        tagArtist = song.artist.orEmpty()
                    )
                }
                val detectedGroups: List<DuplicateGroup>
                val defaultSelected = HashSet<String>()
                when (mode) {
                    DuplicateScanMode.FILENAME -> {
                        detectedGroups = DuplicateDetector.detect(items, contentSampler = ::contentFingerprint)
                        for (group in detectedGroups) {
                            val keep = DuplicateDetector.recommendKeep(group)
                            group.files.forEachIndexed { i, f ->
                                // 疑似组默认不勾：查重是提示，误删不可逆，让用户核对后再动手
                                if (i != keep && !group.suspect) defaultSelected.add(f.id)
                            }
                        }
                    }
                    DuplicateScanMode.CONTENT -> {
                        // 指纹缓存：同一次扫描里同一首只读一次文件
                        val fingerprints = HashMap<String, String>()
                        detectedGroups = DuplicateDetector.detectByContent(items) { item ->
                            fingerprints.getOrPut(item.id) { contentFingerprint(item) }
                        }
                        for (group in detectedGroups) {
                            val keep = DuplicateDetector.recommendKeep(group)
                            group.files.forEachIndexed { i, f ->
                                if (i != keep) defaultSelected.add(f.id)
                            }
                        }
                    }
                }
                detectedGroups to defaultSelected
            }
            _uiState.update { state ->
                state.copy(
                    scanning = false,
                    scannedCount = songs.size,
                    groups = groups,
                    selectedForDeletion = if (resetSelection) {
                        selected
                    } else {
                        state.selectedForDeletion.filter { id ->
                            groups.any { group -> group.files.any { file -> file.id == id } }
                        }.toSet()
                    }
                )
            }
        }
    }

    fun toggle(id: String) {
        _uiState.update { s ->
            val next = s.selectedForDeletion.toMutableSet()
            if (!next.add(id)) next.remove(id)
            s.copy(selectedForDeletion = next)
        }
    }

    /** 删除所有勾选的重复文件；需要系统授权时先弹确认框，用户确认后再收尾。 */
    fun deleteSelected() {
        if (_uiState.value.deleting || _uiState.value.permissionIntentSender != null) return
        viewModelScope.launch {
            val ids = _uiState.value.selectedForDeletion
            if (ids.isEmpty()) return@launch
            runDeletion(ids)
        }
    }

    /**
     * 系统删除确认框的回执：同意 → 重跑一遍删除（已由系统删掉的走「已不存在」，
     * 顺带清掉库表记录）；拒绝 → 关掉弹窗状态，保留勾选等用户重试。
     */
    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.update { it.copy(permissionIntentSender = null) }
            return
        }
        viewModelScope.launch {
            val ids = _uiState.value.selectedForDeletion
            if (ids.isEmpty()) {
                _uiState.update { it.copy(permissionIntentSender = null) }
                return@launch
            }
            runDeletion(ids)
        }
    }

    private suspend fun runDeletion(ids: Set<String>) {
        _uiState.update { it.copy(deleting = true, permissionIntentSender = null) }
        val songs = songLibraryRepository.getSongsByUris(ids.toList())
        val result = deleteSongsUseCase(songs)
        result.permissionRequired?.let { sender ->
            // Android 10+ 删除非本应用写入的文件要用户在系统弹窗里确认；
            // 保留勾选，等授权结果回来再继续（见 onPermissionResult）
            _uiState.update { it.copy(deleting = false, permissionIntentSender = sender) }
            return
        }
        _uiState.update { it.copy(deleting = false) }
        // 刷新分组但保留用户勾选：已删掉的文件不再成组、勾选自然掉出；
        // 删除失败的（文件仍在）保留勾选，用户可直接重试
        scan(resetSelection = false)
    }

    /** 疑似组的内容采样指纹：大小 + 头 64KB + 尾 64KB 的 SHA-256（取不到返回空串）。 */
    private fun contentFingerprint(item: DuplicateItem): String {
        val size = item.size
        if (size <= 0) return ""
        val pfd = audioFileAccess.openReadableDescriptor(item.id) ?: return ""
        return pfd.use {
            try {
                val channel = ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(ByteBuffer.allocate(8).putLong(size).array())
                val head = ByteBuffer.allocate(min(SAMPLE_BYTES.toLong(), size).toInt())
                while (head.hasRemaining()) {
                    if (channel.read(head) < 0) return@use ""
                }
                digest.update(head.array())
                // 头尾采样不重叠才比尾段：小文件头段已足够区分
                if (size > 2L * SAMPLE_BYTES) {
                    channel.position(size - SAMPLE_BYTES)
                    val tail = ByteBuffer.allocate(SAMPLE_BYTES)
                    while (tail.hasRemaining()) {
                        if (channel.read(tail) < 0) return@use ""
                    }
                    digest.update(tail.array())
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (t: Throwable) {
                ""
            }
        }
    }

    private companion object {
        const val SAMPLE_BYTES = 64 * 1024
    }
}
