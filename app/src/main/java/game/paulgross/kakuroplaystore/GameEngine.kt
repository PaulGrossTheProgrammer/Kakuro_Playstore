package game.paulgross.kakuroplaystore

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.text.toUpperCase
import game.paulgross.kakuroplaystore.GameEngine.TimingServer.EventTimer
import java.lang.Exception
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class GameEngine( private val definition: GameplayDefinition, activity: AppCompatActivity): Thread() {

    private val cm: ConnectivityManager = activity.applicationContext.getSystemService(ConnectivityManager::class.java)  // Used for Internet access.
    private val preferences: SharedPreferences = activity.getPreferences(MODE_PRIVATE)  // Use to save and load the game state.
    val assets: AssetManager = activity.applicationContext.assets // Used to access files in the assets directory
    private var gameDefVersion = ""  // TODO - can paste the code in the init bock here?

    init {
        gameDefVersion = activity.applicationContext.packageManager.getPackageInfo(activity.applicationContext.packageName, 0).versionName

        Log.d(TAG, "Engine initialised with ${definition::class.java.simpleName}, version $gameDefVersion")
    }

    private var socketServer: SocketServer? = null
    private var socketClient: SocketClient? = null

    private var encodeStateFunction: (() -> Message)? = null
    private var decodeStateFunction: ((Message) -> Any)? = null

    // By default the save/restore state functions will use the messages from endcode/decode state
    // But the engine user can optionally specify arbitrary functions instead of the default.
    private var saveStateFunction: (() -> Unit)? = null
    private var restoreStateFunction: (() -> Unit)? = null

    private val gameIsRunning = AtomicBoolean(true)  // TODO - this might not need to be Atomic.

    // We use a BlockingQueue here to block thread progress if needed.
    // https://developer.android.com/reference/java/util/concurrent/BlockingQueue
    private val inboundMessageQueue: BlockingQueue<InboundMessage> = LinkedBlockingQueue()

    private enum class InboundMessageSource {
        APP, CLIENT, CLIENTHANDLER
    }

    private data class Changes(val system: Boolean, val game: Boolean)

    private data class InboundMessage(
        val message: Message,
        val source: InboundMessageSource,
        val responseFunction: ((message: Message) -> Unit)?
    )

    enum class GameMode {
        /** Game only responds to messages within the App. */
        LOCAL,

        /** Allow remote users to play by joining this GameEngine over the network. */
        SERVER,

        /** Joined a network GameEngine. */
        CLIENT
    }
    private var gameMode: GameMode = GameMode.LOCAL

    // TODO merge this with the state change callbacks???
    private var remotePlayers: MutableList<(message: Message) -> Unit> = mutableListOf()  // Only used in SERVER mode.
    private var localPlayer: MutableList<(message: Message) -> Unit> = mutableListOf()  // Only used in SERVER mode.

    // TODO - combine this with the remote and local players lists...
    private var stateChangeCallbacks: MutableList<(message: Message) -> Unit> = mutableListOf()

    data class ClientRequest(val requestString: String, val responseQ: Queue<String>)

    private val allIpAddresses: MutableList<String> = mutableListOf()

    private data class MessageHandler(val type: String, val handlerFunction: (message: Message) -> Message)
    private data class SystemMessageHandler(val type: String,
                                            val handlerFunction: (message: Message, source: InboundMessageSource, ((message: Message) -> Unit)?) -> Changes)

    private val listOfSystemHandlers: MutableList<SystemMessageHandler> = mutableListOf()
    private val listOfGameHandlers: MutableList<MessageHandler> = mutableListOf()

    fun registerHandler(type: String, handlerFunction: (message: Message) -> Message) {
        // TODO - throw exceptions for overwriting existing types.
        listOfGameHandlers.add(MessageHandler(type, handlerFunction))
    }

    private fun determineIpAddresses() {
        // FUTURE: Need to monitor the network and react to IP address changes.
        allIpAddresses.clear()
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val addrs = lp?.linkAddresses
        addrs?.forEach { addr ->
            Log.d(TAG, "IP Address: $addr")
            allIpAddresses.add(addr.address.hostAddress)
        }
    }

    private var loopDelayMilliseconds = -1L  // -1 means disable looping,

    private var timingServer: TimingServer? = null
    private var savedEventTimers: List<EventTimer>? = null

    override fun run() {
//        timingServer.startup()
        resumeTimingServer()

        // Register all the reserved system messages
        listOfSystemHandlers.add(SystemMessageHandler("Shutdown", ::handleShutdownMessage))
        listOfSystemHandlers.add(SystemMessageHandler("Abandoned", ::handleAbandonedMessage))
        listOfSystemHandlers.add(SystemMessageHandler("Reset", ::handleResetMessage))
        listOfSystemHandlers.add(SystemMessageHandler("StartServer", ::handleStartServerMessage))
        listOfSystemHandlers.add(SystemMessageHandler("StartLocal", ::handleStartLocalMessage))
        listOfSystemHandlers.add(SystemMessageHandler("RemoteServer", ::handleRemoteServerMessage))
        listOfSystemHandlers.add(SystemMessageHandler("StopGame", ::handleStopGameMessage))
        listOfSystemHandlers.add(SystemMessageHandler("RequestEngineStateChanges", ::handleRequestEngineStateChangesMessage))
        listOfSystemHandlers.add(SystemMessageHandler("RequestStopEngineStateChanges", ::handleRequestStopEngineStateChangesMessage))
        listOfSystemHandlers.add(SystemMessageHandler("RequestStateChanges", ::handleRequestStateChangesMessage))

        definition.setEngine(this)  // This is where the Definition plugs in its own message handlers.

        restoreGameState()

        while (gameIsRunning.get()) {
            var im: InboundMessage? = null

            if (loopDelayMilliseconds < 0) {
                // We are NOT using a loop delay, so WAIT HERE for messages ...
                im = inboundMessageQueue.take()
            } else {
                // We are using a loop delay, so DON'T WAIT HERE for messages, just test to see if one is available ...
                im = inboundMessageQueue.poll()
            }

            var systemStateChange = false
            var gameStateChanged = false

            if (im != null) {

                // Check System Handlers
                listOfSystemHandlers.forEach { handler ->
                    if (handler.type == im.message.type) {
                        Log.d(TAG, "Handling SYSTEM message: ${im.message.type}")
                        val changes = handler.handlerFunction.invoke(im.message, im.source, im.responseFunction)
                        if (changes.system) {
                            systemStateChange = true
                        }
                        if (changes.game) {
                            gameStateChanged = true
                        }
                    }
                }

                // Check game messages.
                listOfGameHandlers.forEach { handler ->
                    if (handler.type == im.message.type) {
                        val message = handler.handlerFunction.invoke(im.message)
                        if (message == messageStateChange) {
                            gameStateChanged = true
                        }

                        // Handle custom messages, which will be passed back to the caller.
                        if (message != messageStateChange && message != messageNoStateChange) {
                            if (message.getString("StateChanged").toString().equals("true", ignoreCase = true)) {
                                gameStateChanged = true
                            }
                            im.responseFunction?.invoke(message)
                        }
                    }
                }
            }

            if (loopDelayMilliseconds > 0) {
                // TODO - call the optional periodic game actions
//                stateChanged = actionFunction.invoke()...
            }

            // TODO - if systemStateChange notify listeners...

            if (gameStateChanged) {
                saveGameState() // Maybe don't do this for fast periodic games
                pushStateToClients()
            }

            if (loopDelayMilliseconds > 0) {
                sleep(loopDelayMilliseconds)
            }
        }
        Log.d(TAG, "The Game Server has shut down.")
    }

    fun resumeTimingServer() {
        println("#### resumeTimingServer() running...")
        if (null != timingServer) {
            return
        }

        println("#### resumeTimingServer() creating a new TimingServer()")
        timingServer = TimingServer()
        // TODO: Apply any saved TimerEvents to the server
        if (savedEventTimers != null) {
            timingServer?.restoreSavedTimers(savedEventTimers!!)
        }
        timingServer?.start()

        // FIXME - this doesn't work. Can't call start() more than once.
        // Need to create a new object.
//        timingServer.startup()

/*        if (!timingServer.running) {
            timingServer.running = true
            timingServer.run()
        }*/
    }

    fun pauseTimingServer() {
        if (null == timingServer) {
            return
        }
        // TODO - store any EventTimers
        savedEventTimers = timingServer?.saveEventTimers()
        timingServer?.shutdown()
        timingServer = null
    }

    fun requestDelayedEvent(responseFunction: (message: Message) -> Unit, theType: String, delay: Int): EventTimer? {
        return timingServer?.addSingleEvent(responseFunction, theType, delay)
    }

    fun requestPeriodicEvent(responseFunction: (message: Message) -> Unit, theType: String, period: Int): EventTimer? {
        return timingServer?.addPeriodicEvent(responseFunction, theType, period)
    }

    private fun switchToRemoteServerMode(address: String) {
        // FIXME - doesn't handle when the remote server isn't running...
        // TODO - implement a timeout when attempting to join a remote game.

        Log.d(TAG, "Switch to Remote Server at: $address")
        if (socketServer != null) {
            socketServer?.shutdownRequest()
            allIpAddresses.clear()
            socketServer = null
            remotePlayers.clear()
        }

        try {
            socketClient = SocketClient(this, address, SocketServer.PORT)
            socketClient!!.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        gameMode = GameMode.CLIENT
    }

    private fun switchToLocalServerMode() {
        if (socketClient != null) {
            socketClient?.shutdownRequest()
            socketClient = null
        }

        remotePlayers.clear()
        socketServer = SocketServer(this)
        socketServer!!.start()
        determineIpAddresses()

        gameMode = GameMode.SERVER
    }

    private fun switchToPureLocalMode() {
        if (socketServer != null) {
            socketServer?.shutdownRequest()
            allIpAddresses.clear()
            socketServer = null
            remotePlayers.clear()
        }

        if (socketClient != null) {
            socketClient?.shutdownRequest()
            socketClient = null
        }

        gameMode = GameMode.LOCAL
    }

    class Message(val type: String) {
        // TODO - MAYBE add standard encoders and decoders to convert to and from Strings.

        // TODO - add a new method that allows the raw data to be sent as well as the string version
        // TODO - Only convert the raw to the string version if required

        private var body: MutableMap<String, String>? = null

        fun setKeyString(key: String, value: String) {
            if (body == null) {
                body = mutableMapOf<String, String>()
            }
            body!!.put(key, value)
        }

        fun getString(key: String): String? {
            return body?.get(key)
        }

        fun hasString(s: String): Boolean {
            if (body?.get(s) != null) {
                return true
            }
            return false
        }

        fun missingString(s: String): Boolean {
            if (body?.get(s) != null) {
                return false
            }
            return true
        }

        fun asString(): String {
            var theString = "MessageType=$type"
            body?.forEach { (partName, partValue) ->
                theString += ",$partName=$partValue"
            }

            return theString
        }

        companion object {
            fun decodeMessage(message: String): Message {
                Log.d(TAG, "decodeMessage: $message")

                var type = ""
                val messageBody = mutableMapOf<String, String>()

                if (message.indexOf("=") == -1) {
                    val gm = Message("FormatError")
                    gm.setKeyString("ErrorMessage", "Expected 'MessageType'")
                    gm.setKeyString("SentMessage", message)
                    return gm
                }

                val parts: List<String> = message.split(",")
                parts.forEach { pair ->
                    val keyValue = pair.split("=")
                    if (keyValue[0] == "MessageType") {
                        type = keyValue[1]
                    } else {
                        messageBody[keyValue[0]] = keyValue[1]
                    }
                }

                if (type == "") {
                    val gm = Message("FormatError")
                    gm.setKeyString("ErrorMessage", "Expected 'MessageType'")
                    gm.setKeyString("SentMessage", message)
                    return gm
                }

                val gm = Message(type)
                gm.setKeyString("ErrorMessage", "Expected 'MessageType'")
                gm.setKeyString("SentMessage", message)

                messageBody.forEach { (key, value) ->
                    gm.setKeyString(key, value)
                }

                return gm
            }
        }
    }


    // FIXME - when pausing the App, copy the state of all the eventTimers, and exit the run function.
    // Then when resuming, create a new TimingServer using the saved eventTimers.
    class TimingServer() : Thread() {

        var serverThread: TimingServer? = null

        // TODO - move this to inside the game engine, and allow it to be requested with a factory function.
        // The game engine will start it and shut it down as required.
        // The game engine will pause and unpause it as required too. Need to research and test that feature.

        var running = true

        private val eventTimers = mutableListOf<EventTimer>()
        val DEFAULT_SLEEP_TIME = 60000L  // TODO - maybe make this longer?

        data class EventTimer(val responseFunction: (message: Message) -> Unit, val theType: String, val delay: Int, val periodic: Boolean, var syncTime: Long)

        override fun run() {
            serverThread = this
            println("#### STARTING TIMER SYSTEM.")

            while (running) {

                // Adding a new timer to eventTimers will wake the thread and force a recalc
                // of the sleep time.

                // Iterate all the timer tasks
                // send messages as required. Each message contains the delay time in case the message is late.
                // The delay time is the milliseconds between the requested event time and the actual message time.
                // The special -1 delay time is for new events and also just after the system leaves pause mode???

                // For each timer task, determine the sleep time required.
                // Keep track of only the shortest sleep time, and at the end, sleep for that time.
                // Note that there may not be any work to do while awake because a timer event may have been deleted.

                val deleteList = mutableListOf<EventTimer>()

                var sleepTime = DEFAULT_SLEEP_TIME
                println("Processing ${eventTimers.size} EventTimers ...")
                for (et in eventTimers) {
                    val now = System.currentTimeMillis()
                    val currDelay = now - et.syncTime
                    val configuredDelay = et.delay
                    println("#### Synctime ${et.syncTime}, currDelay = $currDelay vs configuredDelay ${et.delay}.")

                    if (currDelay < configuredDelay) {
                        val waitTime = configuredDelay - currDelay

                        // Only sleep for the shortest wait time.
                        // FIXME - this doesn't work for periodic events ...
                        if (waitTime < sleepTime) {
                            sleepTime = waitTime
                            println("#### New sleep time $sleepTime.")
                        }
                    } else {
                        val responseMessage = Message("TimingServer")
                        responseMessage.setKeyString("type", et.theType)
                        responseMessage.setKeyString("overrun", (currDelay - configuredDelay).toString())
                        et.responseFunction.invoke(responseMessage)

                        if (!et.periodic) {
                            deleteList.add(et)
                        } else {
                            // For periodic, update sync time
                            // Note that the sync time is set to the ideal running time, not the actual running time.
                            // get the period remainder period from the delay
                            val syncRemainder = currDelay.rem(et.delay)
                            val newSync = currDelay - syncRemainder
                            println("Setting the periodic sync to: $newSync")
                            et.syncTime = newSync

                            val waitTime = newSync + currDelay
                            // Only sleep for the shortest wait time.
                            if (waitTime < sleepTime) {
                                sleepTime = waitTime
                                println("#### New sleep time $sleepTime.")
                            }
                        }
                    }
                }

                eventTimers.removeAll(deleteList)

/*
                if (eventTimers.isEmpty()) {
                    sleepTime = DEFAULT_SLEEP_TIME
                }
*/

                // Check running flag again in case it was switched off while sending messages.
                if (running) {
                    println("#### TimingServer sleeping for $sleepTime milliseconds.")
                    try {
                        sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        println("#### TimingServer was INTERRUPTED while sleeping.")
                    }
                }
            }

            // If shutdown is false skip this, so the system can be resumed by calling run() again.
            println("#### TimingServer is SHUTDOWN.")
            // Shutdown everything here ... clear maps and lists etc.
            // ?? Iterate each EventTimer and send a shutdown message to the callbacks???

            eventTimers.clear()
        }

        fun shutdown() {
            running = false
            serverThread?.interrupt()
        }

/*
        fun startup(savedEventTimers: List<EventTimer>) {
            // Check if there is a current reference to the


            // Check the running flag to ensure we don't create a new Thread while already running the Timer Thread.
            if (!running) {
                running = true
                // FIXME - CANNOT call start() again.
                // Possible solution - don't exit the  run loop, but stay in witht long sleep, then interrupt to resume processing
                // start()  // Create the new Thread that executes run()
            }
        }
*/

        /**
         * Temporarily stop processing Events.
         */
        fun pause() {
            running = false
            serverThread?.interrupt()
        }

        public fun addSingleEvent(responseFunction: (message: Message) -> Unit, theType: String, delay: Int): EventTimer {
            val et = EventTimer(responseFunction, theType, delay, false, System.currentTimeMillis())
            eventTimers.add(et)
            serverThread?.interrupt()    // Force a immediate assessment of the timing
            return et
        }

        public fun addPeriodicEvent(responseFunction: (message: Message) -> Unit, theType: String, period: Int): EventTimer {
            val et = EventTimer(responseFunction, theType, period, true, System.currentTimeMillis())
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

        fun restoreSavedTimers(savedEventTimers: List<GameEngine.TimingServer.EventTimer>) {
            eventTimers.addAll(savedEventTimers)
        }

        fun saveEventTimers(): List<EventTimer>? {
            if (eventTimers.isEmpty()) {
                return null
            }
            val ets = mutableListOf<EventTimer>()
            ets.addAll(eventTimers)
            return ets
        }

        // Also allow events to tbe queried by id, for expected delays, last run etc...
    }

    private fun handleShutdownMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.CLIENTHANDLER) {
            remotePlayers.remove(responseFunction)

            responseFunction?.invoke(message)  // Echo back the message type
        }

        if (source == InboundMessageSource.CLIENT) {
            switchToPureLocalMode()
        }

        return Changes(system = true, game = false)
    }

    private fun handleAbandonedMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.CLIENTHANDLER) {
            remotePlayers.remove(responseFunction)
        }

        if (source == InboundMessageSource.CLIENT) {
            switchToPureLocalMode()
        }
        return Changes(system = false, game = false)
    }

    private fun handleResetMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.APP) {
            resetGame()
        }
        return Changes(system = false, game = true)
    }

    private fun handleStartServerMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.APP && gameMode != GameMode.SERVER) {
            switchToLocalServerMode()
        }
        return Changes(system = true, game = false)
    }

    private fun handleRemoteServerMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.APP && gameMode != GameMode.CLIENT) {
            val address = message.getString("Address")
            if (address != null) {
                switchToRemoteServerMode(address)
                return Changes(system = true, game = false)
            }
        }
        return Changes(system = false, game = false)
    }

    private fun handleStartLocalMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.APP && gameMode != GameMode.LOCAL) {
            switchToPureLocalMode()
            return Changes(system = true, game = false)
        }
        return Changes(system = false, game = false)
    }

    private fun handleStopGameMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        if (source == InboundMessageSource.APP) {
            stopGame()
            return Changes(system = true, game = false)
        }
        return Changes(system = false, game = false)
    }

    private fun handleRequestStateChangesMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        Log.d(TAG, "handleRequestStateChangesMessage ...")
        if (responseFunction != null && gameMode == GameMode.SERVER) {
            if (!remotePlayers.contains(responseFunction)) {
                remotePlayers.add(responseFunction)
            }
        }

        if (responseFunction != null) {
            if (!stateChangeCallbacks.contains(responseFunction)) {
                Log.d(TAG,"Adding caller to stateChangeCallbacks ...")
                stateChangeCallbacks.add(responseFunction)
                // Assume that the caller does NOT have the current state.

                if (encodeStateFunction != null) {
                    val newMessage = encodeStateFunction?.invoke()
                    if (newMessage != null) {
                        Log.d(TAG, "Sending ${newMessage.asString()}")
                        responseFunction.invoke(newMessage)
                    }
                }
                encodeStateFunction?.invoke()?.let { responseFunction?.invoke(it) }
            }
        }

        return Changes(system = false, game = false)
    }

    private val engineStateChangeListeners: MutableSet<(message: Message) -> Unit> = mutableSetOf()

    private fun handleRequestEngineStateChangesMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        Log.d(TAG, "Request for engine state changes received")

        // Put the response function in the Set for future notifications.
        if (responseFunction != null) {
            engineStateChangeListeners.add(responseFunction)
            // Also send the current engine state. This assumes that a new request needs the current state.

            responseFunction.invoke(getEngineState())
        }

        return Changes(system = false, game = false)
    }

    private fun handleRequestStopEngineStateChangesMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        // TODO
        Log.d(TAG, "Request STOP for engine state changes received")

        // Remove the response function from the Set for future notifications.
        if (responseFunction != null) {
            engineStateChangeListeners.remove(responseFunction)
        }

        return Changes(system = false, game = false)
    }

    private fun getEngineState(): Message {
        val newMessage = Message("EngineState")
        newMessage.setKeyString("GameMode", gameMode.toString())
        newMessage.setKeyString("IPAddress", encodeIpAddresses())
        newMessage.setKeyString("Clients", remotePlayers.size.toString())

        return newMessage
    }

    fun encodeIpAddresses(): String {
        var ips = ""
        allIpAddresses.forEach { add ->
            if (ips.isNotEmpty()) {
                ips += ","
            }
            ips += add
        }

        return ips
    }

    fun decodeIpAddresses(data: String): List<String> {
        val theList = data.split(",")

        return theList
    }

    private fun pushStateToClients() {
        Log.d(TAG, "Pushing State to clients...")
        stateChangeCallbacks.forEach { callback ->
            // TODO - figure out what the hell this syntax actually means???!!!!
            encodeState()?.let { callback(it) }
        }
    }

    fun gotoSettingsActivity(context: Context) {
        val intent = Intent(context, GameEngineSettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        intent.putExtra("SelectedSetting", "PRIVACYPOLICY")
        context.startActivity(intent)
    }

    private fun stopGame() {
        Log.d(TAG, "The Game Server is shutting down ...")
        timingServer?.shutdown()

        gameIsRunning.set(false)

        if (gameMode == GameMode.SERVER) {
            socketServer?.shutdownRequest()
        }

        if (gameMode == GameMode.CLIENT) {
            socketClient?.shutdownRequest()
        }

        singletonGameEngine = null
    }

    private fun saveGameState() {
        if (saveStateFunction != null) {
            saveStateFunction?.invoke()
        } else {
            // TODO: If there is no specified saveStateFunction() function,
            // call the encode state function and write to permanent storage instead.
            // The save key is "SavedState".
            // saveDataString("SavedState", encodeState())
        }
    }

    /**
     * Restores the Game state from the last time it was saved.
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")

        if (restoreStateFunction != null) {
            restoreStateFunction?.invoke()

            // TODO: Is this needed for multiplayer mode
            // pushStateToClients(encodeState()) ??? Might make a redundant calls to Activity???
        } else {
            // TODO: If there is no specified restoreStateFunction() function,
            // restoredGameMessage = loadDataString("SavedState", null)
            // Do we push this message just to the Activity?
            // Or use a special pushStateToClients() with the new state??
            // pushStateToClients(restoredGameMessage) - this should update the activity.
        }
    }

    fun loadDataString(name: String, default: String): String {
        var data = preferences.getString(name, null)
        if (data == null) {
            data = default
        }
        return data
    }

    fun saveDataString(name: String, value: String) {
        val editor = preferences.edit()
        editor.putString(name, value)
        editor.apply()
    }

    private fun resetGame() {
        // TODO: Plugin a reset game function here....???

        saveGameState()
        pushStateToClients()
    }

    private fun encodeState(): Message? {
        if (encodeStateFunction != null) {
            return encodeStateFunction?.invoke()
        }
        return null
    }

    fun decodeState(message: Message): Any? {
        if (decodeStateFunction != null) {
            return decodeStateFunction?.invoke(message)
        }
        return null
    }

    fun pluginEncodeState(encodeStateFunction: () -> Message) {
        Log.d(TAG, "Plugging in encode state function...")
        this.encodeStateFunction = encodeStateFunction
    }

    fun pluginDecodeState(decodeStateFunction: (message: Message) -> Any) {
        Log.d(TAG, "Plugging in encode state function...")
        this.decodeStateFunction = decodeStateFunction
    }

    fun pluginSaveState(saveStateFunction: () -> Unit) {
        Log.d(TAG, "Plugging in save puzzle function...")
        this.saveStateFunction = saveStateFunction
    }

    fun pluginRestoreState(restoreStateFunction: () -> Unit) {
        Log.d(TAG, "Plugging in restore puzzle function...")
        this.restoreStateFunction = restoreStateFunction
    }

    fun queueMessageFromActivity(message: Message, responseFunction: ((message: Message) -> Unit)?) {
        val im = InboundMessage(message, InboundMessageSource.APP, responseFunction)
        inboundMessageQueue.add(im)
    }

    fun queueMessageFromClient(message: Message, responseFunction: (message: Message) -> Unit) {
        val im = InboundMessage(message, InboundMessageSource.CLIENT, responseFunction)
        singletonGameEngine?.inboundMessageQueue?.add(im)
    }

    fun queueMessageFromClientHandler(message: Message, responseFunction: (message: Message) -> Unit) {
        val im = InboundMessage(message, InboundMessageSource.CLIENTHANDLER, responseFunction)
        singletonGameEngine?.inboundMessageQueue?.add(im)
    }

    companion object {
        private val TAG = GameEngine::class.java.simpleName

        val messageNoStateChange = Message("NoStateChange")
        val messageStateChange = Message("StateChange")

        // For the moment, just permit the Singleton instance.
        private var singletonGameEngine: GameEngine? = null

        // FUTURE: Allocate multiple instances based on a game identifier and definition.
        fun activate(definition: GameplayDefinition, activity: AppCompatActivity): GameEngine {
            if (singletonGameEngine == null) {
                Log.d(TAG, "Starting new GameEngine ...")
                singletonGameEngine = GameEngine(definition, activity)
                // TODO - Do I reactivate the Timer system here???
                singletonGameEngine!!.resumeTimingServer()
                singletonGameEngine!!.start()
            } else {
                Log.d(TAG, "Already created GameEngine.")
            }

            return singletonGameEngine!!
        }

        fun get(): GameEngine? {
            if (singletonGameEngine == null) {
                Log.d(TAG, "GameEngine has not yet been activated.")
                return null
            }
            return singletonGameEngine
        }
    }
}