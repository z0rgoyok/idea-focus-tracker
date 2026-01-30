package com.focustracker.services

import java.awt.AWTEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

internal object UserActivityEvents {

    fun isMeaningful(event: AWTEvent): Boolean {
        return when (event) {
            is MouseEvent -> isMeaningfulMouseEventId(event.id)
            is MouseWheelEvent -> true
            is KeyEvent -> isMeaningfulKeyEventId(event.id)
            else -> false
        }
    }

    fun isMeaningfulMouseEventId(id: Int): Boolean {
        return when (id) {
            MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_CLICKED,
            MouseEvent.MOUSE_DRAGGED -> true

            // Ignore noisy/ambient mouse move events (can happen without user intent).
            MouseEvent.MOUSE_MOVED,
            MouseEvent.MOUSE_ENTERED,
            MouseEvent.MOUSE_EXITED -> false

            else -> false
        }
    }

    fun isMeaningfulKeyEventId(id: Int): Boolean = id == KeyEvent.KEY_PRESSED
}

