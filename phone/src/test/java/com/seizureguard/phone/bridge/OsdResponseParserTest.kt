package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class OsdResponseParserTest {
    @Test fun ok() = assertEquals(PostOutcome.OK, classify(200, "OK"))
    @Test fun sendSettings() = assertEquals(PostOutcome.SEND_SETTINGS, classify(200, "sendSettings"))
    @Test fun parseError() = assertEquals(PostOutcome.OSD_PARSE_ERROR, classify(200, "ERROR"))
    @Test fun unknownBodyIsParseError() = assertEquals(PostOutcome.OSD_PARSE_ERROR, classify(200, "<html>"))
    @Test fun emptyBodyIsParseError() = assertEquals(PostOutcome.OSD_PARSE_ERROR, classify(200, ""))

    @Test fun wrongDatasource() = assertEquals(
        PostOutcome.WRONG_DATASOURCE,
        classify(200, "{'msg': 'Error - you should not see this message! - Something wrong in WebServer.serve()'}"),
    )

    @Test fun unreachable() {
        assertEquals(PostOutcome.UNREACHABLE, classify(0, ""))
        assertEquals(PostOutcome.UNREACHABLE, classify(500, "OK"))
        assertEquals(PostOutcome.UNREACHABLE, classify(-1, "you should not see this message"))
    }
}
