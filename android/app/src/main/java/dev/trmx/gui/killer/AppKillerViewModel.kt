package dev.trmx.gui.killer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class KillerFilterTab {
    ALL,
    SAFE,
    OTHER,
    PROTECTED,
}

class AppKillerViewModel(application: Application) : AndroidViewModel(application) {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterTab = MutableStateFlow(KillerFilterTab.SAFE)
    val filterTab: StateFlow<KillerFilterTab> = _filterTab.asStateFlow()

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isKilling = MutableStateFlow(false)
    val isKilling: StateFlow<Boolean> = _isKilling.asStateFlow()

    private val _progress = MutableStateFlow<KillProgress?>(null)
    val progress: StateFlow<KillProgress?> = _progress.asStateFlow()

    private val _lastReport = MutableStateFlow<KillReport?>(null)
    val lastReport: StateFlow<KillReport?> = _lastReport.asStateFlow()

    private val _hasConfirmedInSession = MutableStateFlow(false)
    val hasConfirmedInSession: StateFlow<Boolean> = _hasConfirmedInSession.asStateFlow()

    private var hasInitializedSelection = false
    private var killJob: Job? = null

    init {
        checkAccessibility()
        loadApps()
    }

    fun checkAccessibility() {
        val enabled = AppKillerEngine.isAccessibilityEnabled(getApplication())
        _isAccessibilityEnabled.value = enabled
    }

    fun openAccessibilitySettings() {
        AppKillerEngine.openAccessibilitySettings(getApplication())
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = AppKillerEngine.loadInstalledApps(getApplication())
            _apps.value = list

            // If selection hasn't been initialized yet, initialize suggested SAFE apps
            _selectedPackages.update { current ->
                if (!hasInitializedSelection) {
                    hasInitializedSelection = true
                    list.filter { it.category == AppCategory.SAFE }
                        .map { it.packageName }
                        .toSet()
                } else {
                    // Retain existing selections, excluding any core or uninstalled packages
                    val validSet = list.map { it.packageName }.toSet()
                    current.filter { !AppCategories.isCore(it) && validSet.contains(it) }.toSet()
                }
            }
        }
    }

    fun toggleSelection(packageName: String) {
        if (AppCategories.isCore(packageName)) return // Never selectable
        _selectedPackages.update { current ->
            if (current.contains(packageName)) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }

    fun selectAllSafe() {
        val safePackages = _apps.value
            .filter { it.category == AppCategory.SAFE }
            .map { it.packageName }
            .toSet()
        _selectedPackages.update { it + safePackages }
    }

    fun deselectAll() {
        _selectedPackages.value = emptySet()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilterTab(tab: KillerFilterTab) {
        _filterTab.value = tab
    }

    fun markSessionConfirmed() {
        _hasConfirmedInSession.value = true
    }

    fun startKill(context: Context) {
        if (_isKilling.value) return
        val targets = _selectedPackages.value.toList()
        if (targets.isEmpty()) return

        val appLabels = _apps.value.associate { it.packageName to it.label }

        _isKilling.value = true
        _progress.value = null
        _lastReport.value = null

        killJob = viewModelScope.launch(Dispatchers.IO) {
            val report = AppKillerEngine.executeKillList(
                context = context,
                packagesToKill = targets,
                appLabels = appLabels,
                onProgress = { p ->
                    _progress.value = p
                },
            )

            withContext(Dispatchers.Main) {
                _lastReport.value = report
                _isKilling.value = false
                _progress.value = null
                checkAccessibility()
            }
        }
    }

    fun cancelKill() {
        killJob?.cancel()
        killJob = null
        _isKilling.value = false
        _progress.value = null
    }

    fun clearReport() {
        _lastReport.value = null
    }
}
