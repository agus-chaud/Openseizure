package com.seizureguard.phone.bridge

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

object OsdPayloadCodec {
    fun rawDataJson(milliG: DoubleArray): String {
        val data = JSONArray()
        milliG.forEach { data.put(it) }
        return JSONObject().put("dataType", "raw").put("data", data).toString()
    }

    // analysisPeriod, sampleFreq and battery are read with getInt() by OSD and are mandatory.
    fun settingsJson(battery: Int, sampleFreq: Int = 25, analysisPeriod: Int = 5): String =
        JSONObject()
            .put("dataType", "settings")
            .put("analysisPeriod", analysisPeriod)
            .put("sampleFreq", sampleFreq)
            .put("battery", battery)
            .put("watchPartNo", "")
            .put("watchFwVersion", "")
            .put("sdVersion", "")
            .put("sdName", "")
            .toString()

    fun formBody(json: String): ByteArray =
        ("dataObj=" + URLEncoder.encode(json, "UTF-8")).toByteArray(Charsets.UTF_8)
}
