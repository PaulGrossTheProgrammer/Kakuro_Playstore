package game.paulgross.kakuroplaystore

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.util.Log
import java.lang.Exception
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class GameEngine(private val cm: ConnectivityManager, private val preferences: SharedPreferences, private val definition: GameplayDefinition): Thread() {

    private var socketServer: SocketServer? = null
    private var socketClient: SocketClient? = null

    private val gameIsRunning = AtomicBoolean(true)  // TODO - this might not need to be Atomic.

    // We use a BlockingQueue here to block thread progress if needed.
    // https://developer.android.com/reference/java/util/concurrent/BlockingQueue
    private val inboundMessageQueue: BlockingQueue<InboundMessage> = LinkedBlockingQueue()

    //  TODO: Remove static use of this class by passing a function to each source
    //   so that the function automatically identifies the source.
    enum class InboundMessageSource {
        APP, CLIENT, CLIENTHANDLER
    }

    data class InboundMessage(
        val message: Message,
        val source: InboundMessageSource,
        val responseFunction: ((message: String) -> Unit)?
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

    // TDSO merge this with the state change callbacks???
    private var remotePlayers: MutableList<(message: String) -> Unit> = mutableListOf()  // Only used in SERVER mode.
    private var localPlayer: MutableList<(message: String) -> Unit> = mutableListOf()  // Only used in SERVER mode.

    data class ClientRequest(val requestString: String, val responseQ: Queue<String>)

    private val allIpAddresses: MutableList<String> = mutableListOf()

    private data class MessageHandler(val type: String, val handlerFunction: (message: Message) -> Boolean)
    private data class SystemMessageHandler(val type: String, val handlerFunction: (message: Message, source: InboundMessageSource, ((message: String) -> Unit)?) -> Boolean)

    private val listOfSystemHandlers: MutableList<SystemMessageHandler> = mutableListOf()
    private val listOfGameHandlers: MutableList<MessageHandler> = mutableListOf()

    fun registerHandler(type: String, handlerFunction: (message: Message) -> Boolean) {
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

    override fun run() {
        // TODO - register all the reserved system commands
        listOfSystemHandlers.add(SystemMessageHandler("Shutdown", ::handleShutdownMessage))
        listOfSystemHandlers.add(SystemMessageHandler("Abandoned", ::handleAbandonedMessage))

        definition.setEngine(this)

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

            var stateChanged = false

            if (im != null) {

                // TODO - consolidate these three system handler functions...
                // Handle system messages.
                if (im.source == InboundMessageSource.APP) {
                    handleActivityMessage(im)
                }
                if (im.source == InboundMessageSource.CLIENT) {
                    handleClientMessage(im)
                }
                if (im.source == InboundMessageSource.CLIENTHANDLER) {
                    handleClientHandlerMessage(im)
                }

                // Handle game messages.
                listOfGameHandlers.forEach { handler ->
                    if (handler.type == im.message.type) {
                        val changed = handler.handlerFunction.invoke(im.message)
                        if (changed) {
                            stateChanged = true
                        }
                    }
                }

            }

            if (loopDelayMilliseconds > 0) {
                // TODO - call the optional periodic game actions

            }

            if (stateChanged) {
                saveGameState()
                pushStateToClients()
            }

            if (loopDelayMilliseconds > 0) {
                sleep(loopDelayMilliseconds)
            }
        }
        Log.d(TAG, "The Game Server has shut down.")
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
            socketClient = SocketClient(address, SocketServer.PORT)
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
        socketServer = SocketServer()
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

//    private var previousStateUpdate = ""  // TODO - this should only be in GameplayDefinition.

    class Message(val type: String) {
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

    fun handleShutdownMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: String) -> Unit)?): Boolean {
        if (source == InboundMessageSource.CLIENTHANDLER) {
            remotePlayers.remove(responseFunction)

            responseFunction?.invoke(message.type)  // Echo back the message type
        }

        if (source == InboundMessageSource.CLIENT) {
            switchToPureLocalMode()
        }

        return false // No change made to the game state
    }

    fun handleAbandonedMessage(message: Message, source: InboundMessageSource, responseFunction: ((message: String) -> Unit)?): Boolean {
        if (source == InboundMessageSource.CLIENTHANDLER) {
            remotePlayers.remove(responseFunction)
        }

        if (source == InboundMessageSource.CLIENT) {
            switchToPureLocalMode()
        }

        return false // No change made to the game state
    }

    private fun handleClientHandlerMessage(im: InboundMessage) {

        var validRequest = false

        // TODO - use RequestStateChanges instead.
        if (im.message.type == "Initialise") {
            validRequest = true

            // Add the response function to the list of remote clients
            im.responseFunction?.let { remotePlayers.add(it) }
        }

            // Is this even needed???
//        if (im.message.type == "Status") {
//            validRequest = true
//            im.responseQueue!!.add("state,${encodeState()}")
//        }
        if (im.message.type == "Shutdown" || im.message.type == "Abandoned") {
            validRequest = true
            remotePlayers.remove(im.responseFunction)

            if (im.message.type == "Shutdown") {
                // Echo the shutdown back to the socket
                im.responseFunction?.invoke(im.message.type)
            }
        }

        if (!validRequest) {
            Log.d(TAG, "invalid message: [${im.message}]")
        }
    }

    private fun handleClientMessage(im: InboundMessage) {
        // This might be unnecessary... already handled by Gameplay Definition
        if (im.message.type == "State") {
            val remoteState = im.message.getString("State")

            Log.d(TAG, "REMOTE Game Server sent state change: [$remoteState]")

            saveGameState()

            // TODO - this should just be the local Activity.
            //  A client has no remote clients by definition.
            pushStateToClients()
        }

        if (im.message.type == "Shutdown" || im.message.type == "Abandoned") {
//            remotePlayers.remove(im.responseFunction)
            switchToPureLocalMode()
        }

    }

    // TODO - combine this with the remote and local players lists...
    private var stateChangeCallbacks: MutableList<(message: String) -> Unit> = mutableListOf()

    private fun handleActivityMessage(im: InboundMessage) {

        var stateChanged = false
        if (im.message.type == "RequestStateChanges") {
            Log.d(TAG, "RequestStateChanges received...")
            if (im.responseFunction != null) {
                stateChangeCallbacks.add(im.responseFunction)

                Log.d(TAG, "Invoking the response function....")
                // Assume that the caller does NOT have the current state.
                im.responseFunction?.invoke("MessageType=State,${encodeState()}")
            }
        }

        // TODO - need a cancel state update requests message too...

        if (im.message.type == "Reset") {
            resetGame()
            stateChanged = true
        }
//        if (im.message.type == "Status") {
//            im.responseFunction?.invoke("MessageType=State,${encodeState()}")
//        }

        if (im.message.type == "StartServer") {
            if (gameMode != GameMode.SERVER) {
                switchToLocalServerMode()
            }
        }
        if (im.message.type == "StartLocal") {
            if (gameMode != GameMode.LOCAL) {
                switchToPureLocalMode()
            }
        }
        if (im.message.type == "RemoteServer") {
            if (gameMode != GameMode.CLIENT) {
                val ip = im.message.getString("Address")
                if (ip!= null && ip != "") {
                    switchToRemoteServerMode(ip)
                }
            }
        }
        if (im.message.type == "StopGame") {
            stopGame()
        }

        if (stateChanged) {
            saveGameState()
            pushStateToClients()
        }
    }

    private fun pushStateToClients() {
        Log.d(TAG, "Pushing State to clients...")
        stateChangeCallbacks.forEach { callback ->
            // TODO - make this a Message for the local client?
            callback("MessageType=State,${encodeState()}")
        }
    }

    private fun stopGame() {
        Log.d(TAG, "The Game Server is shutting down ...")
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
        if (savePuzzleFunction != null) {
            Log.d(TAG, "Calling plugin ...")
            savePuzzleFunction?.invoke()
        }
    }

    fun restoreData(name: String, default: String): String {
        var data = preferences.getString(name, null)
        if (data == null) {
            data = default
        }
        return data
    }

    fun saveData(name: String, value: String) {
        val editor = preferences.edit()
        editor.putString(name, value)
        editor.apply()
    }

    /**
     * Restores the Game state from the last time it was saved.
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")

        if (restoreStateFunction != null) {
            restoreStateFunction?.invoke()
        }
    }

    private fun resetGame() {
        // TODO: Plugin a reset game function here....

        saveGameState()
        pushStateToClients()
    }

    private fun encodeState(): String? {
        if (encodeStateFunction != null) {
            return encodeStateFunction?.invoke()
        }
        return ""
    }

    fun decodeState(message: Message): Any? {
        if (decodeStateFunction != null) {
            return decodeStateFunction?.invoke(message)
        }
        return null
    }

    private var encodeStateFunction: (() -> String)? = null
    private var decodeStateFunction: ((Message) -> Any)? = null
    private var savePuzzleFunction: (() -> Unit)? = null
    private var restoreStateFunction: (() -> Unit)? = null

    fun pluginEncodeState(encodeStateFunction: () -> String) {
        Log.d(TAG, "Plugging in encode state function...")
        this.encodeStateFunction = encodeStateFunction
    }

    fun pluginDecodeState(decodeStateFunction: (message: Message) -> Any) {
        Log.d(TAG, "Plugging in encode state function...")
        this.decodeStateFunction = decodeStateFunction
    }

    fun pluginSaveState(saveStateFunction: () -> Unit) {
        Log.d(TAG, "Plugging in save puzzle function...")
        this.savePuzzleFunction = saveStateFunction
    }

    fun pluginRestoreState(restoreStateFunction: () -> Unit) {
        Log.d(TAG, "Plugging in restore puzzle function...")
        this.restoreStateFunction = restoreStateFunction
    }

    fun queueActivityMessage(message: Message, responseFunction: ((message: String) -> Unit)?) {
        val im = InboundMessage(message, InboundMessageSource.APP, responseFunction)
        inboundMessageQueue.add(im)
    }

    companion object {
        private val TAG = GameEngine::class.java.simpleName

        // For the moment, just permit the Singleton instance.
        private var singletonGameEngine: GameEngine? = null

        // FUTURE: Allocate multiple instances based on a game identifier.
        fun activate(definition: GameplayDefinition, cm: ConnectivityManager, sharedPreferences: SharedPreferences): GameEngine {
            if (singletonGameEngine == null) {
                Log.d(TAG, "Starting new GameEngine ...")
                singletonGameEngine = GameEngine(cm ,sharedPreferences, definition)
                singletonGameEngine!!.start()
            } else {
                Log.d(TAG, "Already created GameEngine.")
            }
            return singletonGameEngine!!
        }

        // TODO - move these to instance functions by passing them to the message sources as arguments.


        fun queueClientHandlerMessage(message: Message, responseFunction: (message: String) -> Unit) {
            val im = InboundMessage(message, InboundMessageSource.CLIENTHANDLER, responseFunction)
            singletonGameEngine?.inboundMessageQueue?.add(im)
        }

        fun queueClientMessage(message: Message, responseFunction: (message: String) -> Unit) {
            val im = InboundMessage(message, InboundMessageSource.CLIENT, responseFunction)
            singletonGameEngine?.inboundMessageQueue?.add(im)
        }

    }
}