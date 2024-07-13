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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GameEngine(): Thread() {

    private var definition: GameplayDefinition? = null
    private var activity: AppCompatActivity? = null
    var assets: AssetManager? = null
    private var preferences: SharedPreferences? = null
    private var cm: ConnectivityManager? = null
    private var gameDefVersion = ""

    fun isSetup(): Boolean {
        return activity != null
    }

    fun activate(definition: GameplayDefinition, activity: AppCompatActivity) {
        this.definition = definition
        this.activity = activity
        assets = activity.applicationContext?.assets // Used to access files in the assets directory
        preferences = activity.getPreferences(MODE_PRIVATE)  // Use to save and load the game state.
        cm = activity.applicationContext.getSystemService(ConnectivityManager::class.java)  // Used for Internet access.

        gameDefVersion = activity.applicationContext.packageManager.getPackageInfo(activity.applicationContext.packageName, 0).versionName

        Log.d(TAG, "Engine initialised with ${definition::class.java.simpleName}, version $gameDefVersion")

        if (gameIsRunning.get()) {
            println("#### Game thread already running.")
        } else {
            println("#### Game thread NOT already running, so starting it up now.")
            start()
        }
    }

    private fun deactivate() {
        Log.d(TAG, "Engine removing all references to the Activity...")
        activity = null
        assets = null
        preferences = null
        cm = null
    }

    private var socketServer: SocketServer? = null
    private var socketClient: SocketClient? = null

    private var encodeStateFunction: (() -> Message)? = null
    private var decodeStateFunction: ((Message) -> Any)? = null

    // By default the save/restore state functions will use the messages from endcode/decode state
    // But the engine user can optionally specify arbitrary functions instead of the default.
    private var saveStateFunction: (() -> Unit)? = null
    private var restoreStateFunction: (() -> Unit)? = null

    private val gameIsRunning = AtomicBoolean(false)  // TODO - this might not need to be Atomic.

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
    private var localPlayer: MutableList<(message: Message) -> Unit> = mutableListOf()  // Only used in LOCAL mode?

    data class ThreadMessageCallback(val thread: Thread, val callback: (message: Message) -> Unit)

    // TODO - combine this with the remote and local players lists...
    private var stateChangeCallbacks: MutableList<ThreadMessageCallback> = mutableListOf()

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
        val lp = cm?.getLinkProperties(cm?.activeNetwork)
        val addrs = lp?.linkAddresses
        addrs?.forEach { addr ->
            Log.d(TAG, "IP Address: $addr")
            allIpAddresses.add(addr.address.hostAddress)
        }
    }

    private var timingServer: TimingServer? = null
    // TODO - add methods so that clients can save and restore their own timers.
//    private var savedEventTimers: List<EventTimer>? = null

    override fun run() {
        gameIsRunning.set(true)

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

        // TODO: Can I make the GameDefinition solely responsible for game state changes, and make this a GameDefinition plugin instread???
        listOfSystemHandlers.add(SystemMessageHandler("RequestStateChanges", ::handleRequestStateChangesMessage))
        listOfSystemHandlers.add(SystemMessageHandler("RequestStopStateChanges", ::handleRequestStopStateChangesMessage))

        definition?.setEngine(this)  // This is where the Definition plugs in its own message handlers.

        restoreGameState()

        while (gameIsRunning.get()) {
            var im: InboundMessage? = null

            im = inboundMessageQueue.take()

            var systemStateChange = false
            var gameStateChanged = false

            if (im != null) {

                // Check System Handlers
                listOfSystemHandlers.forEach { handler ->
                    if (handler.type == im.message.type) {
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

            // TODO - if systemStateChange notify listeners...

            // TODO - can I design the GameplayDefinition such that it tracks and notifies gamestate changes???
            if (gameStateChanged) {
                saveGameState() // Maybe don't do this for fast periodic games
                pushStateToClients()
            }
        }
        Log.d(TAG, "The Game Server has shut down.")
    }

    fun getTimingServer(): TimingServer? {
        return timingServer
    }

    fun resumeTimingServer() {
        // FIXME - timing server doesn't resume saved timers after screen rotation.
        if (null != timingServer) {
            return
        }

        timingServer = TimingServer()
//        println("#### Found [${savedEventTimers?.size}] saved EventTimers.")

/*        if (savedEventTimers != null) {
            println("#### Restoring saved EventTimers.")
            timingServer!!.restoreSavedTimers(savedEventTimers!!)
        }*/
        timingServer!!.start()
    }

    fun pauseTimingServer() {
        if (null == timingServer) {
            return
        }
//        savedEventTimers = timingServer?.saveEventTimers()
//        println("#### Pausing TimeServer - saved [${savedEventTimers?.size}] EventTimers.")
        timingServer?.shutdown()
        timingServer = null
    }

    fun requestDelayedEvent(responseFunction: (message: Message) -> Unit, theType: String, delay: Int): EventTimer? {
        return timingServer?.addDelayedEvent(responseFunction, theType, delay)
    }

    fun requestPeriodicEvent(responseFunction: (message: Message) -> Unit, theType: String, period: Int): EventTimer? {
        return timingServer?.addPeriodicEvent(responseFunction, theType, period)
    }

    fun requestFinitePeriodicEvent(responseFunction: (message: Message) -> Unit, theType: String, period: Int, repeats: Int): EventTimer? {
        return timingServer?.addFinitePeriodicEvent(responseFunction, theType, period, repeats)
    }

    fun cancelEventsByType(eventType: String) {
        timingServer?.cancelEventsByType(eventType)
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

    // TODO - move this to its own class file.
    class TimingServer() : Thread() {

        private var serverThread: TimingServer? = null

        private var running = true

        private val eventTimers = mutableListOf<EventTimer>()
        private val DEFAULT_SLEEP_TIME = 60000L

        // The repeat parameter:
        // When skipped, repeat is implicitly zero and the event happens once only after the delay.
        // When a fixed number of repeats is required, use repeat as a positive integer. The repeats will have the delay period.
        // When there is no intention to stop the repeats, set repeat to -1. Effectively an infinite repeat with the delay period.
        // Note: An infinite repeat can still be stopped with a cancel message.
        class EventTimer(val responseFunction: (message: Message) -> Unit, val theType: String, val delay: Int, private val repeats: Int = 0, var syncTime: Long) {
            private var currRepeat: Int = 0

            fun incRepeats() {
                currRepeat++
            }

            fun getCurrRepeat(): Int {
                return currRepeat
            }

            fun isFinalEvent(): Boolean {
                if (repeats == -1) {
                    return false
                }
                return currRepeat >= repeats
            }
        }

        private val cancelTypeRequests = ConcurrentLinkedQueue<String>()
        private val newEventTimerQueue = ConcurrentLinkedQueue<EventTimer>()

        override fun run() {
            serverThread = this
            val deleteList = mutableListOf<EventTimer>()

            while (running) {
                // Remove cancelled types
                do {
                    val typeName = cancelTypeRequests.poll()
                    if (typeName != null) {
                        for (et in eventTimers) {
                            if (et.theType == typeName) {
                                deleteList.add(et)
                            }
                        }
                    }
                } while (typeName != null)
                eventTimers.removeAll(deleteList)
                deleteList.clear()

                // Transfer the queued EventTimers to the eventTimers list.
                do {
                    val et = newEventTimerQueue.poll()
                    if (et != null) {
                        eventTimers.add(et)
                    }
                } while (et != null)

                var sleepTime = DEFAULT_SLEEP_TIME
                for (et in eventTimers) {
                    val now = System.currentTimeMillis()
                    val currDelay = now - et.syncTime
                    val configuredDelay = et.delay

                    if (currDelay < configuredDelay) {
                        val waitTime = configuredDelay - currDelay

                        // Sleep duration is the shortest wait time.
                        if (sleepTime > waitTime) {
                            sleepTime = waitTime
                        }
                    } else {
                        val responseMessage = Message("TimingServer")
                        responseMessage.setKeyString("type", et.theType)
                        responseMessage.setKeyString("overrun", (currDelay - configuredDelay).toString())
                        responseMessage.setKeyString("repeat", et.getCurrRepeat().toString())
                        responseMessage.setKeyString("final", et.isFinalEvent().toString())

                        // TODO - allow the response to be set up as a new Thread id if this invoke is expected to take a long time to execute.
                        et.responseFunction.invoke(responseMessage)

                        if (et.isFinalEvent()) {
                            deleteList.add(et)
                        } else {
                            // Note that the sync time is set to the ideal running time, not the actual running time.
                            // get the period remainder period from the delay
                            val syncRemainder = currDelay.rem(et.delay)
                            val newSync = et.syncTime + currDelay - syncRemainder
                            et.syncTime = newSync

                            val waitTime = et.delay - syncRemainder
                            if (sleepTime > waitTime) {
                                sleepTime = waitTime
                            }

                            et.incRepeats()
                        }
                    }
                }

                eventTimers.removeAll(deleteList)

                // Check running flag again in case it was switched off while sending messages.
                if (running) {
                    try {
                        sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        // This catch block is empty because interruptions are needed for timer updates.
                    }
                }
            }

            eventTimers.clear()
        }

        fun shutdown() {
            running = false
            serverThread?.interrupt()
        }


        // TODO - add a flag that forces a new Thread to invoke the response function.
        // MAYBE an annotation @ForceThread ???
        public fun addDelayedEvent(responseFunction: (message: Message) -> Unit, theType: String, delay: Int): EventTimer {
            val et = EventTimer(responseFunction, theType, delay, 0, System.currentTimeMillis())
            newEventTimerQueue.add(et)
            serverThread?.interrupt()
            return et
        }

        public fun addPeriodicEvent(responseFunction: (message: Message) -> Unit, theType: String, period: Int): EventTimer {
            val et = EventTimer(responseFunction, theType, period, -1, System.currentTimeMillis())
            newEventTimerQueue.add(et)
            serverThread?.interrupt()
            return et
        }

        public fun addFinitePeriodicEvent(responseFunction: (message: Message) -> Unit, theType: String, period: Int, repeats: Int): EventTimer {
            val et = EventTimer(responseFunction, theType, period, repeats, System.currentTimeMillis())
            newEventTimerQueue.add(et)
            serverThread?.interrupt()
            return et
        }

        public fun setSyncOffset(delta: Int, otherEvent: EventTimer) {
            // Adjust the sync time so that events don't happen too closely.

            // The new sync time will be relative to the sync time of the otherEventId.
        }

        public fun cancelEvent(eventTimer: EventTimer) {
            // TODO: Delete event from timerLookup
        }

        public fun cancelEventsByType(eventType: String) {
            cancelTypeRequests.add(eventType)
            serverThread?.interrupt()
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

        // Also allow events to tbe queried by type, for expected delays, last run etc...?
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
        if (responseFunction != null && gameMode == GameMode.SERVER) {
            if (!remotePlayers.contains(responseFunction)) {
                remotePlayers.add(responseFunction)
            }
        }

        if (responseFunction != null) {
            // First check that we haven't already got an request from the same thread...
            var duplicate = false
            for (threadCallback in stateChangeCallbacks) {
                if (threadCallback.thread == currentThread()) {
                    duplicate = true
                }
            }

            if (!duplicate) {
                stateChangeCallbacks.add(ThreadMessageCallback(currentThread() ,responseFunction))
            }

            // Also send the current state to ensure that the client is up to date.
            println("#### Sending initial state to client requesting state changes.")
            val newMessage = encodeStateFunction?.invoke()
            if (newMessage != null) {
                responseFunction.invoke(newMessage)
            }
        }

        return Changes(system = false, game = false)
    }

    // TODO - need a stop request for state changes.
    private fun handleRequestStopStateChangesMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        println("#### Request STOP for state changes received")

        // Remove the response function from the Set for future notifications.
        if (responseFunction != null) {
            var callback: ThreadMessageCallback? = null
            for (threadCallback in stateChangeCallbacks) {
                if (threadCallback.thread == currentThread() && threadCallback.callback == responseFunction) {
                    println("#### Request found.")
                    callback = threadCallback
                }
            }
            if (callback != null) {
                println("#### Found and removed callback request")
                stateChangeCallbacks.remove(callback)
            } else {
                println("#### FAILED TO REMOVE CALLBACK REQUEST.")
            }
        }

        return Changes(system = false, game = false)
    }

    private val engineStateChangeListeners: MutableSet<ThreadMessageCallback> = mutableSetOf()

    private fun handleRequestEngineStateChangesMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        println("#### Request for engine state changes received")
        // Put the response function in the Set for future notifications.
        if (responseFunction != null) {
            // First check that we haven't already got an identical request...
            var duplicate = false
            for (threadCallback in engineStateChangeListeners) {
                if (threadCallback.thread == currentThread()) {
                    println("#### THIS IS A DUPLICATE REQUEST.")
                    duplicate = true
                }
            }

            if (!duplicate) {
                println("#### Not a duplicate request.")
                engineStateChangeListeners.add(ThreadMessageCallback(currentThread() ,responseFunction))
                // Also send the current engine state. This assumes that a new request needs the current state.
                responseFunction.invoke(getEngineState())
            }
        }

        return Changes(system = false, game = false)
    }



    private fun handleRequestStopEngineStateChangesMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: Message) -> Unit)?): Changes {
        println("#### Request STOP for engine state changes received")

        // Remove the response function from the Set for future notifications.
        if (responseFunction != null) {
            var callback: ThreadMessageCallback? = null
            for (threadCallback in engineStateChangeListeners) {
                if (threadCallback.thread == currentThread() && threadCallback.callback == responseFunction) {
                    println("#### Request found.")
                    callback = threadCallback
                }
            }
            if (callback != null) {
                println("#### Found and removed callback request")
                engineStateChangeListeners.remove(callback)
            } else {
                println("#### FAILED TO REMOVE CALLBACK REQUEST.")
            }
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
        stateChangeCallbacks.forEach { callbackClient ->
            val newMessage = encodeStateFunction?.invoke()
            if (newMessage != null) {
                callbackClient.callback.invoke(newMessage)
            }
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

        if (gameMode == GameMode.SERVER) {
            socketServer?.shutdownRequest()
        }

        if (gameMode == GameMode.CLIENT) {
            socketClient?.shutdownRequest()
        }

        deactivate()
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
        var data = preferences?.getString(name, null)
        if (data == null) {
            data = default
        }
        return data
    }

    fun saveDataString(name: String, value: String) {
        val editor = preferences?.edit()
        editor?.putString(name, value)
        editor?.apply()
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
        this.encodeStateFunction = encodeStateFunction
    }

    fun pluginDecodeState(decodeStateFunction: (message: Message) -> Any) {
        this.decodeStateFunction = decodeStateFunction
    }

    fun pluginSaveState(saveStateFunction: () -> Unit) {
        this.saveStateFunction = saveStateFunction
    }

    fun pluginRestoreState(restoreStateFunction: () -> Unit) {
        this.restoreStateFunction = restoreStateFunction
    }

    fun queueMessageFromActivity(message: Message, responseFunction: ((message: Message) -> Unit)?) {
        val im = InboundMessage(message, InboundMessageSource.APP, responseFunction)
        inboundMessageQueue.add(im)
    }

    fun queueMessageFromClient(message: Message, responseFunction: (message: Message) -> Unit) {
        val im = InboundMessage(message, InboundMessageSource.CLIENT, responseFunction)
        singletonGameEngine.inboundMessageQueue?.add(im)
    }

    fun queueMessageFromClientHandler(message: Message, responseFunction: (message: Message) -> Unit) {
        val im = InboundMessage(message, InboundMessageSource.CLIENTHANDLER, responseFunction)
        singletonGameEngine.inboundMessageQueue?.add(im)
    }

    companion object {
        private val TAG = GameEngine::class.java.simpleName

        val messageNoStateChange = Message("NoStateChange")
        val messageStateChange = Message("StateChange")

        private var singletonGameEngine: GameEngine = GameEngine()

        fun activate(definition: GameplayDefinition, activity: AppCompatActivity): GameEngine {
            if (!singletonGameEngine.isSetup()) {
                Log.d(TAG, "Activating GameEngine ...")
                singletonGameEngine.activate(definition, activity)
            }

            return singletonGameEngine!!
        }

        fun get(): GameEngine {
            return singletonGameEngine
        }
    }
}