package com.quanghuy2309.pureroot // Thay bằng package name của bạn

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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PureRootMain" // Đổi tên TAG để phân biệt với BaseRootActivity
    }

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Launcher này dành riêng cho MainActivity khi nó khởi chạy PermissionDeniedActivity lúc ban đầu
    private lateinit var initialPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nếu MainActivity có UI riêng (ví dụ màn hình splash), setContentView ở đây
        // setContentView(R.layout.activity_main_splash)
        // và trong layout đó có thể có TextView với text="@string/checking_root_status"

        Log.d(TAG, "onCreate: Initializing initialPermissionLauncher and checking root for the first time.")

        initialPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Đây là nơi xử lý kết quả KHI PermissionDeniedActivity được khởi chạy TỪ MainActivity LÚC BAN ĐẦU
            if (result.resultCode == Activity.RESULT_OK) {
                // Trường hợp này thực tế không nên xảy ra với logic của PermissionDeniedActivity
                // vì nó chỉ trả về RESULT_CANCELED khi người dùng Back hoặc Exit.
                // Tuy nhiên, để phòng hờ:
                Log.d(TAG, "Root granted after returning from PermissionDeniedActivity (launched by MainActivity - unexpected RESULT_OK).")
                Toast.makeText(this, getString(R.string.root_granted_initial), Toast.LENGTH_SHORT).show()
                navigateToAppList()
            } else { // RESULT_CANCELED hoặc các mã lỗi khác
                // ĐOẠN CODE BẠN HỎI NẰM Ở ĐÂY:
                // Xảy ra khi người dùng nhấn Back từ PermissionDeniedActivity
                // hoặc nhấn nút "Exit App" trong PermissionDeniedActivity,
                // khi PermissionDeniedActivity được khởi chạy lần đầu bởi MainActivity.
                Log.w(TAG, "Returned from PermissionDeniedActivity (launched by MainActivity) with RESULT_CANCELED. Exiting app.")
                Toast.makeText(this, getString(R.string.exiting_no_root), Toast.LENGTH_LONG).show()
                finishAffinity() // Đóng toàn bộ ứng dụng
            }
        }

        // Thực hiện kiểm tra root lần đầu khi ứng dụng khởi chạy
        checkInitialRootAccess()
    }

    private fun checkInitialRootAccess() {
        Toast.makeText(this, getString(R.string.checking_root_status), Toast.LENGTH_SHORT).show()

        activityScope.launch { // Chạy trên Dispatchers.Main vì Toast và điều hướng cần ở Main Thread
            val hasRoot = requestInitialRootPrivileges() // Sử dụng hàm riêng cho lần request đầu
            if (hasRoot) {
                Log.d(TAG, "Root access granted initially by MainActivity.")
                navigateToAppList()
            } else {
                Log.d(TAG, "Root access denied or not available initially (MainActivity).")
                // Cần chạy checkSuExists trên IO thread
                val suExists = withContext(Dispatchers.IO) { checkSuExists() }
                navigateToPermissionDeniedInitially(suExists)
            }
        }
    }

    // Hàm này yêu cầu quyền root LẦN ĐẦU, có thể hiện dialog của Magisk
    private suspend fun requestInitialRootPrivileges(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        var os: DataOutputStream? = null
        try {
            Log.d(TAG, "Requesting initial SU privileges...")
            process = Runtime.getRuntime().exec("su") // Lệnh 'su' sẽ trigger dialog
            os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n") // Gửi lệnh để xác nhận shell
            os.writeBytes("exit\n")
            os.flush()
            val exitValue = process.waitFor()
            Log.d(TAG, "Initial SU process exited with value: $exitValue")
            return@withContext exitValue == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error during initial root request: ${e.message}", e)
            return@withContext false
        } finally {
            try {
                os?.close()
                process?.destroy()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing initial SU resources: ${e.message}", e)
            }
        }
    }

    // Hàm checkSuExists giữ nguyên như trong BaseRootActivity hoặc bạn có thể tạo một file Utils chung
    private suspend fun checkSuExists(): Boolean = withContext(Dispatchers.IO) {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) {
                Log.d(TAG, "SU binary found at: $path (from MainActivity)")
                return@withContext true
            }
        }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitValue = process.waitFor()
            process.destroy()
            val suFound = exitValue == 0
            Log.d(TAG, "SU 'which' command exit value: $exitValue (Found: $suFound) (from MainActivity)")
            return@withContext suFound
        } catch (e: Exception) {
            Log.e(TAG, "Error checking 'which su': ${e.message} (from MainActivity)", e)
            return@withContext false
        }
    }

    private fun navigateToAppList() {
        Log.d(TAG, "Navigating to AppListActivity from MainActivity.")
        startActivity(Intent(this, AppListActivity::class.java))
        finish() // Đóng MainActivity để không quay lại được
    }

    private fun navigateToPermissionDeniedInitially(suExistsAndWasDenied: Boolean) {
        Log.d(TAG, "Navigating to PermissionDeniedActivity from MainActivity. suExistsAndWasDenied: $suExistsAndWasDenied")
        val intent = Intent(this, PermissionDeniedActivity::class.java).apply {
            putExtra("WAS_DENIED_OR_NO_SU", suExistsAndWasDenied)
        }
        initialPermissionLauncher.launch(intent)
        // Không finish() MainActivity ở đây, chờ kết quả từ PermissionDeniedActivity
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        Log.d(TAG, "MainActivity onDestroy.")
    }
}