package com.quanghuy2309.pureroot

import android.app.Activity // Added for ActivityResultLauncher
import android.content.Intent // Added for ACTION_CREATE_DOCUMENT
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri // Added for Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher // Added for ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts // Added for ActivityResultContracts
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
import java.io.IOException // Added for IOException
import java.text.SimpleDateFormat // Added for Date formatting
import java.util.Date // Added for Date
import java.util.Locale // Added for Locale

class AppListActivity : BaseRootActivity() {

    companion object {
        private const val TAG = "AppListActivity"
    }

    private val viewModel: AppListViewModel by viewModels()
    private lateinit var appListAdapter: AppListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchView: SearchView
    private lateinit var switchAppType: SwitchMaterial
    private lateinit var progressBar: ProgressBar
    private lateinit var btnExportAppList: Button // MODIFICATION: Added export button

    // MODIFICATION: Launcher for creating the export file
    private lateinit var exportFileLauncher: ActivityResultLauncher<Intent>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        title = getString(R.string.app_list_title)
        Log.d(TAG, "onCreate - AppListActivity started.")

        // MODIFICATION: Initialize the export file launcher
        exportFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val dataToExport = viewModel.getAppListExportData()
                    writeTextToUri(uri, dataToExport)
                }
            }
        }

        setupUI()
        observeViewModelState()
        observeActionResults()
    }

    private fun setupUI() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        searchView = findViewById(R.id.searchViewApps)
        switchAppType = findViewById(R.id.switchAppType)
        progressBar = findViewById(R.id.progressBarLoading)
        btnExportAppList = findViewById(R.id.btnExportAppList) // MODIFICATION: Initialize export button

        appListAdapter = AppListAdapter { appInfo ->
            Log.d(TAG, "App clicked: ${appInfo.appName}, Enabled: ${appInfo.isEnabled}")
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

        switchAppType.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAppFilterType(if (isChecked) AppFilterType.SYSTEM_APPS else AppFilterType.USER_APPS)
        }

        // MODIFICATION: Set onClickListener for the export button
        btnExportAppList.setOnClickListener {
            initiateExportAppList()
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
    }

    override fun onRootReacquired() {
        super.onRootReacquired()
        Log.d(TAG, "Root was re-acquired, refreshing app list.")
        viewModel.refreshAppList()
    }

    private fun confirmAction(appInfo: AppInfo, actionNameDisplay: String, actionToPerform: () -> Unit) {
        var warningMessage = ""
        if (appInfo.isSystemApp) {
            warningMessage = "\n\n${getString(R.string.warning_modify_system_app, actionNameDisplay.lowercase())}"
            if (appInfo.packageName.startsWith("com.android") || appInfo.packageName.startsWith("com.google.android.inputmethod")) {
                warningMessage += "\n\n${getString(R.string.warning_modify_core_android, actionNameDisplay.lowercase())}"
            } else if (appInfo.packageName.startsWith("com.google.android")) {
                warningMessage += "\n\n${getString(R.string.warning_modify_google_app, actionNameDisplay.lowercase())}"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.dialog_confirm_title)} $actionNameDisplay")
            .setMessage("${getString(R.string.dialog_confirm_action_message, actionNameDisplay.lowercase(), appInfo.appName, appInfo.packageName)}$warningMessage")
            .setPositiveButton(actionNameDisplay) { _, _ ->
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

    // START MODIFICATION: Methods for exporting app list
    private fun initiateExportAppList() {
        val appName = getString(R.string.app_name).replace(" ", "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${appName}_AppStatus_${timestamp}.csv"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv" // MIME type for CSV
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        exportFileLauncher.launch(intent)
    }

    private fun writeTextToUri(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.bufferedWriter().use { writer ->
                    writer.write(text)
                }
                Toast.makeText(this, "App list exported successfully to Documents folder.", Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error exporting app list", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // END MODIFICATION
}