package game.paulgross.kakuroplaystore

import game.paulgross.kakuroplaystore.GameEngine.Message

class TimingServer: Thread() {

    // TODO - move this to inside the game engine, and all it to be requested.
    // The game engine will start it and shut it down as required.
    // The game engine will pause and unpause it as required too. Need to research and test that feature.

    private var running = true

    private val timerLookup = mutableMapOf<String, EventTimer>()

    data class EventTimer(var id: Int, val responseFunction: (message: Message) -> Unit, val start: Long, val duration: Int, val periodic: Boolean)

    override fun run() {
        var sleepTime = 10000L // Default 10 seconds
                // TODO - run until shutdown, sleeping between known events.
        while (running) {


            // Adding a new timer to timerLookup will wake the thread and force a recalc
            // of the sleep time.

            // Iterate all the timer tasks
            // send messages as required. Each message contains the delay time in case the message is late.
            // The delay time is the milliseconds between the requested event time and the actual message time.
            // The special -1 delay time is for new events and also just after the system leaves pause mode.

            // For each timer task, determine the sleep time required.
            // Keep track of only the shortest sleep time, and at the end, sleep for that time.

            println("TimingServer sleeping for $sleepTime milliseconds")
            sleep(sleepTime)
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

    public fun setSyncOffset(delta: Int, otherEventId: Int) {
        // Adjust the sync time so that events don't happen too closely.

        // The new sync time will be relative to the sync time of the otherEventId.
    }

    public fun cancelEvent(eventId: Int) {

    }

    // Also allow events to tbe queried by id.
}