package com.quanghuy2309.pureroot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

abstract class BaseRootActivity : AppCompatActivity() {

    companion object {
        private const val TAG_BASE_ROOT = "BaseRootActivity"
        // Một cờ để ngăn việc hiển thị PermissionDeniedActivity liên tục nếu người dùng
        // cố tình không cấp quyền và cứ quay lại app.
        private var permissionDeniedScreenShownRecently = false
        private const val PERMISSION_DENIED_COOLDOWN_MS = 5000L // 5 giây cooldown
    }

    protected val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var permissionResultLauncher: ActivityResultLauncher<Intent> // Đổi tên cho rõ ràng hơn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            permissionDeniedScreenShownRecently = false // Reset cờ khi quay lại

            if (result.resultCode == Activity.RESULT_OK) {
                // Quyền đã được cấp lại khi người dùng ở PermissionDeniedActivity
                Log.d(TAG_BASE_ROOT, "Root re-acquired (RESULT_OK from PermissionDeniedActivity).")
                onRootReacquired() // Gọi hàm để Activity con có thể làm mới
                // checkRootAccessAndReact() // Không cần gọi lại ở đây nữa vì onResume của Activity này sẽ tự động chạy
                // và onRootReacquired đã được gọi.
                // Việc gọi lại có thể gây thừa thãi.
                // Quan trọng là onRootReacquired() phải làm mới được UI.
            } else { // RESULT_CANCELED
                Log.w(TAG_BASE_ROOT, "Returned from PermissionDeniedActivity with RESULT_CANCELED.")
                // Không tự động thoát app ở đây. onResume của BaseRootActivity sẽ kiểm tra lại.
                // Nếu người dùng vẫn không cấp quyền, onResume sẽ lại điều hướng (với cooldown)
                // hoặc hiển thị Toast "exiting_no_root_soon".
                // Việc này cho phép người dùng có cơ hội thoát app bằng nút Back
                // thay vì app tự động đóng ngay lập tức.
                // Nếu sau cooldown mà root vẫn không có, và người dùng tương tác,
                // PermissionDeniedActivity có thể hiện lại.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG_BASE_ROOT, "${this::class.java.simpleName} onResume - Checking root status.")
        // Reset cờ khi activity được resume, trừ khi nó vừa được resume ngay sau khi
        // PermissionDeniedActivity bị đóng (trường hợp này callback của launcher xử lý)
        // permissionDeniedScreenShownRecently = false; // Có thể cân nhắc việc reset ở đây
        checkRootAccessAndReact()
    }

    protected fun checkRootAccessAndReact() {
        activityScope.launch {
            val hasRoot = verifyRootPrivileges()
            if (!hasRoot) {
                Log.w(TAG_BASE_ROOT, "Root access lost or not available in ${this@BaseRootActivity::class.java.simpleName}.")
                if (!permissionDeniedScreenShownRecently) {
                    val suExists = checkSuExists()
                    navigateToPermissionDeniedScreen(suExists)
                } else {
                    Log.w(TAG_BASE_ROOT, "PermissionDeniedActivity was shown recently. Not showing again immediately. User might need to exit.")
                    // Có thể hiển thị một Toast nhẹ nhàng nhắc nhở hoặc không làm gì cả,
                    // để người dùng tự thoát nếu họ không muốn cấp quyền.
                    // Nếu Activity này không thể hoạt động thiếu root, nó sẽ bị kẹt ở trạng thái không dùng được.
                    // Hoặc, sau một thời gian, bạn có thể quyết định thoát app.
                    Toast.makeText(this@BaseRootActivity, getString(R.string.exiting_no_root_soon), Toast.LENGTH_SHORT).show()
                    // Cân nhắc đóng activity hiện tại nếu nó không thể hoạt động
                    // finish()
                }
            } else {
                Log.d(TAG_BASE_ROOT, "Root access verified in ${this@BaseRootActivity::class.java.simpleName}.")
                permissionDeniedScreenShownRecently = false // Reset cờ khi có root
                onRootVerified()
            }
        }
    }

    protected open fun onRootVerified() { /* Mặc định không làm gì */ }
    protected open fun onRootReacquired() { /* Mặc định không làm gì */ }

    private suspend fun verifyRootPrivileges(): Boolean = withContext(Dispatchers.IO) {
        // (Giữ nguyên hàm verifyRootPrivileges)
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su -c id")
            val exitValue = process.waitFor()
            if (exitValue == 0) {
                val output = process.inputStream.bufferedReader().readText()
                return@withContext output.contains("uid=0")
            }
            return@withContext false
        } catch (e: Exception) { return@withContext false
        } finally { process?.destroy() }
    }

    private suspend fun checkSuExists(): Boolean = withContext(Dispatchers.IO) {
        // (Giữ nguyên hàm checkSuExists)
        val paths = arrayOf("/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su")
        for (path in paths) { if (File(path).exists()) return@withContext true }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitValue = process.waitFor()
            process.destroy()
            return@withContext exitValue == 0
        } catch (e: Exception) { return@withContext false }
    }

    private fun navigateToPermissionDeniedScreen(suExistsAndWasDenied: Boolean) {
        if (isFinishing || isDestroyed) {
            Log.w(TAG_BASE_ROOT, "Activity is finishing/destroyed, not navigating.")
            return
        }
        // Kiểm tra nếu Activity hiện tại đã là PermissionDeniedActivity
        if (this::class.java == PermissionDeniedActivity::class.java) {
            Log.w(TAG_BASE_ROOT, "Already in PermissionDeniedActivity. Not navigating again.")
            return
        }

        Log.d(TAG_BASE_ROOT, "Navigating to PermissionDeniedActivity from ${this::class.java.simpleName}. suExistsAndWasDenied: $suExistsAndWasDenied")
        permissionDeniedScreenShownRecently = true // Đặt cờ
        // Đặt lại cờ sau một khoảng thời gian cooldown để cho phép hiển thị lại nếu cần
        activityScope.launch {
            delay(PERMISSION_DENIED_COOLDOWN_MS)
            permissionDeniedScreenShownRecently = false
            Log.d(TAG_BASE_ROOT, "Permission denied cooldown finished.")
        }

        val intent = Intent(this, PermissionDeniedActivity::class.java).apply {
            putExtra("WAS_DENIED_OR_NO_SU", suExistsAndWasDenied)
            // KHÔNG DÙNG FLAG_ACTIVITY_CLEAR_TASK NỮA
            // Thay vào đó, có thể dùng FLAG_ACTIVITY_REORDER_TO_FRONT nếu PermissionDeniedActivity đã tồn tại
            // Hoặc chỉ đơn giản là khởi chạy bình thường.
            // flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT // Thử nghiệm với cờ này
        }
        permissionResultLauncher.launch(intent)
        // Không finish() Activity hiện tại (ví dụ AppListActivity)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        Log.d(TAG_BASE_ROOT, "${this::class.java.simpleName} onDestroy.")
    }
}