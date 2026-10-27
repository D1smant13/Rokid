package com.damageinc.chainhud

import org.junit.Assert.*
import org.junit.Test

class HttpErrorDiagnosticsTest {
    @Test fun cloudflare403IncludesRayButNoRawBody() {
        val x = HttpErrorDiagnostics.describe(
            403, "<html>Cloudflare challenge secret</html>",
            "text/html", "cloudflare", "abc-123")
        assertTrue(x.message.contains("Cloudflare"))
        assertTrue(x.message.contains("abc-123"))
        assertFalse(x.message.contains("secret"))
    }
    @Test fun tornApiError16IsIdentified() {
        val x = HttpErrorDiagnostics.describe(
            403, """{"error":{"code":16,"error":"Access level"}}""",
            "application/json", "nginx", null)
        assertEquals(16, x.tornCode)
        assertTrue(x.message.contains("access insufficient"))
    }
    @Test fun genericForbiddenIsNotDiagnosedAsBadKey() {
        val x = HttpErrorDiagnostics.describe(403, "Forbidden", "text/plain", "nginx", null)
        assertFalse(x.message.contains("incorrect key"))
        assertTrue(x.message.contains("server refused"))
    }
}
