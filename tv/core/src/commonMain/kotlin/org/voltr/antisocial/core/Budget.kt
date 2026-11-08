package org.voltr.antisocial.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Session and budget state, kept fresh from the server for every screen. */
class Budget(private val api: Api, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _serverDown = MutableStateFlow(false)
    val serverDown: StateFlow<Boolean> = _serverDown.asStateFlow()

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    private var starting = false

    suspend fun refresh() {
        try {
            _status.value = api.status()
            _serverDown.value = false
            _signedOut.value = false
        } catch (e: ApiError) {
            _serverDown.value = !e.signedOut
            _signedOut.value = e.signedOut
        }
    }

    /** A check-in's answer is as fresh as a status request. */
    fun update(status: Status) {
        _status.value = status
        _serverDown.value = false
    }

    fun startSession(videos: Int) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                _status.value = api.startSession(videos)
                _sessionError.value = null
            } catch (e: ApiError) {
                _sessionError.value = e.message
                refresh()
            } finally {
                starting = false
            }
        }
    }
}
