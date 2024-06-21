package game.paulgross.kakuroplaystore

import game.paulgross.kakuroplaystore.GameEngine.Message

class TimingServer_old() : Thread() {

    var serverThread: TimingServer_old? = null

    // TODO - move this to inside the game engine, and allow it to be requested with a factory function.
    // The game engine will start it and shut it down as required.
    // The game engine will pause and unpause it as required too. Need to research and test that feature.

    private var running = true
    private var shutdown = false

    private val eventTimers = mutableListOf<EventTimer>()

    data class EventTimer(val responseFunction: (message: Message) -> Unit, val delay: Int, val periodic: Boolean, val syncTime: Long)

    override fun run() {
        serverThread = this
        println("#### STARTING TIMER SYSTEM.")


        var sleepTime = 10000L // Default 10 seconds

        // TODO - run until shutdown, sleeping between sending event messages.
        while (running) {

            // Adding a new timer to eventTimers will wake the thread and force a recalc
            // of the sleep time.

            // Iterate all the timer tasks
            // send messages as required. Each message contains the delay time in case the message is late.
            // The delay time is the milliseconds between the requested event time and the actual message time.
            // The special -1 delay time is for new events and also just after the system leaves pause mode.

            // For each timer task, determine the sleep time required.
            // Keep track of only the shortest sleep time, and at the end, sleep for that time.
            for (et in eventTimers) {
                println("#### TODO - calc sleep delay for [$et].")
            }

            // Check running flag again in case it was switched off during message handling.
            if (running) {
                println("#### TimingServer sleeping for $sleepTime milliseconds.")
                try {
                    sleep(sleepTime)
                } catch (e: InterruptedException) {
                    println("#### TimingServer was INTERRUPTED while sleeping.")
                }            }
        }

        // Shutdown everything here ... clear maps and lists etc.
        // ?? Iterate each EventTimer and send a shutdown message to the callbacks???

        eventTimers.clear()
    }

    /**
     * This will completely shutdown the timer system. All TimerEvents are removed.
     */
    public fun shutdown() {
        shutdown = true
    }

    /**
     * This will temporarily stop the timer system.
     * It is called when the App is paused by the Android System.
     * All TimerEvents are retained so then when run() is called next, the timer system can resume.
     */
    public fun pause() {
        running = false
        serverThread?.interrupt()
    }

    public fun addSingleEvent(responseFunction: (message: Message) -> Unit, delay: Int): EventTimer {
        val et = EventTimer(responseFunction, delay, false, System.currentTimeMillis())
        eventTimers.add(et)
        serverThread?.interrupt()    // Force a immediate assessment of the timing
        return et
    }

    public fun addPeriodicEvent(responseFunction: (message: Message) -> Unit, period: Int): EventTimer {
        val et = EventTimer(responseFunction, period, true, System.currentTimeMillis())
        eventTimers.add(et)
        serverThread?.interrupt()    // Force a immediate assessment of the timing
        return et
    }

    public fun setSyncOffset(delta: Int, otherEvent: EventTimer) {
        // Adjust the sync time so that events don't happen too closely.

        // The new sync time will be relative to the sync time of the otherEventId.
    }

    public fun cancelEvent(eventTimer: EventTimer) {
        // TODO: Delete event from timerLookup
    }

    // Also allow events to tbe queried by id, for expected delays, last run etc...
}