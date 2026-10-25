package com.lonx.lyrico.ui.components

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统删除授权弹窗的统一挂载点。
 *
 * Android 10+ 删除非本应用写入的文件前必须经用户确认：数据层拿到
 * IntentSender 后交到这里拉起系统弹窗，用户的选择经 [onResult] 回给
 * 调用方（同意 → 收尾删除；拒绝 → 保持现状可重试）。
 */
@Composable
fun DeletePermissionHost(
    senderFlow: StateFlow<IntentSender?>,
    onResult: (Boolean) -> Unit
) {
    val sender by senderFlow.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> onResult(result.resultCode == Activity.RESULT_OK) }

    LaunchedEffect(sender) {
        sender?.let { sender ->
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
}
