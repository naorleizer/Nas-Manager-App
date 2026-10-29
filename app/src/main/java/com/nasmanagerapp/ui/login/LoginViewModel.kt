package com.nasmanagerapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nasmanagerapp.TrueNasApplication
import com.nasmanagerapp.data.auth.SessionPreferences
import com.nasmanagerapp.data.auth.TrueNasAuthRepository
import com.nasmanagerapp.data.network.TrueNasUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: TrueNasAuthRepository,
    private val sessionPreferences: SessionPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(
            serverUrl = sessionPreferences.serverUrl,
            username = sessionPreferences.username,
            // Mirrors the guard in onServerUrlChange: if the prefilled address is already
            // http://, the checkbox must start unchecked, not just disabled.
            rememberMe = !TrueNasUrl.isHttp(sessionPreferences.serverUrl),
        )
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(value: String) {
        _uiState.update {
            // "Stay logged in" would otherwise resend the password automatically, in the
            // clear, on any network the device joins later — force it off over http:// and
            // keep it off until the address is no longer http.
            val rememberMe = if (TrueNasUrl.isHttp(value)) false else it.rememberMe
            it.copy(serverUrl = value, rememberMe = rememberMe, errorMessage = null)
        }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onRememberMeChange(value: Boolean) {
        _uiState.update { it.copy(rememberMe = value) }
    }

    fun onAcceptHttpRisksChange(value: Boolean) {
        _uiState.update { it.copy(acceptHttpRisks = value, errorMessage = null) }
    }

    fun connect(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.isLoading) return

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.login(state.serverUrl, state.username, state.password, state.acceptHttpRisks)
                .onSuccess {
                    // Server address and username are prefilled on next launch regardless of
                    // "Stay logged in", so an http:// user who can't save the password still
                    // only has to retype that.
                    sessionPreferences.serverUrl = state.serverUrl
                    sessionPreferences.username = state.username
                    // Defense in depth: never persist the password for an http:// address even
                    // if rememberMe somehow got here as true (the UI already forces it off).
                    if (state.rememberMe && !TrueNasUrl.isHttp(state.serverUrl)) {
                        sessionPreferences.password = state.password
                        sessionPreferences.isLoggedIn = true
                    }
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Unknown error.")
                    }
                }
        }
    }
}

class LoginViewModelFactory(private val app: TrueNasApplication) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LoginViewModel(app.authRepository, app.sessionPreferences) as T
    }
}
