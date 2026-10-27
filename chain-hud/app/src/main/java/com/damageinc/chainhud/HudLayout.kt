package com.damageinc.chainhud

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rokid green-waveguide compact HUD.
 *
 * IMPORTANT: The protocol spells "aligin" (not "align") in RelativeLayout
 * parent-alignment keys. The v0.2 root gravity="top|right" was ignored:
 * on-device testing showed the HUD anchored at top-left.
 *
 * Applying both the documented protocol spelling and the conventional Android
 * spelling gives us the best chance of working across glasses firmware versions.
 * The update payload preserves stable node IDs to avoid flickering.
 */
object HudLayout {
    private fun label(id: String, value: String, size: String, bold: Boolean): JSONObject {
        val props = JSONObject()
            .put("id", id)
            .put("layout_width", "wrap_content")
            .put("layout_height", "wrap_content")
            .put("text", value)
            .put("textColor", "#FF00FF00")
            .put("textSize", size)
            .put("gravity", "end")
        if (bold) props.put("textStyle", "bold")
        return JSONObject().put("type", "TextView").put("props", props)
    }

    fun open(t: HudText): String {
        val hud = JSONObject()
            .put("type", "LinearLayout")
            .put("props", JSONObject()
                .put("id", "hudContainer")
                .put("layout_width", "wrap_content")
                .put("layout_height", "wrap_content")
                .put("orientation", "vertical")
                .put("gravity", "end")
                .put("layout_aliginParentEnd", "true")
                .put("layout_aliginParentTop", "true")
                .put("layout_alignParentEnd", "true")
                .put("layout_alignParentTop", "true")
                .put("marginTop", "14dp")
                .put("marginEnd", "20dp")
                .put("backgroundColor", "#00000000"))
            .put("children", JSONArray()
                .put(label("chainLabel", t.chain, "12sp", true))
                .put(label("timerLabel", t.timer, "21sp", true))
                // Empty in normal operation; temporarily populated for actual
                // danger/no-chain/stale/error notices.
                .put(label("detailLabel", t.detail, "10sp", false)))
        return JSONObject()
            .put("type", "RelativeLayout")
            .put("props", JSONObject()
                .put("id", "hudRoot")
                .put("layout_width", "match_parent")
                .put("layout_height", "match_parent")
                .put("backgroundColor", "#00000000"))
            .put("children", JSONArray().put(hud))
            .toString()
    }

    fun update(t: HudText): String = JSONArray()
        .put(change("chainLabel", t.chain))
        .put(change("timerLabel", t.timer))
        .put(change("detailLabel", t.detail))
        .toString()

    private fun change(id: String, value: String) = JSONObject()
        .put("action", "update").put("id", id)
        .put("props", JSONObject().put("text", value))
}
