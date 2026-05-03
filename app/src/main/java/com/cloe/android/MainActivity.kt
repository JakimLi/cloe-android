package com.cloe.android

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etHost = findViewById<EditText>(R.id.et_host)
        val btnConnect = findViewById<Button>(R.id.btn_connect)
        val btnPullActions = findViewById<Button>(R.id.btn_pull_actions)
        val btnDisconnect = findViewById<Button>(R.id.btn_disconnect)
        val btnPermission = findViewById<Button>(R.id.btn_permission)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        etHost.setText("100.x.x.x")

        fun hasOverlayPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else true
        }

        fun updateUI() {
            val serviceRunning = CloeService.isRunning
            val hasPermission = hasOverlayPermission()

            btnPermission.isEnabled = !hasPermission
            btnConnect.isEnabled = hasPermission && !serviceRunning
            btnPullActions.isEnabled = hasPermission
            btnDisconnect.isEnabled = serviceRunning

            tvStatus.text = when {
                !hasPermission -> "⚠ 请先授予悬浮窗权限"
                serviceRunning -> "✅ 已连接，Cloe 正在桌面上"
                else -> "未连接"
            }
        }

        btnPermission.setOnClickListener {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
        }

        btnConnect.setOnClickListener {
            val h = etHost.text.toString().trim()
            if (h.isBlank() || h == "100.x.x.x") {
                Toast.makeText(this, "请输入 PC 的 Tailscale IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = android.content.Intent(this, CloeService::class.java)
            intent.putExtra("host", h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        btnPullActions.setOnClickListener {
            val h = etHost.text.toString().trim()
            if (h.isBlank() || h == "100.x.x.x") {
                Toast.makeText(this, "请输入 PC 的 Tailscale IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnPullActions.isEnabled = false
            lifecycleScope.launch {
                val result = ActionSync.pullFromDesktop(h, this@MainActivity)
                btnPullActions.isEnabled = true
                result.fold(
                    onSuccess = { n ->
                        Toast.makeText(
                            this@MainActivity,
                            "已全量拉取 $n 个 GIF（所有套装）",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (CloeService.isRunning) {
                            val reload = android.content.Intent(this@MainActivity, CloeService::class.java)
                            reload.putExtra("host", h)
                            reload.putExtra("reload_actions", true)
                            startService(reload)
                        }
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            this@MainActivity,
                            e.message ?: "拉取失败",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }

        btnDisconnect.setOnClickListener {
            stopService(android.content.Intent(this, CloeService::class.java))
        }

        updateUI()
        findViewById<TextView>(R.id.tv_status).postDelayed(object : Runnable {
            override fun run() {
                updateUI()
                findViewById<TextView>(R.id.tv_status).postDelayed(this, 500)
            }
        }, 500)
    }
}
