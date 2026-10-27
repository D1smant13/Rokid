package com.damageinc.chainhud

/** All times in milliseconds from SystemClock.elapsedRealtime(). Never compare with wall time. */
data class ChainSnapshot(val count: Int, val timeout: Int, val observedAt: Long) {
    init { require(count >= 0 && timeout >= 0) }
    fun remainingSeconds(now: Long): Int {
        if (count == 0 || timeout == 0) return 0
        return ((timeout * 1000L - (now - observedAt).coerceAtLeast(0L) + 999L) / 1000L)
            .coerceAtLeast(0L).toInt()
    }
    fun isStale(now: Long): Boolean = now - observedAt > 45_000L
}

data class HudText(val chain: String, val timer: String, val detail: String)

object ChainClock {
    fun render(snapshot: ChainSnapshot?, now: Long, error: String? = null): HudText {
        if (snapshot == null) return HudText("CHAIN --", "--:--", error ?: "CONNECTING")
        if (snapshot.isStale(now)) return HudText("CHAIN ${snapshot.count}", "--:--", "STALE - CHECK TORN")
        if (snapshot.count == 0 || snapshot.timeout == 0) return HudText("CHAIN ${snapshot.count}", "--:--", "NO ACTIVE CHAIN")
        val remaining = snapshot.remainingSeconds(now)
        if (remaining == 0) return HudText("CHAIN ${snapshot.count}", "00:00", "VERIFY IN TORN")
        val timer = "%02d:%02d".format(remaining / 60, remaining % 60)
        val warning = when {
            remaining <= 15 -> if ((now / 500L) % 2L == 0L) "!! HIT NOW !!" else "HIT NOW"
            remaining <= 30 -> "!! CHAIN RISK !!"
            remaining <= 60 -> "! UNDER 1 MIN !"
            else -> "NEXT HIT"
        }
        return HudText("CHAIN ${snapshot.count}", timer, warning)
    }
}
