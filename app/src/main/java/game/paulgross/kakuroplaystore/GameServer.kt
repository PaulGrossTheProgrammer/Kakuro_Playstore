package game.paulgross.kakuroplaystore

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.util.Log
import java.lang.Exception
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class GameServer(private val cm: ConnectivityManager, private val preferences: SharedPreferences, private val definition: GameplayDefinition): Thread() {
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
        val message: String, val source: InboundMessageSource,
        val responseQueue: BlockingQueue<String>?,
        val responseFunction: ((message: String) -> Unit)?
    )

    enum class GameMode {
        /** Game only responds to messages within the App. */
        LOCAL,

        /** Allow remote users to play by joining this GameServer over the network. */
        SERVER,

        /** Joined a network GameServer. */
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
                    val gm: GeneralMessage = decodeMessage(im.message)
                    Log.d(TAG,"Testing GeneralMassage: $gm")

                    var stateChanged = false
                    stateChanged = gameplayHandler?.invoke(im) == true

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

    fun getGameMode(): GameMode {
        return gameMode
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

    data class GeneralMessage(val type: String, val body: Map<String, String>?)

    fun decodeMessage(message: String): GeneralMessage {
        Log.d(TAG, "decodeMessage: $message")

        var type = ""
        val messageBody = mutableMapOf<String, String>()

        if (message.indexOf("=") == -1) {
            messageBody["ErrorMessage"] = "Expected 'MessageType'"
            messageBody["SentMessage"] = message
            return GeneralMessage("FormatError", messageBody)
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
            messageBody.clear()
            messageBody["ErrorMessage"] = "Expected 'MessageType'"
            messageBody["SentMessage"] = message
            return GeneralMessage("FormatError", messageBody)
        }

        return GeneralMessage(type, messageBody)
    }


    private fun handleClientHandlerMessage(im: InboundMessage) {

        var validRequest = false
        if (im.message == "Initialise") {
            validRequest = true
            remotePlayers.add(im.responseQueue!!)
        }

        // TODO: Normal gameplay commands here...

        if (im.message == "status") {
            validRequest = true
            im.responseQueue!!.add("state,${encodeState()}")
        }
        if (im.message == "shutdown" || im.message == "abandoned") {
            validRequest = true
            im.responseQueue!!.add(im.message)
            remotePlayers.remove(im.responseQueue)
        }

        if (!validRequest) {
            Log.d(TAG, "invalid message: [${im.message}]")
        }
    }

    private fun handleClientMessage(im: InboundMessage) {
        if (im.message.startsWith("state,", true)) {
            val remoteState = im.message.substringAfter("MessageType=State,")

            if (previousStateUpdate != remoteState) {
                Log.d(TAG, "REMOTE Game Server sent state change: [$remoteState]")

                previousStateUpdate = remoteState

                val stateVars = decodeState(remoteState)
                // TODO:

                saveGameState()
                pushStateToClients()
            }
        }
        if (im.message == "shutdown" || im.message == "abandoned") {
            im.responseQueue!!.add(im.message)
            switchToPureLocalMode()
        }
    }

    private var stateChangeCallbacks: MutableList<(message: String) -> Unit> = mutableListOf()

    private fun handleActivityMessage(im: InboundMessage) {

        var stateChanged = false
        if (im.message == "RequestStateChanges") {
            Log.d(TAG, "RequestStateChanges received...")
            if (im.responseFunction != null) {
                stateChangeCallbacks.add(im.responseFunction)

                // Assume that the caller does NOT have the current state.
                im.responseFunction?.invoke("MessageType=State,${encodeState()}")
            }
        }

        // TODO - need a cancel state update requests message too...

        if (im.message == "Reset") {
            resetGame()
            stateChanged = true
        }
        if (im.message == "Status") {
            im.responseFunction?.invoke("MessageType=State,${encodeState()}")
        }

        if (im.message == "StartServer") {
            if (gameMode != GameMode.SERVER) {
                switchToLocalServerMode()
            }
        }
        if (im.message == "StartLocal") {
            if (gameMode != GameMode.LOCAL) {
                switchToPureLocalMode()
            }
        }
        if (im.message.startsWith("RemoteServer")) {
            if (gameMode != GameMode.CLIENT) {
                val ip = im.message.substringAfter(":", "")
                if (ip != "") {
                    switchToRemoteServerMode(ip)
                }
            }
        }
        if (im.message == "StopGame") {
            stopGame()
        }
    }

    private fun pushStateToClients() {
        stateChangeCallbacks.forEach { callback ->
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

        singletonGameServer = null
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

    fun decodeState(stateString: String): GameplayDefinition.StateVariables? {
        Log.d(TAG, "decodeState() for [$stateString]")
        if (decodeStateFunction != null) {
            return decodeStateFunction?.invoke(stateString)
        }
        return null
    }

    private var gameplayHandler: ((im: InboundMessage) -> Boolean)? = null
    private var encodeStateFunction: (() -> String)? = null
    private var decodeStateFunction: ((String) -> GameplayDefinition.StateVariables)? = null
    private var savePuzzleFunction: (() -> Unit)? = null
    private var restorePuzzleFunction: (() -> Unit)? = null

    fun pluginGameplay(gameplayHandler: (im: InboundMessage) -> Boolean) {
        Log.d(TAG, "Plugging in gameplay handler...")
        this.gameplayHandler = gameplayHandler
    }

    fun pluginEncodeState(encodeStateFunction: () -> String) {
        Log.d(TAG, "Plugging in encode state function...")
        this.encodeStateFunction = encodeStateFunction
    }

    fun pluginDecodeState(decodeStateFunction: (stateString: String) -> GameplayDefinition.StateVariables) {
        Log.d(TAG, "Plugging in encode state function...")
        this.decodeStateFunction = decodeStateFunction
    }

    fun pluginSavePuzzle(savePuzzleFunction: () -> Unit) {
        Log.d(TAG, "Plugging in save puzzle function...")
        this.savePuzzleFunction = savePuzzleFunction
    }

    fun pluginRestorePuzzle(restorePuzzleFunction: () -> Unit) {
        Log.d(TAG, "Plugging in restore puzzle function...")
        this.restorePuzzleFunction = restorePuzzleFunction
    }

    companion object {
        private val TAG = GameServer::class.java.simpleName

        private var singletonGameServer: GameServer? = null

        fun activate(cm: ConnectivityManager, sharedPreferences: SharedPreferences, definition: GameplayDefinition) {
            if (singletonGameServer == null) {
                Log.d(TAG, "Starting new GameServer ...")
                singletonGameServer = GameServer(cm ,sharedPreferences, definition)
                singletonGameServer!!.start()
            } else {
                Log.d(TAG, "Already created GameServer.")
            }
        }

        fun queueActivityMessage(message: String, responseFunction: ((message: String) -> Unit)?) {
            val im = InboundMessage(message, InboundMessageSource.APP, null, responseFunction)
            singletonGameServer?.inboundMessageQueue?.add(im)
        }

        fun queueClientHandlerMessage(message: String, responseQ: BlockingQueue<String>) {
            val im = InboundMessage(message, InboundMessageSource.CLIENTHANDLER, responseQ, null)
            singletonGameServer?.inboundMessageQueue?.add(im)
        }

        fun queueClientMessage(message: String, responseQ: BlockingQueue<String>) {
            val im = InboundMessage(message, InboundMessageSource.CLIENT, responseQ, null)
            singletonGameServer?.inboundMessageQueue?.add(im)
        }

        fun decodeState(stateString: String): GameplayDefinition.StateVariables? {
            return singletonGameServer?.decodeState(stateString)
        }
    }
}