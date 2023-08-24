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

    enum class InboundMessageSource {
        APP, CLIENT, CLIENTHANDLER
    }

    // TODO - replace responseQueue with responseFunction
    data class InboundMessage(
        val message: Message,
        val source: InboundMessageSource,
        val responseQueue: BlockingQueue<String>?,
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

    private var remotePlayers: MutableList<BlockingQueue<String>> = mutableListOf()  // Only used in SERVER mode.

    data class ClientRequest(val requestString: String, val responseQ: Queue<String>)

    private val allIpAddresses: MutableList<String> = mutableListOf()

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

            if (im != null) {
                if (im.source == InboundMessageSource.APP) {
                    handleActivityMessage(im)
                }
                if (im.source == InboundMessageSource.CLIENT) {
                    handleClientMessage(im)
                }
                if (im.source == InboundMessageSource.CLIENTHANDLER) {
                    handleClientHandlerMessage(im)
                }

                if (gameplayHandler != null) {
                    // TODO - testing new message decoder:
//                    val gm: Message = Message.decodeMessage(im.message)
//                    Log.d(TAG,"Testing GeneralMassage: $gm")

                    var stateChanged = false
                    // TODO Change invoke signature to Message
                    stateChanged = gameplayHandler?.invoke(im.message) == true

                    if (stateChanged) {
                        saveGameState()
                        pushStateToClients()
                    }
                }
            }

            if (loopDelayMilliseconds > 0) {
                sleep(loopDelayMilliseconds)
            }
        }
        Log.d(TAG, "The Game Server has shut down.")
    }

    private fun switchToRemoteServerMode(address: String) {
        // FIXME - doesn't handle when the remote server isn't running...

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

    private var previousStateUpdate = ""  // TODO - this should only be in GameplayDefinition.

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

    private fun handleClientHandlerMessage(im: InboundMessage) {

        var validRequest = false
        if (im.message.type == "Initialise") {
            validRequest = true
            remotePlayers.add(im.responseQueue!!)
        }

        // TODO: Normal gameplay commands here...

        if (im.message.type == "Status") {
            validRequest = true
            im.responseQueue!!.add("state,${encodeState()}")
        }
        if (im.message.type == "Shutdown" || im.message.type == "Abandoned") {
            validRequest = true
            im.responseQueue!!.add(im.message.type)
            remotePlayers.remove(im.responseQueue)
        }

        if (!validRequest) {
            Log.d(TAG, "invalid message: [${im.message}]")
        }
    }

    private fun handleClientMessage(im: InboundMessage) {
        if (im.message.type == "state") {
            val remoteState = im.message.getString("RemoteState")

            if (remoteState != null && previousStateUpdate != remoteState) {
                Log.d(TAG, "REMOTE Game Server sent state change: [$remoteState]")

                previousStateUpdate = remoteState

                val stateVars = decodeState(Message.decodeMessage(remoteState))
                // TODO:

                saveGameState()
                pushStateToClients()
            }
        }
        if (im.message.type == "Shutdown" || im.message.type == "Abandoned") {
            im.responseQueue!!.add(im.message.type)
            switchToPureLocalMode()
        }
    }

    private var stateChangeCallbacks: MutableList<(message: String) -> Unit> = mutableListOf()

    private fun handleActivityMessage(im: InboundMessage) {

        var stateChanged = false
        if (im.message.type == "RequestStateChanges") {
            Log.d(TAG, "RequestStateChanges received...")
            if (im.responseFunction != null) {
                stateChangeCallbacks.add(im.responseFunction)

                // Assume that the caller does NOT have the current state.
                im.responseFunction?.invoke("MessageType=State,${encodeState()}")
            }
        }

        // TODO - need a cancel state update requests message too...

        if (im.message.type == "Reset") {
            resetGame()
            stateChanged = true
        }
        if (im.message.type == "Status") {
            im.responseFunction?.invoke("MessageType=State,${encodeState()}")
        }

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
    }

    private fun pushStateToClients() {
        stateChangeCallbacks.forEach { callback ->
            // TODO - make this a Message
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
     *
     * TODO - return data that can be sent to the Definition...
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")

        if (restorePuzzleFunction != null) {
            Log.d(TAG, "Calling plugin ...")
            restorePuzzleFunction?.invoke()
        }
    }

    private fun resetGame() {
        // TODO:

        saveGameState()
        pushStateToClients()
    }

    private fun encodeState(): String? {
        if (encodeStateFunction != null) {
            Log.d(TAG, "Using NEW plugin for state...")
            return encodeStateFunction?.invoke()
        }
        return ""
    }

    fun decodeState(message: Message): GameplayDefinition.StateVariables? {
        if (decodeStateFunction != null) {
            return decodeStateFunction?.invoke(message)
        }
        return null
    }

    private var gameplayHandler: ((m: Message) -> Boolean)? = null
    private var encodeStateFunction: (() -> String)? = null
    private var decodeStateFunction: ((Message) -> GameplayDefinition.StateVariables)? = null
    private var savePuzzleFunction: (() -> Unit)? = null
    private var restorePuzzleFunction: (() -> Unit)? = null

    fun pluginGameplay(gameplayHandler: (m: Message) -> Boolean) {
        Log.d(TAG, "Plugging in gameplay handler...")
        this.gameplayHandler = gameplayHandler
    }

    fun pluginEncodeState(encodeStateFunction: () -> String) {
        Log.d(TAG, "Plugging in encode state function...")
        this.encodeStateFunction = encodeStateFunction
    }

    fun pluginDecodeState(decodeStateFunction: (message: Message) -> GameplayDefinition.StateVariables) {
        Log.d(TAG, "Plugging in encode state function...")
        this.decodeStateFunction = decodeStateFunction
    }

    fun pluginSaveState(savePuzzleFunction: () -> Unit) {
        Log.d(TAG, "Plugging in save puzzle function...")
        this.savePuzzleFunction = savePuzzleFunction
    }

    fun pluginRestoreState(restorePuzzleFunction: () -> Unit) {
        Log.d(TAG, "Plugging in restore puzzle function...")
        this.restorePuzzleFunction = restorePuzzleFunction
    }

    companion object {
        private val TAG = GameEngine::class.java.simpleName

        private var singletonGameEngine: GameEngine? = null

        fun activate(cm: ConnectivityManager, sharedPreferences: SharedPreferences, definition: GameplayDefinition) {
            if (singletonGameEngine == null) {
                Log.d(TAG, "Starting new GameEngine ...")
                singletonGameEngine = GameEngine(cm ,sharedPreferences, definition)
                singletonGameEngine!!.start()
            } else {
                Log.d(TAG, "Already created GameEngine.")
            }
        }

        // TODO - use Message class instead of String.
        fun queueActivityMessage(message: Message, responseFunction: ((message: String) -> Unit)?) {
            val im = InboundMessage(message, InboundMessageSource.APP, null, responseFunction)
            singletonGameEngine?.inboundMessageQueue?.add(im)
        }

        fun queueClientHandlerMessage(message: Message, responseQ: BlockingQueue<String>) {
            val im = InboundMessage(message, InboundMessageSource.CLIENTHANDLER, responseQ, null)
            singletonGameEngine?.inboundMessageQueue?.add(im)
        }

        fun queueClientMessage(message: Message, responseQ: BlockingQueue<String>) {
            val im = InboundMessage(message, InboundMessageSource.CLIENT, responseQ, null)
            singletonGameEngine?.inboundMessageQueue?.add(im)
        }

        fun decodeState(message: Message): GameplayDefinition.StateVariables? {
            return singletonGameEngine?.decodeState(message)
        }
    }
}