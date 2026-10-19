package com.fpslite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

class OverlayService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private var view: TextView? = null
    private var label = ""
    private var sampler: Thread? = null
    private var shell: RootShell? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (view == null) {
            if (!addOverlay()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            status = "Iniciando..."
            startSampler()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "FPS Lite", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FPS Lite ligado")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Desligar", stop).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun addOverlay(): Boolean {
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        lp.x = prefs.getInt("x", dp(16))
        lp.y = prefs.getInt("y", dp(48))
        val tv = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }
        // arrastar para mudar de lugar (a posição fica salva)
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x
                    sy = lp.y
                    tx = e.rawX
                    ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
            }
            true
        }
        clamp()
        return try {
            wm.addView(tv, lp)
            view = tv
            setLabel("-- FPS", GRAY)
            true
        } catch (e: Exception) {
            status = "Sem permissão de sobreposição"
            false
        }
    }

    private fun startSampler() {
        sampler = Thread {
            try {
                val sh = RootShell().also { shell = it }
                if (Thread.currentThread().isInterrupted) {
                    sh.close()
                    return@Thread
                }
                if (sh.run("id")?.contains("uid=0") != true) {
                    status = "Sem root (acesso negado?)"
                    ui.post { setLabel("SEM ROOT", RED) }
                    return@Thread
                }
                val eng = FpsEngine(sh)
                while (!Thread.currentThread().isInterrupted) {
                    val fps = eng.sample()
                    status = "Medindo: " + eng.method
                    ui.post { show(fps) }
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                // desligado
            } catch (e: Exception) {
                status = "Erro: " + e.message
                ui.post { setLabel("ERRO", RED) }
            }
        }
        sampler?.start()
    }

    private fun show(fps: Float) {
        if (fps < 0f) {
            setLabel("-- FPS", GRAY)
            return
        }
        val n = fps.roundToInt()
        setLabel("$n FPS", if (n >= 55) GREEN else if (n >= 30) YELLOW else RED)
    }

    private fun setLabel(t: String, color: Int) {
        val v = view ?: return
        if (t == label) return // não redesenha à toa
        label = t
        v.text = t
        v.setTextColor(color)
    }

    private fun clamp() {
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - dp(80)))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - dp(40)))
        view?.let {
            try {
                wm.updateViewLayout(it, lp)
            } catch (e: Exception) {
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clamp()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        sampler?.interrupt()
        shell?.close()
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
        super.onDestroy()
    }

    companion object {
        const val STOP = "com.fpslite.STOP"
        private const val CHANNEL = "fps"
        private val GRAY = 0xFFAAAAAA.toInt()
        private val GREEN = 0xFF4CAF50.toInt()
        private val YELLOW = 0xFFFFC107.toInt()
        private val RED = 0xFFF44336.toInt()

        @Volatile var running = false
        @Volatile var status = "Desligado"
    }
}
