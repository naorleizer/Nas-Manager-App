package com.nasmanagerapp.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nasmanagerapp.TrueNasApplication
import com.nasmanagerapp.data.dashboard.AlertInfo
import com.nasmanagerapp.data.dashboard.AppInfo
import com.nasmanagerapp.data.dashboard.BootInfo
import com.nasmanagerapp.data.dashboard.CpuPageHistory
import com.nasmanagerapp.data.dashboard.DashboardApiException
import com.nasmanagerapp.data.dashboard.DashboardRepository
import com.nasmanagerapp.data.dashboard.JobState
import com.nasmanagerapp.data.dashboard.LiveMetrics
import com.nasmanagerapp.data.dashboard.MetricsHistory
import com.nasmanagerapp.data.dashboard.NetworkInfo
import com.nasmanagerapp.data.dashboard.PoolDetail
import com.nasmanagerapp.data.dashboard.PoolDiskInfo
import com.nasmanagerapp.data.dashboard.PoolSummary
import com.nasmanagerapp.data.dashboard.ServiceChange
import com.nasmanagerapp.data.dashboard.ServiceInfo
import com.nasmanagerapp.data.dashboard.SystemGeneralSettings
import com.nasmanagerapp.data.dashboard.SystemInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashboardUiState(
    val systemInfo: SystemInfo? = null,
    val isLoadingSystemInfo: Boolean = true,
    val systemInfoError: String? = null,
    val pools: List<PoolSummary> = emptyList(),
    val isLoadingPools: Boolean = true,
    val poolsError: String? = null,
    val liveMetrics: LiveMetrics? = null,
    val liveMetricsError: String? = null,
    val lastUpdatedAtMillis: Long? = null,
    val metricsHistory: MetricsHistory? = null,
    val isLoadingHistory: Boolean = false,
    val historyError: String? = null,
    val cpuPageHistory: CpuPageHistory? = null,
    val isLoadingCpuPageHistory: Boolean = false,
    val cpuPageHistoryError: String? = null,
    val poolDetails: Map<Int, PoolDetail> = emptyMap(),
    val loadingPoolDetailIds: Set<Int> = emptySet(),
    val poolDetailErrors: Map<Int, String> = emptyMap(),
    val poolDiskDetails: List<PoolDiskInfo>? = null,
    val isLoadingPoolDiskDetails: Boolean = false,
    val poolDiskDetailsError: String? = null,
    val alerts: List<AlertInfo> = emptyList(),
    val isLoadingAlerts: Boolean = false,
    val alertsError: String? = null,
    val dismissingAlertIds: Set<String> = emptySet(),
    val apps: List<AppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val appsError: String? = null,
    val updatingAppIds: Set<String> = emptySet(),
    val switchingAppIds: Set<String> = emptySet(),
    val appActionErrors: Map<String, String> = emptyMap(),
    val systemGeneral: SystemGeneralSettings? = null,
    val isLoadingSystemGeneral: Boolean = false,
    val systemGeneralError: String? = null,
    val networkInfo: NetworkInfo? = null,
    val isLoadingNetworkInfo: Boolean = false,
    val networkInfoError: String? = null,
    val bootInfo: BootInfo? = null,
    val isLoadingBootInfo: Boolean = false,
    val bootInfoError: String? = null,
    val services: List<ServiceInfo>? = null,
    val isLoadingServices: Boolean = false,
    val servicesError: String? = null,
    val isSavingServices: Boolean = false,
    val serviceSaveErrors: List<String> = emptyList(),
)

/**
 * System info is fetched once (it barely changes). CPU, memory and pools are polled every
 * [POLL_INTERVAL_MILLIS] as long as this ViewModel is alive — the polling coroutine runs in
 * [viewModelScope], so it's cancelled automatically when the dashboard screen is left.
 */
class DashboardViewModel(private val repository: DashboardRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadSystemInfo()
        loadAlerts()
        startPolling()
    }

    fun retryLoadSystemInfo() {
        loadSystemInfo()
    }

    /**
     * Loaded on demand when the Memory graph screen is opened, again whenever its time-range
     * selector changes, and on every auto-refresh tick while its toggle is on (see
     * `GraphScreen.kt`) — not polled continuously by this ViewModel.
     */
    fun loadMetricsHistory(windowSeconds: Long) {
        _uiState.update { it.copy(isLoadingHistory = true, historyError = null) }
        viewModelScope.launch {
            repository.getMetricsHistory(windowSeconds)
                .onSuccess { history ->
                    _uiState.update { it.copy(metricsHistory = history, isLoadingHistory = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoadingHistory = false, historyError = error.message) }
                }
        }
    }

    /**
     * Loaded on demand when the CPU graph screen is opened, again whenever its time-range selector
     * changes, and on every auto-refresh tick while its toggle is on (see `GraphScreen.kt`) — not
     * polled continuously by this ViewModel.
     */
    fun loadCpuPageHistory(windowSeconds: Long) {
        _uiState.update { it.copy(isLoadingCpuPageHistory = true, cpuPageHistoryError = null) }
        viewModelScope.launch {
            repository.getCpuPageHistory(windowSeconds)
                .onSuccess { history ->
                    _uiState.update { it.copy(cpuPageHistory = history, isLoadingCpuPageHistory = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoadingCpuPageHistory = false, cpuPageHistoryError = error.message) }
                }
        }
    }

    /**
     * Loaded on demand for each pool shown on the Pools graph screen (see `GraphScreen.kt`,
     * `PoolsGraphContent`) — not polled, since topology/scrub history change far less often than
     * [PoolSummary]'s live fields. Keyed by pool id since several pools can be shown at once.
     */
    fun loadPoolDetail(poolId: Int) {
        _uiState.update {
            it.copy(
                loadingPoolDetailIds = it.loadingPoolDetailIds + poolId,
                poolDetailErrors = it.poolDetailErrors - poolId,
            )
        }
        viewModelScope.launch {
            repository.getPoolDetail(poolId)
                .onSuccess { detail ->
                    _uiState.update {
                        it.copy(
                            poolDetails = it.poolDetails + (poolId to detail),
                            loadingPoolDetailIds = it.loadingPoolDetailIds - poolId,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loadingPoolDetailIds = it.loadingPoolDetailIds - poolId,
                            poolDetailErrors = it.poolDetailErrors + (poolId to (error.message ?: "Unknown error.")),
                        )
                    }
                }
        }
    }

    /**
     * Loaded on demand when the pool detail screen (disk-by-disk breakdown) is opened — see
     * `PoolDetailScreen.kt`. One pool's disks at a time, so unlike [loadPoolDetail] this doesn't
     * need to be keyed by pool id.
     */
    fun loadPoolDiskDetails(poolId: Int, windowSeconds: Long) {
        _uiState.update { it.copy(isLoadingPoolDiskDetails = true, poolDiskDetailsError = null, poolDiskDetails = null) }
        viewModelScope.launch {
            repository.getPoolDiskDetails(poolId, windowSeconds)
                .onSuccess { disks ->
                    _uiState.update { it.copy(poolDiskDetails = disks, isLoadingPoolDiskDetails = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoadingPoolDiskDetails = false, poolDiskDetailsError = error.message) }
                }
        }
    }

    /**
     * Loaded on demand when the Alerts screen (drawer) is opened, and again on manual refresh — not
     * polled, unlike CPU/Memory/Pools, since alerts don't need 2s freshness.
     */
    fun loadAlerts() {
        _uiState.update { it.copy(isLoadingAlerts = true, alertsError = null) }
        viewModelScope.launch {
            repository.getAlerts()
                .onSuccess { alerts -> _uiState.update { it.copy(alerts = alerts, isLoadingAlerts = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoadingAlerts = false, alertsError = error.message) } }
        }
    }

    /**
     * Dismisses one alert (server-side, mirrors the TrueNAS web dashboard's own Dismiss button —
     * see `DashboardRepository.dismissAlert`) and removes it from [DashboardUiState.alerts]
     * optimistically on success, rather than re-fetching the whole list.
     */
    fun dismissAlert(id: String) {
        _uiState.update { it.copy(dismissingAlertIds = it.dismissingAlertIds + id, alertsError = null) }
        viewModelScope.launch {
            repository.dismissAlert(id)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            alerts = it.alerts.filterNot { alert -> alert.id == id },
                            dismissingAlertIds = it.dismissingAlertIds - id,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            dismissingAlertIds = it.dismissingAlertIds - id,
                            alertsError = error.message,
                        )
                    }
                }
        }
    }

    /**
     * Loaded on demand when the Apps screen (drawer) is opened, and again on manual refresh or
     * after an upgrade finishes — not polled.
     */
    fun loadApps() {
        _uiState.update { it.copy(isLoadingApps = true, appsError = null) }
        viewModelScope.launch {
            repository.getApps()
                .onSuccess { apps -> _uiState.update { it.copy(apps = apps, isLoadingApps = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoadingApps = false, appsError = error.message) } }
        }
    }

    /** "Update" on every app currently listed with [AppInfo.upgradeAvailable]. */
    fun upgradeAllOutdatedApps() {
        _uiState.value.apps
            .filter { it.upgradeAvailable && it.id !in _uiState.value.updatingAppIds }
            .forEach { upgradeApp(it.id) }
    }

    /**
     * Starts an upgrade job for one app (mirrors the TrueNAS web dashboard's own Apps update flow —
     * see `DashboardRepository.upgradeApp`) and polls it to completion, refreshing [DashboardUiState.apps]
     * on success rather than patching just that entry (version/state/upgrade_available all change).
     */
    fun upgradeApp(id: String) {
        if (id in _uiState.value.updatingAppIds || id in _uiState.value.switchingAppIds) return
        _uiState.update { it.copy(updatingAppIds = it.updatingAppIds + id, appActionErrors = it.appActionErrors - id) }
        viewModelScope.launch {
            repository.upgradeApp(id)
                .mapCatching { jobId -> awaitJobCompletion(jobId) }
                .onSuccess {
                    _uiState.update { it.copy(updatingAppIds = it.updatingAppIds - id) }
                    loadApps()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            updatingAppIds = it.updatingAppIds - id,
                            appActionErrors = it.appActionErrors + (id to (error.message ?: "Unknown error.")),
                        )
                    }
                }
        }
    }

    /**
     * Starts ([start] = true) or stops one app — same job + polling flow as [upgradeApp] (see
     * `DashboardRepository.startApp`), then re-fetches [DashboardUiState.apps] for the new state.
     */
    fun setAppRunning(id: String, start: Boolean) {
        if (id in _uiState.value.updatingAppIds || id in _uiState.value.switchingAppIds) return
        _uiState.update { it.copy(switchingAppIds = it.switchingAppIds + id, appActionErrors = it.appActionErrors - id) }
        viewModelScope.launch {
            (if (start) repository.startApp(id) else repository.stopApp(id))
                .mapCatching { jobId -> awaitJobCompletion(jobId) }
                .onSuccess {
                    _uiState.update { it.copy(switchingAppIds = it.switchingAppIds - id) }
                    loadApps()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            switchingAppIds = it.switchingAppIds - id,
                            appActionErrors = it.appActionErrors + (id to (error.message ?: "Unknown error.")),
                        )
                    }
                }
        }
    }

    /**
     * Loaded on demand when the drawer's "System" screen is opened, and again on manual refresh —
     * not polled. The four sections (General settings/Network/Boot/Services) come from independent API
     * calls, fetched concurrently and tracked with their own loading/error state so one failing
     * doesn't hide the others.
     */
    fun loadSystemSettings() {
        _uiState.update {
            it.copy(
                isLoadingSystemGeneral = true,
                systemGeneralError = null,
                isLoadingNetworkInfo = true,
                networkInfoError = null,
                isLoadingBootInfo = true,
                bootInfoError = null,
                isLoadingServices = true,
                servicesError = null,
                serviceSaveErrors = emptyList(),
            )
        }
        viewModelScope.launch {
            val generalDeferred = async { repository.getSystemGeneral() }
            val networkDeferred = async { repository.getNetworkInfo() }
            val bootDeferred = async { repository.getBootInfo() }
            val servicesDeferred = async { repository.getServices() }

            generalDeferred.await()
                .onSuccess { general -> _uiState.update { it.copy(systemGeneral = general, isLoadingSystemGeneral = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoadingSystemGeneral = false, systemGeneralError = error.message) } }

            networkDeferred.await()
                .onSuccess { network -> _uiState.update { it.copy(networkInfo = network, isLoadingNetworkInfo = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoadingNetworkInfo = false, networkInfoError = error.message) } }

            bootDeferred.await()
                .onSuccess { boot -> _uiState.update { it.copy(bootInfo = boot, isLoadingBootInfo = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoadingBootInfo = false, bootInfoError = error.message) } }

            servicesDeferred.await()
                .onSuccess { services -> _uiState.update { it.copy(services = services, isLoadingServices = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoadingServices = false, servicesError = error.message) } }
        }
    }

    /**
     * Applies the System screen's pending service edits (only sent on "Save modifications", see
     * `SystemScreen.kt`) — services in parallel, and per service "start automatically" before
     * start/stop. Then reloads the list so the toggles show the server's real state, whatever
     * succeeded; failures are reported per service in [DashboardUiState.serviceSaveErrors].
     */
    fun saveServiceChanges(changes: List<ServiceChange>) {
        if (changes.isEmpty() || _uiState.value.isSavingServices) return
        _uiState.update { it.copy(isSavingServices = true, serviceSaveErrors = emptyList()) }
        viewModelScope.launch {
            val errors = changes.map { change ->
                async {
                    runCatching {
                        change.startOnBoot?.let { repository.setServiceStartOnBoot(change.service.id, it).getOrThrow() }
                        change.running?.let { repository.setServiceRunning(change.service.name, it).getOrThrow() }
                    }.exceptionOrNull()?.let { "${change.service.label}: ${it.message ?: "Unknown error."}" }
                }
            }.awaitAll().filterNotNull()

            val reloaded = repository.getServices()
            _uiState.update {
                it.copy(
                    services = reloaded.getOrNull() ?: it.services,
                    isSavingServices = false,
                    serviceSaveErrors = errors + listOfNotNull(reloaded.exceptionOrNull()?.message),
                )
            }
        }
    }

    /** Polls [DashboardRepository.getJobStatus] until the job leaves WAITING/RUNNING, throwing on FAILED/ABORTED. */
    private suspend fun awaitJobCompletion(jobId: Long) {
        while (true) {
            val status = repository.getJobStatus(jobId).getOrThrow()
            when (status.state) {
                JobState.SUCCESS -> return
                JobState.FAILED, JobState.ABORTED ->
                    throw DashboardApiException(status.error ?: "The update failed.")
                JobState.WAITING, JobState.RUNNING, JobState.UNKNOWN -> delay(JOB_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun loadSystemInfo() {
        _uiState.update { it.copy(isLoadingSystemInfo = true, systemInfoError = null) }
        viewModelScope.launch {
            repository.getSystemInfo()
                .onSuccess { info ->
                    _uiState.update { it.copy(systemInfo = info, isLoadingSystemInfo = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingSystemInfo = false, systemInfoError = error.message)
                    }
                }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshLiveData()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun refreshLiveData() = coroutineScope {
        val metricsDeferred = async { repository.getLiveMetrics() }
        val poolsDeferred = async { repository.getPools() }
        val metricsResult = metricsDeferred.await()
        val poolsResult = poolsDeferred.await()

        _uiState.update { state ->
            state
                .let { s ->
                    metricsResult.fold(
                        onSuccess = { s.copy(liveMetrics = it, liveMetricsError = null) },
                        onFailure = { s.copy(liveMetricsError = it.message) },
                    )
                }
                .let { s ->
                    poolsResult.fold(
                        onSuccess = { s.copy(pools = it, isLoadingPools = false, poolsError = null) },
                        onFailure = { s.copy(isLoadingPools = false, poolsError = it.message) },
                    )
                }
                .let { s ->
                    if (metricsResult.isSuccess || poolsResult.isSuccess) {
                        s.copy(lastUpdatedAtMillis = System.currentTimeMillis())
                    } else {
                        s
                    }
                }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2000L
        const val JOB_POLL_INTERVAL_MILLIS = 2000L
    }
}

class DashboardViewModelFactory(private val app: TrueNasApplication) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return DashboardViewModel(app.dashboardRepository) as T
    }
}
