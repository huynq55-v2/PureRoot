package com.quanghuy2309.pureroot

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

enum class AppFilterType { USER_APPS, SYSTEM_APPS, ALL_APPS }

data class AppListUiState(
    val isLoading: Boolean = true,
    val apps: List<AppInfo> = emptyList(),
    val currentFilterType: AppFilterType = AppFilterType.USER_APPS,
    val searchQuery: String = ""
)

// Cập nhật AppActionResult cho Disable/Enable
sealed class AppActionResult {
    data class DisableSuccess(val appName: String, val packageName: String) : AppActionResult()
    data class DisableFailure(val appName: String, val reason: String) : AppActionResult()
    data class EnableSuccess(val appName: String, val packageName: String) : AppActionResult()
    data class EnableFailure(val appName: String, val reason: String) : AppActionResult()
    data class OperationRequiresRoot(val operationName: String) : AppActionResult()
    data class GenericError(val message: String) : AppActionResult() // Giữ lại GenericError
}

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private val _actionResult = MutableSharedFlow<AppActionResult>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val actionResult: SharedFlow<AppActionResult> = _actionResult.asSharedFlow()

    private var allInstalledApps: List<AppInfo> = emptyList()

    init {
        loadAllApps()
    }

    private fun loadAllApps() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            allInstalledApps = fetchInstalledApps()
            filterAndDisplayApps()
        }
    }

    private suspend fun fetchInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        val packages = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS) // Thêm MATCH_DISABLED_COMPONENTS
        } catch (e: Exception) {
            Log.e("ViewModelFetch", "Error getting installed applications", e)
            _actionResult.tryEmit(AppActionResult.GenericError("Could not get app list: ${e.message}"))
            emptyList<ApplicationInfo>()
        }

        val appList = mutableListOf<AppInfo>()
        val myPackageName = getApplication<Application>().packageName

        Log.i("ViewModelFetch", "Total packages from PM: ${packages.size}")

        for (appPkgInfo in packages) {
            val currentPackageName = appPkgInfo.packageName
            try {
                val appName = pm.getApplicationLabel(appPkgInfo).toString()
                // Tạo icon placeholder nếu getApplicationIcon lỗi, hoặc xử lý lỗi tốt hơn
                val icon = try { pm.getApplicationIcon(appPkgInfo) } catch (e: Exception) { null }
                val sourceDir = appPkgInfo.sourceDir ?: "N/A" // Xử lý null cho sourceDir
                val flags = appPkgInfo.flags
                val isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isEnabled = appPkgInfo.enabled // Lấy trạng thái enabled

                if (currentPackageName == myPackageName && _uiState.value.currentFilterType != AppFilterType.ALL_APPS) {
                    // Log.d("ViewModelFetch", "Skipping self: $appName for non-ALL filter")
                    // continue // Có thể bỏ qua chính app này nếu không phải đang xem ALL_APPS
                }

                appList.add(AppInfo(appName, currentPackageName, icon, isSystemApp, sourceDir, isEnabled))
            } catch (e: Exception) {
                Log.e("ViewModelFetch_ERROR", "Error processing app $currentPackageName", e)
            }
        }
        Log.i("ViewModelFetch", "Total apps processed into AppInfo list: ${appList.size}")
        return@withContext appList.sortedBy { it.appName.lowercase() }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, isLoading = true) }
        filterAndDisplayApps()
    }

    fun setAppFilterType(filterType: AppFilterType) {
        _uiState.update { it.copy(currentFilterType = filterType, isLoading = true) }
        filterAndDisplayApps()
    }

    private fun filterAndDisplayApps() {
        viewModelScope.launch(Dispatchers.Default) {
            val currentState = _uiState.value
            Log.d("ViewModelFilter", "Filtering for type: ${currentState.currentFilterType}, query: '${currentState.searchQuery}'")

            val filteredList = allInstalledApps.filter { app ->
                val typeMatch = when (currentState.currentFilterType) {
                    AppFilterType.USER_APPS -> !app.isSystemApp
                    AppFilterType.SYSTEM_APPS -> app.isSystemApp
                    AppFilterType.ALL_APPS -> true
                }
                val queryMatch = if (currentState.searchQuery.isBlank()) {
                    true
                } else {
                    app.appName.contains(currentState.searchQuery, ignoreCase = true) ||
                            app.packageName.contains(currentState.searchQuery, ignoreCase = true)
                }
                typeMatch && queryMatch
            }
            Log.d("ViewModelFilter", "Filtered apps count: ${filteredList.size} for type ${currentState.currentFilterType}")
            _uiState.update { it.copy(apps = filteredList, isLoading = false) }
        }
    }

    fun refreshAppList() {
        loadAllApps()
    }

    // Chức năng Disable App
    fun disableApp(appInfo: AppInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = performPmCommand("disable", appInfo)
            _actionResult.emit(
                if (result.first) AppActionResult.DisableSuccess(appInfo.appName, appInfo.packageName)
                else AppActionResult.DisableFailure(appInfo.appName, result.second)
            )
            if (result.first) refreshAppList() else _uiState.update { it.copy(isLoading = false) }
        }
    }

    // Chức năng Enable App
    fun enableApp(appInfo: AppInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = performPmCommand("enable", appInfo)
            _actionResult.emit(
                if (result.first) AppActionResult.EnableSuccess(appInfo.appName, appInfo.packageName)
                else AppActionResult.EnableFailure(appInfo.appName, result.second)
            )
            if (result.first) refreshAppList() else _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun performPmCommand(action: String, appInfo: AppInfo): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val logTag = "ViewModelPmCommand"
        var process: Process? = null
        var os: DataOutputStream? = null
        val command = "pm $action '${appInfo.packageName}'\n"
        Log.d(logTag, "Executing PM command: $command")

        try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)

            os.writeBytes(command)
            os.writeBytes("exit\n")
            os.flush()
            os.close() // Đóng OutputStream

            val exitValue = process.waitFor()
            Log.d(logTag, "$action command exit value: $exitValue for ${appInfo.packageName}")

            val stdErr = process.errorStream.bufferedReader().readText()
            val stdOut = process.inputStream.bufferedReader().readText() // Đọc stdout để kiểm tra output "Success"

            if (stdErr.isNotBlank()) Log.e(logTag, "Stderr for $action ${appInfo.packageName}: $stdErr")
            if (stdOut.isNotBlank()) Log.d(logTag, "Stdout for $action ${appInfo.packageName}: $stdOut")


            // pm enable/disable thường output "Package <pkg> new state: enabled/disabled" hoặc tương tự
            // Hoặc không output gì nếu thành công (exit code 0)
            // Kiểm tra exit code và trạng thái package sau đó
            if (exitValue == 0) {
                // Kiểm tra lại trạng thái package
                val pm = getApplication<Application>().packageManager
                try {
                    val updatedAppInfo = pm.getApplicationInfo(appInfo.packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                    val expectedState = action == "enable"
                    if (updatedAppInfo.enabled == expectedState) {
                        Log.i(logTag, "Package ${appInfo.packageName} successfully ${action}d.")
                        return@withContext true to "Successfully ${action}d."
                    } else {
                        Log.w(logTag, "Package ${appInfo.packageName} state did not change to $action. Current state: ${updatedAppInfo.enabled}")
                        return@withContext false to "State did not change as expected. Stderr: $stdErr".trim()
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(logTag, "Package ${appInfo.packageName} not found after $action command.", e)
                    return@withContext false to "Package not found after command. Stderr: $stdErr".trim() // Có thể là thành công nếu là disable
                }
            } else {
                val reason = "Command '$action' failed (code $exitValue). Stderr: $stdErr".trim()
                Log.e(logTag, reason)
                return@withContext false to reason
            }
        } catch (e: Exception) {
            Log.e(logTag, "Exception during $action for ${appInfo.packageName}", e)
            return@withContext false to "Exception: ${e.message}"
        } finally {
            os?.closeQuietly()
            process?.destroyQuietly()
        }
    }

    // Hàm testSimpleSuCommand() đã được xóa
}

// Extension functions để đóng resource an toàn
fun DataOutputStream.closeQuietly() {
    try { this.close() } catch (e: IOException) { /* ignore */ }
}
fun Process.destroyQuietly() {
    try { this.destroy() } catch (e: Exception) { /* ignore */ }
}