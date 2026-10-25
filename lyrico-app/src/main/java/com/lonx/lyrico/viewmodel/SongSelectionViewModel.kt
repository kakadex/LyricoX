package com.lonx.lyrico.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.R
import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.getUri
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.RenameSongUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SongSelectionViewModel(
    private val deleteSongsUseCase: DeleteSongsUseCase,
    private val renameSongUseCase: RenameSongUseCase,
    private val playbackRepository: PlaybackRepository,
    private val selectionManager: SharedSelectionManager
) : ViewModel() {

    val selectedSongUris = selectionManager.selectedUris
    val isSelectionMode = selectionManager.isSelectionMode
    val swipeAnchorUri = selectionManager.swipeAnchorUri

    /** 非 null 时正在等用户在系统删除确认框里操作，回执见 [onPermissionResult] */
    private val _deletePermissionSender = MutableStateFlow<IntentSender?>(null)
    val deletePermissionSender: StateFlow<IntentSender?> = _deletePermissionSender.asStateFlow()

    private var pendingDeletion: List<SongEntity> = emptyList()
    private var pendingExitSelection: Boolean = false
    private var deleteJob: Job? = null

    fun toggleSelection(uri: String) {
        selectionManager.toggle(uri)
    }

    fun swipeSelect(song: SongEntity, visibleSongs: List<SongEntity>) {
        selectionManager.selectSwipeRange(
            uri = song.uri,
            visibleUris = visibleSongs.map { it.uri }
        )
    }

    fun exitSelectionMode() {
        selectionManager.exitSelectionMode()
    }

    fun deselectAll() {
        selectionManager.deselectAll()
    }

    fun selectAll(songs: List<SongEntity>) {
        selectionManager.selectAll(songs.map { it.uri }.toSet())
    }

    fun setSelectionUris(): Boolean {
        val selectedUris = selectedSongUris.value
        if (selectedUris.isEmpty()) return false

        selectionManager.setUris(selectedUris)
        return true
    }

    fun play(context: Context, song: SongEntity) {
        playbackRepository.play(context, song.getUri)
    }

    fun delete(song: SongEntity) {
        runDeletion(listOf(song), exitOnDone = false)
    }

    fun batchDelete(songs: List<SongEntity>) {
        val selectedUris = selectedSongUris.value
        runDeletion(songs.filter { it.uri in selectedUris }, exitOnDone = true)
    }

    /**
     * 系统删除确认框的回执：同意 → 重跑一遍删除（已由系统删掉的走「已不存在」，
     * 顺带清库表）；拒绝 → 清掉弹窗状态，保持现状可重试。
     */
    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            pendingDeletion = emptyList()
            _deletePermissionSender.value = null
            return
        }
        val songs = pendingDeletion
        if (songs.isNotEmpty()) runDeletion(songs, exitOnDone = pendingExitSelection)
    }

    private fun runDeletion(songs: List<SongEntity>, exitOnDone: Boolean) {
        if (songs.isEmpty()) return
        if (deleteJob?.isActive == true) return
        deleteJob = viewModelScope.launch {
            _deletePermissionSender.value = null
            val result = deleteSongsUseCase(songs)
            result.permissionRequired?.let { sender ->
                // Android 10+ 删除非本应用写入的文件要用户在系统弹窗里确认：
                // 保留清单与勾选，等回执（见 onPermissionResult）
                pendingDeletion = songs
                pendingExitSelection = exitOnDone
                _deletePermissionSender.value = sender
                return@launch
            }
            pendingDeletion = emptyList()
            if (exitOnDone) exitSelectionMode()
        }
    }

    fun batchShare(context: Context, songs: List<SongEntity>) {
        val selectedUris = selectedSongUris.value
        val toShare = songs.filter { it.uri in selectedUris }
        if (toShare.isEmpty()) return

        val uris = toShare.map { it.uri.toUri() }.toCollection(ArrayList())

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.share_chooser_title)
            )
        )
    }

    fun renameSong(song: SongEntity, newFileName: String) {
        viewModelScope.launch {
            renameSongUseCase(song, newFileName)
        }
    }
}
