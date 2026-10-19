package com.fpslite

/**
 * Lê o FPS pelo SurfaceFlinger (root). Não há teto fixo (120 etc.):
 * mostra o que o sistema realmente apresentou na tela.
 *
 * 1) Padrão: contador de flips da tela ("service call SurfaceFlinger 1013") — leve, 1 leitura/s.
 * 2) Reserva: camada do app em primeiro plano ("dumpsys SurfaceFlinger --latency").
 */
class FpsEngine(private val sh: RootShell) {
    @Volatile var method = "-"
    private var flips = -1L
    private var lastT = 0L
    private var fails = 0
    private var zeros = 0
    private var useFlips = true
    private var layer: String? = null
    private var tick = 0
    private var nextSearch = 0

    /** FPS atual, ou -1 se não houver dados. */
    fun sample(): Float {
        if (useFlips) {
            val f = parseFlips(sh.run("service call SurfaceFlinger 1013"))
            if (f == null) {
                if (++fails < 3) return -1f
                useFlips = false
            } else {
                fails = 0
                val t = System.nanoTime()
                var fps = -1f
                if (flips >= 0L && t > lastT) {
                    fps = ((f - flips) and 0xFFFFFFFFL) * 1e9f / (t - lastT)
                    if (fps > 2000f) fps = -1f
                }
                flips = f
                lastT = t
                method = "tela"
                if (fps > 0f) {
                    zeros = 0
                } else if (fps == 0f && ++zeros % 5 == 0) {
                    // contador parado há um tempo? confere pela camada do app
                    val a = layerFps()
                    if (a > 0f) {
                        useFlips = false
                        return a
                    }
                    method = "tela"
                }
                return fps
            }
        }
        return layerFps()
    }

    private fun layerFps(): Float {
        tick++
        method = "app"
        var f = layer?.let { latency(it) } ?: -1f
        if (f > 0f) return f
        if (tick >= nextSearch) { // parado ou sem camada: procura de novo, no máx. a cada 3 s
            nextSearch = tick + 3
            f = findLayer()
        }
        return f
    }

    private fun findLayer(): Float {
        layer = null
        val focus = sh.run("dumpsys window | grep -m1 mCurrentFocus") ?: return -1f
        val pkg = FOCUS.find(focus)?.groupValues?.get(1) ?: return -1f
        val list = sh.run("dumpsys SurfaceFlinger --list") ?: return -1f
        var best = -1f
        val names = list.lineSequence().map { it.trim() }
            .filter { it.contains(pkg) && !it.startsWith("Background for") && !it.startsWith("Bounds for") }
            .take(8)
        for (n in names) {
            val f = latency(n)
            if (f > best) {
                best = f
                layer = n
            }
        }
        return best
    }

    private fun latency(name: String): Float {
        val q = "'" + name.replace("'", "'\\''") + "'"
        val o = sh.run("dumpsys SurfaceFlinger --latency $q") ?: return -1f
        return parseLatency(o, System.nanoTime())
    }

    companion object {
        private val HEX = Regex("(?<![0-9a-fA-Fx])[0-9a-fA-F]{8}(?![0-9a-fA-F:])")
        private val WS = Regex("\\s+")
        private val FOCUS = Regex("mCurrentFocus=Window\\{\\S+ u\\d+ ([\\w.]+)/")

        /** "Result: Parcel(00000000 0001f4e2 ...)" -> 0x0001f4e2 */
        fun parseFlips(o: String?): Long? {
            if (o == null) return null
            val i = o.indexOf("Parcel(")
            if (i < 0) return null
            val v = HEX.findAll(o, i).map { it.value }.take(2).toList()
            return if (v.size == 2 && v[0] == "00000000") v[1].toLong(16) else null
        }

        /** Saída do --latency: 1ª linha = período; depois "desejado  apresentado  pronto" (ns). */
        fun parseLatency(o: String, now: Long): Float {
            val ts = ArrayList<Long>(128)
            for (line in o.lineSequence().drop(1)) {
                val p = line.trim().split(WS)
                if (p.size < 3) continue
                val t = p[1].toLongOrNull() ?: continue
                if (t > 0L && t < Long.MAX_VALUE / 2) ts.add(t)
            }
            if (ts.isEmpty()) return -1f
            ts.sort()
            val last = ts[ts.size - 1]
            if (now - last > 1_200_000_000L) return 0f // sem frames novos: parado
            val from = last - 1_000_000_000L
            val i = ts.indexOfFirst { it >= from }
            val n = ts.size - i
            val span = last - ts[i]
            return if (n < 2 || span <= 0L) n.toFloat() else (n - 1) * 1e9f / span
        }
    }
}
