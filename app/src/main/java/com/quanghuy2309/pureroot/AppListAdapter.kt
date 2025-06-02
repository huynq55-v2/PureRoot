package com.quanghuy2309.pureroot // Đảm bảo đúng package

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater // <<<< IMPORT
import android.view.View          // <<<< IMPORT
import android.view.ViewGroup     // <<<< IMPORT
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

// onItemClicked: lambda được gọi khi một item được nhấn
class AppListAdapter(private val onItemClicked: (AppInfo) -> Unit) :
    ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

    // ViewHolder giữ các view của một item
    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val appName: TextView = itemView.findViewById(R.id.tvAppName)
        private val packageName: TextView = itemView.findViewById(R.id.tvPackageName)

        fun bind(appInfo: AppInfo) {
            appName.text = appInfo.appName
            packageName.text = appInfo.packageName
            appIcon.setImageDrawable(appInfo.icon)

            // Cập nhật UI dựa trên trạng thái isEnabled
            if (!appInfo.isEnabled) {
                appName.paintFlags = appName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                appName.setTextColor(Color.GRAY)
                packageName.setTextColor(Color.GRAY)
                appIcon.alpha = 0.5f
            } else {
                appName.paintFlags = appName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                // Lấy màu từ theme của context thay vì hardcode
                // Điều này cần Context, có thể lấy từ itemView.context
                val primaryTextColor = itemView.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, Color.BLACK)
                val secondaryTextColor = itemView.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, Color.DKGRAY)
                appName.setTextColor(primaryTextColor)
                packageName.setTextColor(secondaryTextColor)
                appIcon.alpha = 1.0f
            }
        }
    }

    // Tạo ViewHolder mới (được gọi bởi layout manager)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context) // Sử dụng parent.context
            .inflate(R.layout.item_app_info, parent, false)
        return AppViewHolder(view)
    }

    // Gắn dữ liệu vào ViewHolder (được gọi bởi layout manager)
    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = getItem(position)
        holder.bind(appInfo)
        // Gán sự kiện click cho toàn bộ item view
        holder.itemView.setOnClickListener {
            onItemClicked(appInfo)
        }
    }

    // DiffUtil Callback để RecyclerView biết cách cập nhật list hiệu quả
    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem.packageName == newItem.packageName // So sánh bằng packageName là đủ để xác định item
        }

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem == newItem // Data class tự implement equals() để so sánh nội dung
        }
    }
}