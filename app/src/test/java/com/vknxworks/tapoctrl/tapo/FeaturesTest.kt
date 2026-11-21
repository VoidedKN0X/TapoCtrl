package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parsing of sockets, hub sensors, the status light, auto-off, usage time and the power-cut state. */
class FeaturesTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun childrenAreRecognised() {
        val p = ChildInfo.parsePage(j("""{"child_device_list":[
            {"device_id":"a","nickname":"U29ja2V0IDE=","position":1,"device_on":true,"category":"plug.powerstrip.sub-plug","model":"P300"},
            {"device_id":"b","model":"T310","current_temp":21.5,"current_humidity":48,"temp_unit":"celsius","status":"online"},
            {"device_id":"c","model":"T110","open":true,"at_low_battery":true},
            {"device_id":"d","model":"T100","detected":false},
            {"model":"no id"}],"start_index":0,"sum":4}"""))
        assertEquals(4, p.children.size)
        assertEquals(4, p.sum)
        assertEquals("Socket 1", p.children[0].nickname)
        assertEquals(ChildKind.SOCKET, p.children[0].kind)
        assertTrue(p.children[0].switchable)
        assertEquals(ChildKind.TEMPERATURE, p.children[1].kind)
        assertFalse(p.children[1].switchable)
        assertEquals(21.5, p.children[1].temperature!!, 0.0)
        assertEquals(ChildKind.CONTACT, p.children[2].kind)
        assertEquals(true, p.children[2].lowBattery)
        assertEquals(ChildKind.MOTION, p.children[3].kind)
    }

    @Test fun settingsAndUsage() {
        assertEquals(setOf("led", "child_device"), Components.parse(j("""{"component_list":[{"id":"led","ver_code":1},{"id":"child_device"}]}""")))
        assertEquals(emptySet<String>(), Components.parse(j("[]")))
        assertEquals(AutoOffConfig(true, 30), AutoOffConfig.parse(j("""{"enable":true,"delay_min":30}""")))
        assertEquals(DeviceUsage(5, 100, 400), DeviceUsage.parse(j("""{"time_usage":{"today":5,"past7":100,"past30":400}}""")))
        assertEquals(false, LedInfo.parse(j("""{"led_rule":"never"}""")).on)
        assertEquals(true, LedInfo.parse(j("""{"led_rule":"night_mode"}""")).on)
        assertNull(LedInfo.parse(j("{}")).on)
    }

    @Test fun powerCutState() {
        assertEquals(PowerCutState.ALWAYS_OFF, parsePowerCutState(j("""{"default_states":{"type":"custom","state":{"on":false}}}""")))
        assertEquals(PowerCutState.ALWAYS_ON, parsePowerCutState(j("""{"default_states":{"type":"custom","state":{"on":true}}}""")))
        assertEquals(PowerCutState.LAST_STATE, parsePowerCutState(j("""{"default_states":{"type":"last_states","state":{}}}""")))
        assertNull(parsePowerCutState(j("{}")))
    }

    @Test fun childErrorsAreErrors() {
        ChildReply.check(j("""{"responseData":{"error_code":0,"result":{}}}"""))
        try {
            ChildReply.check(j("""{"responseData":{"error_code":-1008}}"""))
            fail("accepted an error")
        } catch (e: TapoException.DeviceError) {
            assertEquals(-1008, e.code)
        }
    }
}
