package com.fpslite

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var btn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        btn = Button(this).apply { setOnClickListener { toggle() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(info)
            addView(btn)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = OverlayService.running
        btn.text = if (on) "Desligar" else "Ligar"
        info.text = "FPS Lite\n" + (if (on) OverlayService.status else "Desligado")
    }

    private fun toggle() {
        val svc = Intent(this, OverlayService::class.java)
        if (OverlayService.running) {
            stopService(svc)
            OverlayService.running = false
            refresh()
            return
        }
        btn.isEnabled = false
        Thread {
            // com root, libera sobreposição e notificação sozinho
            try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "appops set $packageName SYSTEM_ALERT_WINDOW allow; " +
                            "pm grant $packageName android.permission.POST_NOTIFICATIONS"
                    )
                ).waitFor()
            } catch (e: Exception) {
            }
            runOnUiThread {
                btn.isEnabled = true
                if (Settings.canDrawOverlays(this)) {
                    startForegroundService(svc)
                    btn.postDelayed({ refresh() }, 700)
                } else {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
        }.start()
    }
}
