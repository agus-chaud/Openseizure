package com.seizureguard.phone.bridge

enum class PostOutcome { OK, SEND_SETTINGS, OSD_PARSE_ERROR, WRONG_DATASOURCE, UNREACHABLE }

// Untouched placeholder SdWebServer returns when its data source is not "Garmin".
private const val WRONG_DATASOURCE_MARKER = "you should not see this message"

fun classify(httpCode: Int, body: String): PostOutcome {
    if (httpCode != 200) return PostOutcome.UNREACHABLE
    if (body.contains(WRONG_DATASOURCE_MARKER)) return PostOutcome.WRONG_DATASOURCE
    return when (body.trim()) {
        "OK" -> PostOutcome.OK
        "sendSettings" -> PostOutcome.SEND_SETTINGS
        else -> PostOutcome.OSD_PARSE_ERROR
    }
}
