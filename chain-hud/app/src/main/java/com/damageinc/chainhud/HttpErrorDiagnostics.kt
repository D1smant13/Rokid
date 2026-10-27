package com.damageinc.chainhud

/** Safe HTTP error summaries; never show a raw server response or credentials. */
object HttpErrorDiagnostics {
    data class Report(val message: String, val tornCode: Int? = null)
    fun describe(
        httpStatus: Int, body: String, contentType: String?, server: String?, ray: String?
    ): Report {
        val trimmed = body.trimStart()
        val tornCode = if (trimmed.startsWith("{") && body.contains("\"error\"")) {
            Regex("\"code\"\\s*:\\s*(\\d+)").find(body)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        } else null
        if (tornCode != null) {
            val detail = when (tornCode) {
                2 -> "incorrect key"
                5 -> "rate limit exceeded"
                7 -> "faction unavailable to key"
                8 -> "IP blocked temporarily"
                10 -> "key owner in federal jail"
                13 -> "inactive key"
                16 -> "key access insufficient"
                18 -> "key paused"
                else -> "check Torn API key usage log"
            }
            return Report("Torn API error $tornCode: $detail", tornCode)
        }
        val html = contentType?.contains("html", ignoreCase = true) == true ||
            trimmed.startsWith("<!doctype", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        val cf = !ray.isNullOrBlank() || body.contains("cloudflare", ignoreCase = true)
        val safeRay = ray?.take(40)?.filter { it.isLetterOrDigit() || it == '-' }
            ?.takeIf { it.isNotEmpty() }
        if (httpStatus == 403 && (cf || (html && server?.contains("cloudflare", true) == true))) {
            return Report("HTTP 403: Cloudflare/security block" +
                (if (safeRay != null) " (Ray $safeRay)" else "") + "; try Wi-Fi or cellular")
        }
        if (httpStatus == 403) return Report("HTTP 403: server refused request" +
            (if (html) " (HTML response)" else "") + "; check connection")
        if (httpStatus == 401) return Report("HTTP 401: unauthorized")
        if (httpStatus == 429) return Report("HTTP 429: request limit")
        return Report("Torn HTTP $httpStatus" + if (html) " (HTML response)" else "")
    }
}
