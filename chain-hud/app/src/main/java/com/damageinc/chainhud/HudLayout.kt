package com.damageinc.chainhud

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rokid waveguide: BLACK = unlit/transparent; green channel only.
 * Gravity/position within CustomView is device/firmware-dependent and needs hardware verification.
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
            .put("gravity", "right")
        if (bold) props.put("textStyle", "bold")
        return JSONObject().put("type", "TextView").put("props", props)
    }
    fun open(t: HudText): String {
        val hud = JSONObject().put("type", "LinearLayout")
            .put("props", JSONObject()
                .put("id", "hudContainer")
                .put("layout_width", "wrap_content")
                .put("layout_height", "wrap_content")
                .put("orientation", "vertical")
                .put("gravity", "right")
                .put("paddingEnd", "15dp")
                .put("paddingTop", "12dp")
                .put("backgroundColor", "#00000000"))
            .put("children", JSONArray()
                .put(label("chainLabel", t.chain, "16sp", true))
                .put(label("timerLabel", t.timer, "31sp", true))
                .put(label("detailLabel", t.detail, "12sp", false)))
        return JSONObject().put("type", "RelativeLayout")
            .put("props", JSONObject()
                .put("id", "hudRoot")
                .put("layout_width", "match_parent")
                .put("layout_height", "match_parent")
                .put("gravity", "top|right")
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
