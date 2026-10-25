package com.lonx.lyrico.plugin.runtime

import com.lonx.lyrico.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 网络调优快照：把「超时倍率」从设置流搬到内存里的一个 volatile 值，
 * 供插件源 HTTP 桥（同步调用，不能挂起等 Flow）在每次请求时直接读。
 */
class NetworkTuning(settingsRepository: SettingsRepository) {

    @Volatile
    var timeoutScale: Float = 1f
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            settingsRepository.batchMatchConfig
                .map { it.timeoutScale }
                .distinctUntilChanged()
                .collect { scale ->
                    timeoutScale = if (scale.isFinite() && scale > 0f) scale else 1f
                }
        }
    }
}
