package com.toufik.reelskipper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.toufik.reelskipper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)
        bindControls()
        loadSettingsIntoUi()
    }

    override fun onResume() {
        super.onResume()
        settings.registerListener(prefsListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        settings.unregisterListener(prefsListener)
    }

    private fun bindControls() {
        binding.btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnToggleSkip.setOnClickListener {
            settings.adSkipEnabled = !settings.adSkipEnabled
            updateToggleButton(settings.adSkipEnabled)
        }
        binding.btnSaveKeywords.setOnClickListener {
            val k = binding.etKeywords.text?.toString()?.trim()
            if (!k.isNullOrEmpty()) {
                settings.adKeywordsRaw = k
                toast(getString(R.string.saved))
            }
        }
    }

    private fun loadSettingsIntoUi() {
        binding.etKeywords.setText(settings.adKeywordsRaw)
        updateToggleButton(settings.adSkipEnabled)
    }

    private fun updateToggleButton(enabled: Boolean) {
        val btn = binding.btnToggleSkip
        if (enabled) {
            btn.text = getString(R.string.btn_skip_on)
            btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent)
        } else {
            btn.text = getString(R.string.btn_skip_off)
            btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pill_off_fg)
        }
    }

    private fun refreshStatus() {
        val on = isAccessibilityServiceEnabled()
        setPill(binding.pillStatus, on)
        binding.tvStatusHint.text = getString(
            if (on) R.string.hint_active else R.string.hint_needs_permission
        )
        binding.btnEnable.text = getString(
            if (on) R.string.btn_open_settings else R.string.btn_enable
        )
    }

    private fun setPill(view: TextView, on: Boolean) {
        if (on) {
            view.text = getString(R.string.pill_active)
            view.setBackgroundResource(R.drawable.pill_on)
            view.setTextColor(ContextCompat.getColor(this, R.color.pill_on_fg))
        } else {
            view.text = getString(R.string.pill_inactive)
            view.setBackgroundResource(R.drawable.pill_off)
            view.setTextColor(ContextCompat.getColor(this, R.color.pill_off_fg))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${ReelsAccessibilityService::class.java.name}"
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
