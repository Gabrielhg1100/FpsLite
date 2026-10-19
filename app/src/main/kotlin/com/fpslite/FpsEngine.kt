package com.fpslite

/**
 * Lê o FPS da tela via root. Sem teto fixo: mostra o que o hardware apresentou.
 * 1) Qualcomm/SDE: /sys/class/drm/sde-crtc-0/measured_fps (já calculado pelo kernel).
 * 2) Reserva: contador de flips do SurfaceFlinger (Android mais antigo).
 */
class FpsEngine(private val sh: RootShell) {
    @Volatile var method = "-"
    private var mode = 0 // 0 = descobrindo, 1 = sde, 2 = flips
    private var fails = 0
    private var flips = -1L
    private var lastT = 0L

    /** FPS atual, ou -1 se não houver dados. */
    fun sample(): Float {
        if (mode != 2) {
            val v = parseSde(sh.run(SDE))
            if (v != null) {
                mode = 1
                fails = 0
                method = "sde"
                return v
            }
            if (mode == 1 && ++fails < 3) return -1f
            mode = 2
        }
        return flipsFps()
    }

    private fun flipsFps(): Float {
        method = "flips"
        val f = parseFlips(sh.run("service call SurfaceFlinger 1013")) ?: return -1f
        val t = System.nanoTime()
        var fps = -1f
        if (flips >= 0L && t > lastT) {
            fps = ((f - flips) and 0xFFFFFFFFL) * 1e9f / (t - lastT)
            if (fps > 2000f) fps = -1f
        }
        flips = f
        lastT = t
        return fps
    }

    companion object {
        private const val SDE = "cat /sys/class/drm/sde-crtc-0/measured_fps"
        private val SDE_FPS = Regex("fps:\\s*([0-9]+(?:\\.[0-9]+)?)")
        private val HEX = Regex("(?<![0-9a-fA-Fx])[0-9a-fA-F]{8}(?![0-9a-fA-F:])")

        /** "fps: 59.8 duration:500000 frame_count:30" -> 59.8 */
        fun parseSde(o: String?): Float? =
            o?.let { SDE_FPS.find(it)?.groupValues?.get(1)?.toFloatOrNull() }

        fun parseFlips(o: String?): Long? {
            if (o == null) return null
            val i = o.indexOf("Parcel(")
            if (i < 0) return null
            val v = HEX.findAll(o, i).map { it.value }.take(2).toList()
            return if (v.size == 2 && v[0] == "00000000") v[1].toLong(16) else null
        }
    }
}
