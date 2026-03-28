package com.p2r3.convert

import com.p2r3.convert.model.StartDestination
import com.p2r3.convert.ui.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `settings mapping returns matching destination`() {
        assertEquals(AppDestination.Home, AppDestination.fromSettings(StartDestination.HOME))
        assertEquals(AppDestination.Convert, AppDestination.fromSettings(StartDestination.CONVERT))
        assertEquals(AppDestination.Settings, AppDestination.fromSettings(StartDestination.SETTINGS))
    }
}
