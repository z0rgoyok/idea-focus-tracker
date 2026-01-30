package com.focustracker.services

import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserActivityEventsTest {

    @Test
    fun `mouse moved is not meaningful`() {
        assertFalse(UserActivityEvents.isMeaningfulMouseEventId(MouseEvent.MOUSE_MOVED))
    }

    @Test
    fun `mouse entered is not meaningful`() {
        assertFalse(UserActivityEvents.isMeaningfulMouseEventId(MouseEvent.MOUSE_ENTERED))
    }

    @Test
    fun `mouse pressed is meaningful`() {
        assertTrue(UserActivityEvents.isMeaningfulMouseEventId(MouseEvent.MOUSE_PRESSED))
    }

    @Test
    fun `key pressed is meaningful`() {
        assertTrue(UserActivityEvents.isMeaningfulKeyEventId(KeyEvent.KEY_PRESSED))
    }

    @Test
    fun `key released is not meaningful`() {
        assertFalse(UserActivityEvents.isMeaningfulKeyEventId(KeyEvent.KEY_RELEASED))
    }
}

