package com.damageinc.chainhud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission

/**
 * Runs on the Android PHONE, not directly on the glasses.
 * A foreground companion service owns the CXR session and keeps ticking offscreen.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var keyEdit: EditText
    private lateinit var factionEdit: EditText
    private lateinit var compatible: CheckBox
    private lateinit var statusView: TextView
    private var pendingFaction = 20747
    private var pendingCompat = false
    private val statusTick = object : Runnable {
        override fun run() {
            statusView.text = getSharedPreferences("chainhud", MODE_PRIVATE)
                .getString("status", "Ready to connect") ?: "Ready to connect"
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("chainhud", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 8, 0, 8)
        }
        root.addView(label("TORN // CHAIN HUD", 24f))
        root.addView(label("Green-only Rokid waveguide. The app runs on your phone and sends a HUD to your glasses."))
        root.addView(label("Faction ID"))
        factionEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(p.getInt("faction", 20747).toString())
        }
        root.addView(factionEdit)
        root.addView(label("Torn API key (local encrypted storage; leave blank to reuse saved key)"))
        keyEdit = EditText(this).apply {
            hint = if (SecureKey.read(this@MainActivity) == null) "Enter key" else "Saved key available"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(keyEdit)
        compatible = CheckBox(this).apply {
            text = "Compatibility refresh (close/reopen each second; flickers)"
            isChecked = p.getBoolean("compat", false)
        }
        root.addView(compatible)
        root.addView(Button(this).apply {
            text = "START GLASSES HUD"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "STOP HUD"
            setOnClickListener {
                startService(Intent(this@MainActivity, HudService::class.java)
                    .setAction(HudService.ACTION_STOP))
            }
        })
        root.addView(Button(this).apply {
            text = "ERASE STORED API KEY"
            setOnClickListener {
                SecureKey.clear(this@MainActivity)
                keyEdit.setText("")
                keyEdit.hint = "Enter key"
                statusView.text = "Saved key erased"
            }
        })
        statusView = label("Ready to connect")
        root.addView(statusView)
        root.addView(label(
            "API: faction/chain only. Phone polls periodically; display ticks each second. " +
            "If status says STALE, verify directly in Torn. No attack automation."
        ))
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        ui.post(statusTick)
    }
    override fun onStop() {
        ui.removeCallbacks(statusTick)
        super.onStop()
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 12)
            statusView.text = "Allow Nearby Devices, then tap START again"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 13)
            // Notification permission is optional; proceed after next tap.
            statusView.text = "Allow notifications, then tap START again"
            return
        }
        val id = factionEdit.text.toString().toIntOrNull()
        if (id == null || id <= 0) {
            statusView.text = "Enter a valid faction ID"
            return
        }
        try {
            val edited = keyEdit.text.toString().trim()
            if (edited.isNotEmpty()) SecureKey.save(this, edited)
            if (SecureKey.read(this) == null) {
                statusView.text = "Enter a valid Torn API key first"
                return
            }
        } catch (e: Exception) {
            statusView.text = "Cannot save key: ${e.message}"
            return
        }
        pendingFaction = id
        pendingCompat = compatible.isChecked
        getSharedPreferences("chainhud", MODE_PRIVATE).edit()
            .putInt("faction", id).putBoolean("compat", pendingCompat).apply()
        if (!AuthorizationHelper.isRequiredRokidAppInstalled(this) &&
            !AuthorizationHelper.isRequiredHiRokidInstalled(this)) {
            statusView.text = "Install and connect Hi Rokid first"
            return
        }
        statusView.text = "Requesting Rokid authorization..."
        try {
            // Display-only; do not request camera or microphone.
            val instant = AuthorizationHelper.requestAuthorization(
                this, emptyArray<GlassPermission>(), AUTH_REQUEST
            )
            instant?.let { finishAuthorization(it.first, it.second) }
        } catch (e: Exception) {
            statusView.text = "Rokid authorization error: ${e.javaClass.simpleName}"
        }
    }

    @Deprecated("Rokid SDK still delivers authorization through activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST) finishAuthorization(resultCode, data)
    }

    private fun finishAuthorization(resultCode: Int, data: Intent?) {
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                val intent = Intent(this, HudService::class.java)
                    .setAction(HudService.ACTION_START)
                    .putExtra(HudService.EXTRA_TOKEN, result.token)
                    .putExtra(HudService.EXTRA_FACTION, pendingFaction)
                    .putExtra(HudService.EXTRA_COMPAT, pendingCompat)
                startForegroundService(intent)
                statusView.text = "Started service; waiting for Rokid..."
            }
            is AuthResult.AuthFail -> statusView.text = "Rokid authorization failed"
            is AuthResult.AuthCancel -> statusView.text = "Rokid authorization canceled"
        }
    }
    companion object { private const val AUTH_REQUEST = 2003 }
}
