package game.paulgross.kakuroplaystore

class TimingServer: Thread() {

    private var running = true

    // TODO - change the second String to the new Type that stores the timing data.
    private val timerLookup = mutableMapOf<String, EventTimer>()

    data class EventTimer(val start: Long, val duration: Int, val periodic: Boolean)

    override fun run() {
        // TODO - run until shutdown, sleeping between known events.
        while (running) {


            // Adding a new timer to timerLookup will wake the thread and force a recalc
            // of the sleep time.

            // Iterate all the timer tasks and send messages as required.
            // For each timer task, determine the sleep time required.
            // Keep track of only the shortest sleep time, and at the end, sleep for that time.
        }

        /// Shutdown everything here ... clear maps and lists etc.
    }

    public fun shutdownTimerSystem() {
        running = false
    }

    public fun addSingleEvent(eventName: String) {
        // TODO
    }

    public fun addPeriodicEvent(eventName: String) {
        // TODO
    }

    public fun cancelEvent(eventId: Int) {

    }

    // Also allow events to tbe queried by id.
}