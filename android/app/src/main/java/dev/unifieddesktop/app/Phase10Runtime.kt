package dev.unifieddesktop.app

/**
 * Service-owned desktop lifetime. The TLS socket is replaceable; this object is not. It keeps
 * the Presentation, ShellRoot and guest-engine windows alive while a PC link is being replaced.
 */
class Phase10Runtime {
    @Volatile private var desktop: Phase5Desktop? = null

    @Synchronized
    fun current(): Phase5Desktop? = desktop

    @Synchronized
    fun adopt(value: Phase5Desktop) {
        if (desktop == null) desktop = value
    }

    @Synchronized
    fun clear(value: Phase5Desktop? = null) {
        if (value == null || desktop === value) desktop = null
    }

    fun isUp(): Boolean = desktop?.running == true

    @Synchronized
    fun stop() {
        val d = desktop
        desktop = null
        d?.stop()
    }
}
