package com.vknxworks.tapoctrl.data.settings

import com.vknxworks.tapoctrl.data.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing a settings file: never less private, devices added once, only private addresses. */
class SettingsFileTest {
    @Test fun privacyIsNeverLowered() {
        val cur = AppSettings(hideScreenshots = true, hideRecents = true, lockTimeout = LockTimeout.MINUTES_5)
        val after = SettingsFile.applied(SettingsFileContent(hideScreenshots = false, hideRecents = false, lockTimeout = "RESTART"), cur)
        assertTrue(after.hideScreenshots && after.hideRecents)
        assertEquals(LockTimeout.MINUTES_5, after.lockTimeout)
        assertEquals(LockTimeout.IMMEDIATELY, SettingsFile.applied(SettingsFileContent(lockTimeout = "IMMEDIATELY"), cur).lockTimeout)
    }

    @Test fun devicesAreMerged() {
        val cur = listOf(SavedDevice("1", "192.168.1.10", "Lamp", mac = "AA-BB-CC-DD-EE-FF"))
        val f = SettingsFileContent(devices = listOf(
            FileDevice("192.168.1.99", mac = "aa:bb:cc:dd:ee:ff", room = "Living room"),
            FileDevice("192.168.1.11", name = "Plug"),
            FileDevice("8.8.8.8", name = "Not local"),
            FileDevice("not an ip"),
        ))
        var n = 0
        val m = SettingsFile.merge(f, cur) { "new${n++}" }
        assertEquals(1, m.added)
        assertEquals(1, m.roomsChanged)
        assertEquals(2, m.list.size)
        assertEquals("Living room", m.list[0].room)
        assertEquals("192.168.1.10", m.list[0].ip)
        assertEquals("192.168.1.11", m.list[1].ip)
    }
}
