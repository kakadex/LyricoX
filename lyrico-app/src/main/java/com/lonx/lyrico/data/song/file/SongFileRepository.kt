package com.lonx.lyrico.data.song.file

import android.content.IntentSender
import com.lonx.lyrico.data.model.entity.SongEntity

interface SongFileRepository {
    suspend fun deleteFile(song: SongEntity): DeleteSongFileResult

    suspend fun deleteFiles(songs: List<SongEntity>): BatchSongFileOperationResult

    suspend fun renameFile(
        song: SongEntity,
        newFileName: String
    ): RenameSongFileResult

    /**
     * 为一批歌创建「系统删除确认」IntentSender（Android R+ 一次弹窗覆盖整批）。
     *
     * 返回 null 表示走不了批量授权（版本低于 R、没有可用的 MediaStore URI 等），
     * 调用方退回逐条结果里的 [DeleteSongFileResult.PermissionRequired]。
     */
    suspend fun createBatchDeleteIntentSender(songs: List<SongEntity>): IntentSender?
}
