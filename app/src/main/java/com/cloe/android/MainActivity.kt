package com.cloe.android

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etHost = findViewById<EditText>(R.id.et_host)
        val btnConnect = findViewById<Button>(R.id.btn_connect)
        val btnDisconnect = findViewById<Button>(R.id.btn_disconnect)
        val btnPermission = findViewById<Button>(R.id.btn_permission)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        // Default: Tailscale IP placeholder
        etHost.setText("100.x.x.x")

        // Check overlay permission
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
            btnDisconnect.isEnabled = serviceRunning

            tvStatus.text = when {
                !hasPermission -> "⚠ 请先授予悬浮窗权限"
                serviceRunning -> "✅ 已连接，Cloe 正在桌面上"
                else -> "未连接"
            }
        }

        btnPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
        }

        btnConnect.setOnClickListener {
            val host = etHost.text.toString().trim()
            if (host.isBlank() || host == "100.x.x.x") {
                Toast.makeText(this, "请输入 PC 的 Tailscale IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, CloeService::class.java)
            intent.putExtra("host", host)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        btnDisconnect.setOnClickListener {
            stopService(Intent(this, CloeService::class.java))
        }

        updateUI()
        // Poll status while activity is visible
        findViewById<TextView>(R.id.tv_status).postDelayed(object : Runnable {
            override fun run() {
                updateUI()
                findViewById<TextView>(R.id.tv_status).postDelayed(this, 500)
            }
        }, 500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Re-check permission status
    }
}
