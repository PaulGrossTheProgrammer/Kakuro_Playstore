package game.paulgross.kakuroplaystore

class TimingServer: Thread() {

    // TODO - change the second String to the new Type that stores the timing data.
    private val timerLookup = mutableMapOf<String,String>()

    override fun run() {
        // TODO - run until shutdown, sleeping between known events.

        // Adding a new timer to timerLookup will wake the thread and force a recalc
        // of the sleep time.
    }

    public fun addSingleEvent(eventName: String) {
        // TODO
    }

    public fun addPeriodicEvent(eventName: String) {
        // TODO
    }
}