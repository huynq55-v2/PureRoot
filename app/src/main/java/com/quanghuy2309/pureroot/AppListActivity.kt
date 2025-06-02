package com.quanghuy2309.pureroot

import android.graphics.Color // Import Color
import android.graphics.Paint // Import Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button // Xóa import này nếu nút Test SU bị xóa
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class AppListActivity : BaseRootActivity() {

    companion object {
        private const val TAG = "AppListActivity"
    }

    private val viewModel: AppListViewModel by viewModels()
    private lateinit var appListAdapter: AppListAdapter // Sẽ được cập nhật
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchView: SearchView
    private lateinit var switchAppType: SwitchMaterial
    private lateinit var progressBar: ProgressBar
    // private lateinit var btnTestSu: Button // Xóa nút Test SU

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        title = getString(R.string.app_list_title)
        Log.d(TAG, "onCreate - AppListActivity started.")
        setupUI()
        observeViewModelState()
        observeActionResults()
    }

    private fun setupUI() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        searchView = findViewById(R.id.searchViewApps)
        switchAppType = findViewById(R.id.switchAppType)
        progressBar = findViewById(R.id.progressBarLoading)

        // Khởi tạo AppListAdapter (nó đã được import từ file riêng)
        appListAdapter = AppListAdapter { appInfo -> // Lambda này giờ sẽ trực tiếp gọi confirm
            Log.d(TAG, "App clicked: ${appInfo.appName}, Enabled: ${appInfo.isEnabled}")
            // Thay vì hiển thị menu, gọi thẳng confirmAction dựa trên trạng thái hiện tại
            if (appInfo.isEnabled) {
                confirmAction(appInfo, getString(R.string.action_disable_app_short)) { viewModel.disableApp(appInfo) }
            } else {
                confirmAction(appInfo, getString(R.string.action_enable_app_short)) { viewModel.enableApp(appInfo) }
            }
        }

        recyclerView.apply {
            adapter = appListAdapter
            layoutManager = LinearLayoutManager(this@AppListActivity)
            addItemDecoration(DividerItemDecoration(this@AppListActivity, DividerItemDecoration.VERTICAL))
        }

        searchView.queryHint = getString(R.string.search_apps_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setSearchQuery(query.orEmpty())
                searchView.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }
        })

        val initialFilterIsSystem = viewModel.uiState.value.currentFilterType == AppFilterType.SYSTEM_APPS
        switchAppType.isChecked = initialFilterIsSystem
        // updateSwitchText(initialFilterIsSystem) // Sẽ được gọi qua observeViewModelState

        switchAppType.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAppFilterType(if (isChecked) AppFilterType.SYSTEM_APPS else AppFilterType.USER_APPS)
        }
    }

    private fun updateSwitchText(isSystemMode: Boolean) {
        switchAppType.text = if (isSystemMode) getString(R.string.switch_text_system) else getString(R.string.switch_text_user)
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    Log.d(TAG, "UI State updated: isLoading=${state.isLoading}, appsCount=${state.apps.size}, filter=${state.currentFilterType}, query='${state.searchQuery}'")
                    progressBar.visibility = if (state.isLoading && state.apps.isEmpty()) View.VISIBLE else View.GONE
                    appListAdapter.submitList(state.apps)

                    val isSystemCurrently = state.currentFilterType == AppFilterType.SYSTEM_APPS
                    if (switchAppType.isChecked != isSystemCurrently) {
                        switchAppType.isChecked = isSystemCurrently
                    }
                    updateSwitchText(isSystemCurrently)
                }
            }
        }
    }

    private fun observeActionResults() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.actionResult.collect { result ->
                    when (result) {
                        is AppActionResult.DisableSuccess -> {
                            Toast.makeText(this@AppListActivity, "Disabled ${result.appName}", Toast.LENGTH_SHORT).show()
                        }
                        is AppActionResult.DisableFailure -> {
                            showErrorDialog("Disable Failed: ${result.appName}", result.reason)
                        }
                        is AppActionResult.EnableSuccess -> {
                            Toast.makeText(this@AppListActivity, "Enabled ${result.appName}", Toast.LENGTH_SHORT).show()
                        }
                        is AppActionResult.EnableFailure -> {
                            showErrorDialog("Enable Failed: ${result.appName}", result.reason)
                        }
                        is AppActionResult.OperationRequiresRoot -> {
                            showErrorDialog("Root Required", "Operation '${result.operationName}' requires root access.")
                        }
                        is AppActionResult.GenericError -> {
                            showErrorDialog("Error", result.message)
                        }
                    }
                }
            }
        }
    }

    override fun onRootVerified() {
        super.onRootVerified()
        Log.d(TAG, "Root access verified. AppListActivity can operate.")
        // viewModel.refreshAppList() // Có thể refresh nếu cần
    }

    override fun onRootReacquired() {
        super.onRootReacquired()
        Log.d(TAG, "Root was re-acquired, refreshing app list.")
        viewModel.refreshAppList()
    }

    private fun showAppActionDialog(appInfo: AppInfo) {
        val actions = mutableListOf<String>()

        if (appInfo.isEnabled) {
            actions.add(getString(R.string.action_disable_app)) // "Disable App"
        } else {
            actions.add(getString(R.string.action_enable_app)) // "Enable App"
        }
        // Bạn có thể thêm các action khác như "App Info"
        // actions.add(getString(R.string.action_app_info))

        AlertDialog.Builder(this)
            .setTitle("${appInfo.appName}\n(${appInfo.packageName})")
            .setItems(actions.toTypedArray()) { dialog, which ->
                when (actions[which]) {
                    getString(R.string.action_disable_app) -> {
                        confirmAction(appInfo, "Disable") { viewModel.disableApp(appInfo) }
                    }
                    getString(R.string.action_enable_app) -> {
                        confirmAction(appInfo, "Enable") { viewModel.enableApp(appInfo) }
                    }
                    // getString(R.string.action_app_info) -> { /* Mở trang thông tin app hệ thống */ }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun confirmAction(appInfo: AppInfo, actionNameDisplay: String, actionToPerform: () -> Unit) {
        // actionNameDisplay là text hiển thị cho hành động (ví dụ: "Disable", "Enable")
        var warningMessage = ""
        if (appInfo.isSystemApp) {
            warningMessage = "\n\n${getString(R.string.warning_modify_system_app, actionNameDisplay.lowercase())}"
            // ... (các cảnh báo khác cho core android / google app giữ nguyên) ...
            if (appInfo.packageName.startsWith("com.android") || appInfo.packageName.startsWith("com.google.android.inputmethod")) {
                warningMessage += "\n\n${getString(R.string.warning_modify_core_android, actionNameDisplay.lowercase())}"
            } else if (appInfo.packageName.startsWith("com.google.android")) {
                warningMessage += "\n\n${getString(R.string.warning_modify_google_app, actionNameDisplay.lowercase())}"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.dialog_confirm_title)} $actionNameDisplay") // "Confirm Disable" hoặc "Confirm Enable"
            .setMessage("${getString(R.string.dialog_confirm_action_message, actionNameDisplay.lowercase(), appInfo.appName, appInfo.packageName)}$warningMessage")
            .setPositiveButton(actionNameDisplay) { _, _ -> // Nút Positive sẽ là "Disable" hoặc "Enable"
                actionToPerform()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun showErrorDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
}
