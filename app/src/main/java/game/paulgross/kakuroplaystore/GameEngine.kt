package game.paulgross.kakuroplaystore

import android.content.Context
import android.content.Intent
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

    private var encodeStateFunction: (() -> Message)? = null
    private var decodeStateFunction: ((Message) -> Any)? = null
    private var saveStateFunction: (() -> Unit)? = null
    private var restoreStateFunction: (() -> Unit)? = null

    private val gameIsRunning = AtomicBoolean(true)  // TODO - this might not need to be Atomic.

    // We use a BlockingQueue here to block thread progress if needed.
    // https://developer.android.com/reference/java/util/concurrent/BlockingQueue
    private val inboundMessageQueue: BlockingQueue<InboundMessage> = LinkedBlockingQueue()

    //  TODO: Remove static use of this class by passing a function to each source
    //   so that the function automatically identifies the source.
    enum class InboundMessageSource {
        APP, CLIENT, CLIENTHANDLER
    }

    data class Changes(val system: Boolean, val game: Boolean)

    data class InboundMessage(
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

    // TDSO merge this with the state change callbacks???
    private var remotePlayers: MutableList<(message: Message) -> Unit> = mutableListOf()  // Only used in SERVER mode.
    private var localPlayer: MutableList<(message: Message) -> Unit> = mutableListOf()  // Only used in SERVER mode.

    // TODO - combine this with the remote and local players lists...
    private var stateChangeCallbacks: MutableList<(message: Message) -> Unit> = mutableListOf()

    data class ClientRequest(val requestString: String, val responseQ: Queue<String>)

    private val allIpAddresses: MutableList<String> = mutableListOf()

    private data class MessageHandler(val type: String, val handlerFunction: (message: Message) -> Boolean)
    private data class SystemMessageHandler(val type: String,
                                            val handlerFunction: (message: Message, source: InboundMessageSource, ((message: Message) -> Unit)?) -> Changes)

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
        // TODO - register all the reserved system messages
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
                        // TODO - system handlers need to pass back 2 flags: System and Game state changed.
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
                        val changed = handler.handlerFunction.invoke(im.message)
                        if (changed) {
                            gameStateChanged = true
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

/*    private data class MessageTypeSpec(val messageType: String, val partName: String)
    private data class MessageCodec(
                       val encoderFunc: (Any) -> String,
                       val decoderFunc: (String) -> Any) {}

    private val messageCodecs: MutableMap<MessageTypeSpec, MessageCodec> = mutableMapOf()

    fun pluginMessageCodec(messageType: String, partName: String,
                             encoderFunc: (Any) -> String, decoderFunc: (String) -> Any) {
        messageCodecs[MessageTypeSpec(messageType, partName)] = MessageCodec(encoderFunc, decoderFunc)
    }*/

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
            // TODO - make this a Message for the local client?
            val newMessage = Message("State")


//            callback("MessageType=State,${encodeState()}")
            encodeState()?.let { callback(it) }
        }
    }

    fun gotoSettingsActivity(context: Context) {
        val intent: Intent = Intent(context, GameEngineSettingsActivity::class.java)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        intent.putExtra("SelectedSetting", "")
        context.startActivity(intent)
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
        if (saveStateFunction != null) {
            saveStateFunction?.invoke()
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

    fun queueActivityMessage(message: Message, responseFunction: ((message: Message) -> Unit)?) {
        val im = InboundMessage(message, InboundMessageSource.APP, responseFunction)
        inboundMessageQueue.add(im)
    }

    fun queueClientMessage(message: Message, responseFunction: (message: Message) -> Unit) {
        val im = InboundMessage(message, InboundMessageSource.CLIENT, responseFunction)
        singletonGameEngine?.inboundMessageQueue?.add(im)
    }

    fun queueClientHandlerMessage(message: Message, responseFunction: (message: Message) -> Unit) {
        val im = InboundMessage(message, InboundMessageSource.CLIENTHANDLER, responseFunction)
        singletonGameEngine?.inboundMessageQueue?.add(im)
    }

    companion object {
        private val TAG = GameEngine::class.java.simpleName

        // For the moment, just permit the Singleton instance.
        private var singletonGameEngine: GameEngine? = null

        // FUTURE: Allocate multiple instances based on a game identifier and definition.
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

        fun get(): GameEngine? {
            if (singletonGameEngine == null) {
                Log.d(TAG, "GameEngine has not yet been activated.")
                return null
            }
            return singletonGameEngine
        }
    }
}