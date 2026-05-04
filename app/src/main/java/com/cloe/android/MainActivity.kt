package com.cloe.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.RadioGroup
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_DESKTOP_HOST = "desktop_host"
        private const val KEY_APP_LOCALE = "app_locale"
    }

    /**
     * Wrap the base context with the user-selected locale. This forces ALL string lookups
     * (Toasts, hint, etc.) to use the chosen language, regardless of system language.
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LOCALE, "system") ?: "system"
        val locale: Locale? = when (tag) {
            "en" -> Locale.forLanguageTag("en-US")
            "zh" -> Locale.forLanguageTag("zh-CN")
            else -> null
        }
        val ctx = if (locale != null) {
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            newBase.createConfigurationContext(config)
        } else newBase
        super.attachBaseContext(ctx)
    }

    private fun loadSavedHost(): String =
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
            .getString(KEY_DESKTOP_HOST, null)?.trim().orEmpty()

    private fun rememberHost(host: String) {
        val h = host.trim()
        val example = getString(R.string.default_host_example)
        if (h.isBlank() || h == example) return
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DESKTOP_HOST, h)
            .apply()
    }

    private fun readLocaleTag(): String =
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE).getString(KEY_APP_LOCALE, "system")
            ?: "system"

    private fun overlayPositionSummaryText(): String {
        val sp = getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean(CloePrefs.KEY_OVERLAY_SAVED, false)) {
            return getString(R.string.overlay_position_not_saved)
        }
        val x = sp.getInt(CloePrefs.KEY_OVERLAY_X, CloePrefs.DEFAULT_OVERLAY_X)
        val y = sp.getInt(CloePrefs.KEY_OVERLAY_Y, CloePrefs.DEFAULT_OVERLAY_Y)
        return getString(R.string.overlay_saved_format, x, y)
    }

    private fun bindLocaleRadios() {
        val rg = findViewById<RadioGroup>(R.id.radio_group_locale)
        rg.setOnCheckedChangeListener(null)
        when (readLocaleTag()) {
            "en" -> rg.check(R.id.radio_locale_en)
            "zh" -> rg.check(R.id.radio_locale_zh)
            else -> rg.check(R.id.radio_locale_system)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val newTag = when (checkedId) {
                R.id.radio_locale_en -> "en"
                R.id.radio_locale_zh -> "zh"
                else -> "system"
            }
            if (newTag == readLocaleTag()) return@setOnCheckedChangeListener
            if (!getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_APP_LOCALE, newTag)
                    .commit()
            ) {
                return@setOnCheckedChangeListener
            }
            val locales = when (newTag) {
                "en" -> LocaleListCompat.forLanguageTags("en-US")
                "zh" -> LocaleListCompat.forLanguageTags("zh-CN")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(locales)
            recreate()
        }
    }

    private fun packageVersionName(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            ).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    } catch (_: Exception) {
        ""
    }.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root_app)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val example = getString(R.string.default_host_example)
        val etHost = findViewById<EditText>(R.id.et_host)
        val btnConnect = findViewById<Button>(R.id.btn_connect)
        val btnPullActions = findViewById<Button>(R.id.btn_pull_actions)
        val btnDisconnect = findViewById<Button>(R.id.btn_disconnect)
        val btnPermission = findViewById<Button>(R.id.btn_permission)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val tvVersion = findViewById<TextView>(R.id.tv_version)

        val adapter = ActionPreviewAdapter()
        val rvActions = findViewById<RecyclerView>(R.id.rv_actions)
        rvActions.layoutManager = GridLayoutManager(this, 2)
        rvActions.adapter = adapter
        adapter.submitData(CloeService.getAvailableActions(this))

        val tabConnect = findViewById<View>(R.id.tab_connect)
        val tabSettings = findViewById<View>(R.id.tab_settings)
        val tabPreviews = findViewById<View>(R.id.tab_previews)
        val tvOverlayPosition = findViewById<TextView>(R.id.tv_overlay_position)
        val btnSaveOverlayPosition = findViewById<Button>(R.id.btn_save_overlay_position)

        fun showTab(connect: Boolean, previews: Boolean, settings: Boolean) {
            tabConnect.visibility = if (connect) View.VISIBLE else View.GONE
            tabSettings.visibility = if (settings) View.VISIBLE else View.GONE
            tabPreviews.visibility = if (previews) View.VISIBLE else View.GONE
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> {
                    showTab(connect = true, previews = false, settings = false)
                    true
                }
                R.id.nav_settings -> {
                    showTab(connect = false, previews = false, settings = true)
                    true
                }
                R.id.nav_previews -> {
                    showTab(connect = false, previews = true, settings = false)
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_connect

        val saved = loadSavedHost()
        etHost.setText(if (saved.isNotEmpty()) saved else example)

        tvVersion.text = getString(R.string.footer_version, packageVersionName())
        bindLocaleRadios()

        btnSaveOverlayPosition.setOnClickListener {
            if (!CloeService.isRunning) {
                Toast.makeText(this, R.string.toast_save_position_need_connect, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            startService(Intent(this, CloeService::class.java).apply {
                putExtra("save_overlay_position", true)
            })
            Toast.makeText(this, R.string.toast_position_saved, Toast.LENGTH_SHORT).show()
        }

        fun hasOverlayPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else true
        }

        val bannerPermissionBtn = findViewById<View>(R.id.banner_permission_btn)
        val ivStatus = findViewById<ImageView>(R.id.iv_status_icon)
        val bannerStatus = findViewById<View>(R.id.banner_status)

        fun updateUI() {
            val serviceRunning = CloeService.isRunning
            val hasPermission = hasOverlayPermission()

            bannerPermissionBtn.visibility = if (hasPermission) View.GONE else View.VISIBLE
            btnConnect.isEnabled = hasPermission && !serviceRunning
            btnPullActions.isEnabled = hasPermission
            btnDisconnect.isEnabled = serviceRunning
            btnSaveOverlayPosition.isEnabled = serviceRunning

            tvOverlayPosition.text = overlayPositionSummaryText()

            tvStatus.setText(
                when {
                    !hasPermission -> R.string.status_need_overlay
                    serviceRunning -> R.string.status_connected
                    else -> R.string.status_disconnected
                }
            )

            ivStatus.setImageResource(
                when {
                    !hasPermission -> R.drawable.ic_warning
                    serviceRunning -> R.drawable.ic_check_circle
                    else -> R.drawable.ic_info
                }
            )
            ivStatus.setColorFilter(
                when {
                    !hasPermission -> 0xFFFF9500.toInt()
                    serviceRunning -> 0xFF34C759.toInt()
                    else -> 0xFF8E8E93.toInt()
                }
            )
            bannerStatus.setBackgroundColor(
                when {
                    !hasPermission -> 0xFFFFF4E5.toInt()
                    serviceRunning -> 0xFFE8F8EC.toInt()
                    else -> 0xFFF5F5F7.toInt()
                }
            )
        }

        btnPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
        }

        btnConnect.setOnClickListener {
            val h = etHost.text.toString().trim()
            if (h.isBlank() || h == example) {
                Toast.makeText(this, R.string.toast_enter_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rememberHost(h)
            val intent = Intent(this, CloeService::class.java)
            intent.putExtra("host", h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        btnPullActions.setOnClickListener {
            val h = etHost.text.toString().trim()
            if (h.isBlank() || h == example) {
                Toast.makeText(this, R.string.toast_enter_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rememberHost(h)
            btnPullActions.isEnabled = false
            lifecycleScope.launch {
                val result = ActionSync.pullFromDesktop(h, this@MainActivity)
                btnPullActions.isEnabled = true
                result.fold(
                    onSuccess = { n ->
                        adapter.submitData(CloeService.getAvailableActions(this@MainActivity))
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_pull_ok, n),
                            Toast.LENGTH_SHORT
                        ).show()
                        if (CloeService.isRunning) {
                            val reload = Intent(this@MainActivity, CloeService::class.java)
                            reload.putExtra("host", h)
                            reload.putExtra("reload_actions", true)
                            startService(reload)
                        }
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            this@MainActivity,
                            e.message ?: getString(R.string.toast_pull_fail),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }

        btnDisconnect.setOnClickListener {
            stopService(Intent(this, CloeService::class.java))
        }

        updateUI()
        tvStatus.postDelayed(object : Runnable {
            override fun run() {
                updateUI()
                tvStatus.postDelayed(this, 500)
            }
        }, 500)
    }

    override fun onStop() {
        super.onStop()
        val example = getString(R.string.default_host_example)
        val h = findViewById<EditText>(R.id.et_host).text.toString().trim()
        if (h.isNotBlank() && h != example) rememberHost(h)
    }
}
