package com.aqi.ccb.application

import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle/connection-specific editor task ownership; no Activity, secret, thread or network dependency. */
class ProviderTaskGate {
    class Ticket internal constructor(internal val owner: ProviderTaskGate, val providerId: String) {
        private val cancelled = AtomicBoolean(false)
        fun isCancelled(): Boolean = cancelled.get()
        internal fun cancel() { cancelled.set(true) }
    }
    private var current: Ticket? = null
    @Synchronized fun begin(providerId: String): Ticket {
        require(providerId.isNotBlank())
        current?.cancel()
        return Ticket(this,providerId).also{current=it}
    }
    @Synchronized fun invalidate() { current?.cancel();current=null }
    @Synchronized fun accepts(ticket: Ticket, providerId: String): Boolean =
        ticket.owner===this && current===ticket && !ticket.isCancelled() && ticket.providerId==providerId
    @Synchronized fun complete(ticket: Ticket, providerId: String): Boolean {
        if(!accepts(ticket,providerId))return false
        ticket.cancel();current=null;return true
    }
}
