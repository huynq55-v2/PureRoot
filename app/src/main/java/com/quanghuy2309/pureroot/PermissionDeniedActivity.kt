package com.quanghuy2309.pureroot

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.* // Import Coroutines
import java.io.DataOutputStream // Import cho verifyRootPrivileges
import java.io.File             // Import cho verifyRootPrivileges
import java.io.IOException      // Import cho verifyRootPrivileges


class PermissionDeniedActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PermissionDeniedAct"
    }

    private lateinit var tvMessage: TextView
    private lateinit var btnAction: Button
    private var suExistsAndWasDenied: Boolean = true

    // Scope cho coroutine kiểm tra root trong onResume
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_denied)
        title = getString(R.string.permission_denied_title)

        tvMessage = findViewById(R.id.tvPermissionDeniedMessage)
        btnAction = findViewById(R.id.btnActionPermissionDenied)

        suExistsAndWasDenied = intent.getBooleanExtra("WAS_DENIED_OR_NO_SU", true)
        val appName = getString(R.string.app_name)

        if (suExistsAndWasDenied) {
            tvMessage.text = getString(R.string.permission_explicitly_denied_message, appName)
            btnAction.text = getString(R.string.exit_app_button)
            btnAction.visibility = View.VISIBLE
            btnAction.setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finishAffinity() // Đóng toàn bộ app khi người dùng chủ động chọn Exit
            }
        } else {
            tvMessage.text = getString(R.string.no_root_detected_message)
            btnAction.text = getString(R.string.exit_app_button)
            btnAction.visibility = View.VISIBLE
            btnAction.setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finishAffinity()
            }
        }

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back button pressed, setting result to CANCELED and finishing.")
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    override fun onResume() {
        super.onResume()
        // Kiểm tra lại quyền root khi Activity này được resume
        // (ví dụ, người dùng quay lại từ Magisk)
        Log.d(TAG, "onResume - Checking root status from PermissionDeniedActivity.")
        activityScope.launch {
            val hasRootNow = verifyRootPrivileges() // Hàm này cần được thêm vào đây
            if (hasRootNow) {
                Log.d(TAG, "Root access detected in onResume. Finishing PermissionDeniedActivity with RESULT_OK.")
                setResult(Activity.RESULT_OK) // Báo hiệu rằng quyền đã được cấp
                finish() // Tự động đóng và quay lại Activity trước đó
            } else {
                Log.d(TAG, "Root access still not detected in onResume.")
            }
        }
    }

    // Hàm này giống hệt hàm trong BaseRootActivity, bạn có thể tạo một file Utils
    // hoặc để nó ở đây nếu PermissionDeniedActivity không kế thừa BaseRootActivity
    private suspend fun verifyRootPrivileges(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su -c id")
            val exitValue = process.waitFor()
            if (exitValue == 0) {
                val output = process.inputStream.bufferedReader().readText()
                return@withContext output.contains("uid=0")
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying root in PermissionDeniedActivity: ${e.message}")
            return@withContext false
        } finally {
            process?.destroy()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel() // Hủy coroutine scope
        Log.d(TAG, "PermissionDeniedActivity onDestroy.")
    }
}