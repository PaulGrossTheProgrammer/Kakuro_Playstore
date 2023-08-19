package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.util.Log
import java.lang.Exception
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class GameServer(private val context: Context, private val preferences: SharedPreferences): Thread() {
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
        val cm: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val addrs = lp?.linkAddresses
        addrs?.forEach { addr ->
            allIpAddresses.add(addr.address.hostAddress)
        }
    }

    private var loopDelayMilliseconds = -1L  // -1 means disable looping,

    override fun run() {
        restoreGameState()
        // TODO - get plugin here
        GameplayDefinition  // Hopefully this plugs-in the gameplay ...
        // TODO - set the initial state...

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
                    gameplayHandler?.invoke(im)
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

    private var previousStateUpdate = ""

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

        // Normal gameplay commands

        if (im.message.startsWith("Guess=")) {
            val changed = submitGuess(im.message)
            if (changed) {
                stateChanged = true
            }
        }

        if (im.message.startsWith("Possible=")) {
            val changed = markUnMarkPossible(im.message)
            if (changed) {
                stateChanged = true
            }
        }

        if (im.message == "RestartPuzzle") {
            val changed = restartPuzzle()
            if (changed) {
                stateChanged = true
            }
        }

        if (stateChanged) {
            saveGameState()
            pushStateToClients()
        }
    }

    private fun submitGuess(message: String): Boolean {
        Log.d(TAG, "The user sent a guess: $message")
        val split = message.split("=")
        val guess = split[1].split(",")

        // TODO - handle invalid Ints...
        val index = guess[0].toInt()
        val value = guess[1].toInt()
        playerGrid[index] = value
        playerPossibles.remove(index)

        return true
    }

    private fun restartPuzzle(): Boolean {
        for (i in 0 until playerGrid.size) {
            if (playerGrid[i] != -1) {
                playerGrid[i] = 0
            }
        }
        playerPossibles.clear()
        return true
    }

    private fun markUnMarkPossible(message: String): Boolean {
        Log.d(TAG, "The user sent a possible: $message")
        val split = message.split("=")
        val guess = split[1].split(",")

        // TODO - handle invalid Ints...
        val index = guess[0].toInt()
        val value = guess[1].toInt()

        // Don't allow possibles if there is currently a guess
        if (playerGrid[index] > 0) {
            Log.d(TAG, "Can't set possibles where there is a guess")
            return false
        }

        // determine the current possibles for the index
        var possible = playerPossibles[index]
        if (possible == null) {
            possible = "000000000"
        }

        // Get the position from the value
        val digit = possible[value - 1]
        var replacement = "0"
        if (digit == '0') {
            replacement = value.toString()
        }

        possible = possible.substring(0, value - 1) + replacement + possible.substring(value)
        if (possible == "000000000") {
            Log.d(TAG, "Removing index ...")
            playerPossibles.remove(index)
        } else {
            playerPossibles[index] = possible
        }

        return true
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

    /**
     * Saves the current Game state.
     */
    private fun saveGameState() {
        val editor = preferences.edit()

        editor.putString("CurrPuzzle", currPuzzle)

        val guessesToSave = encodeGuesses()
        editor.putString("Guesses", guessesToSave)

        // TODO - store possibles.
        val possiblesToSave = encodePossibles()
        editor.putString("Possibles", possiblesToSave)

        editor.apply()
        Log.d(TAG, "Saved game state.")
    }

    // TODO -- call this form the definition to load the last save.
    fun restoreData(name: String, default: String): String {
        var data = preferences.getString("name", null)
        if (data == null) {
            data = default
        }
        return data
    }

    /**
     * Restores the Game state from the last time it was saved.
     *
     * TODO - return data that can be sent to the Definition...
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")

        restorePuzzleFunction?.invoke()

        // TODO - delete below after GameplayDefinition works properly...
            var restoredGame = preferences.getString("CurrPuzzle", null)
        currPuzzle = if (restoredGame == null) {
            DEFAULTPUZZLE
        } else {
            restoredGame
        }
        startPuzzleFromString(currPuzzle!!)

        playerGrid.clear()
        val guessesString = preferences.getString("Guesses", "")
        if (guessesString == "") {
            puzzleSolution.forEach { square ->
                if (square == -1) {
                    playerGrid.add(-1)
                } else {
                    playerGrid.add(0)
                }
            }
        } else {
            val guessList = guessesString?.split(":")
            guessList?.forEach {guessString ->
                playerGrid.add(guessString.toInt())
            }
        }

        playerHints.clear()
        generateHints()

        // Restore the player's own "possibles" list.
        val possiblesString = preferences.getString("Possibles", "")
        playerPossibles = decodePossibles(possiblesString!!)
        // 0=103000000, 0=103000000, ... etc

    }

    private fun startPuzzleFromString(puzzleString: String) {
        puzzleWidth = puzzleString.substring(0, 2).toInt()

        puzzleSolution.clear()
        for (char in puzzleString.substring(2)) {
            if (char == '0') {
                puzzleSolution.add(-1)
            } else {
                puzzleSolution.add(char.digitToInt())
            }
        }
    }

    private fun messageGameplayDisplayState(im: InboundMessage) {
        im.responseFunction?.invoke("MessageType=State,${encodeState()}")

    }

    private fun resetGame() {
        // TODO:

        saveGameState()
        pushStateToClients()
    }

    // -- Game here

    enum class Direction {ACROSS, DOWN}
    data class Hint(val index: Int, val direction: Direction, var total: Int)

    /**
     * Create the initial grid with no guesses .
     * Create all the ACROSS and DOWN hints based on the structure and solution.
     */
    private fun generateHints() {

        // Traverse the solution grid and create hints for any number squares with empty squares to the left and/or above.
        puzzleSolution.forEachIndexed { index, value ->
            if (value  != -1) {
                // Check for ACROSS hints.
                // First column numbers always need a hint.
                val isFirstColumn = (index.mod(puzzleWidth) == 0)
                if (isFirstColumn || puzzleSolution[index - 1] == -1) {
                    val sum = sumOfSquares(puzzleSolution, puzzleWidth, index, Direction.ACROSS)
                    playerHints.add(Hint(index, Direction.ACROSS, sum))
                }

                // Check for DOWN hints (don't check last row)
                // First colum row always need a hint.
                val isFirstRow = (index < puzzleWidth)
                if (isFirstRow || puzzleSolution[index - puzzleWidth] == -1) {
                    val sum = sumOfSquares(puzzleSolution, puzzleWidth, index, Direction.DOWN)
                    playerHints.add(Hint(index, Direction.DOWN, sum))
                }
            }
        }
    }

    private fun sumOfSquares(grid: MutableList<Int>, width: Int, startIndex: Int, direction: Direction): Int {
        var sum = 0

        var stepSize = 1
        if (direction == Direction.DOWN) {
            stepSize = width
        }

        var index = startIndex
        while (index < grid.size && grid[index] != -1) {
            sum += grid[index]
            index += stepSize
        }

        return sum
    }

    private var currPuzzle = ""
    private var puzzleWidth = 5
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var playerGrid: MutableList<Int> = mutableListOf()
    private var playerHints: MutableList<Hint> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Strings.
    private var playerPossibles: MutableMap<Int, String> = mutableMapOf()

    data class StateVariables(var playerGrid: MutableList<Int>, var puzzleWidth:Int,
                              var playerHints:MutableList<Hint>, var possibles: MutableMap<Int, String>)

    private fun encodeState(): String {
        var state = ""

        state += "w=$puzzleWidth,"
        state += "g=" + encodeGuesses()

        // h=2ACROSS13:2DOWN23 ... etc
        // TODO - call a method to encode
        state += ",h="
        playerHints.forEachIndexed {index, hint ->
            hint.index
            hint.direction
            hint.total
            state += "${hint.index}${hint.direction}${hint.total}"
            if (index < playerHints.size - 1) {
                state += ":"
            }
        }

        // p=2:0123000000,8:0000006780
        if (playerPossibles.isNotEmpty()) {
            state += ",p="
            state += encodePossibles()
        }

        return state
    }

    private fun encodeGuesses(): String {
        var guessString = ""
        playerGrid.forEachIndexed {index, squareValue ->
            guessString += squareValue.toString()
            if (index < playerGrid.size - 1) {
                guessString += ":"
            }
        }
        return guessString
    }

    private fun encodePossibles(): String {
        var possiblesString = ""
        // ampersand separated entries, colon separated index and possibles.
        // 0:1234567&5:100450000
        // TODO
        var firstEntry = true
        playerPossibles.forEach { index, possibles ->
            if (firstEntry) { firstEntry = false } else { possiblesString += "&" }
            possiblesString += "$index:$possibles"
        }
        return possiblesString
    }

    // TODO - create methods for decode guesses, hints and possibles.

    private fun decodePossibles(possiblesString: String): MutableMap<Int, String> {
        val newPossibles: MutableMap<Int, String> = mutableMapOf()
        if (possiblesString == "") {
            return newPossibles
        }
        val possiblesList = possiblesString.split("&")
        possiblesList.forEach { currPossible ->
            val keyValue = currPossible.split(":")
            val indexInt = keyValue[0].toInt()
            val valueString = keyValue[1]

            newPossibles[indexInt] = valueString
        }

        return newPossibles
    }

    fun decodeState(stateString: String): StateVariables {
        Log.d(TAG, "decodeState() for [$stateString]")

        var width = 0
        val grid: MutableList<Int> = mutableListOf()
        val hints: MutableList<Hint> = mutableListOf()
        var possibles: MutableMap<Int, String> = mutableMapOf()

        // Example
        //w=4,g=-1:-1:0:0:-1:-1:0:0:0:-1:-1:-1:0:0:-1:-1
        // split on commas into key-value pairs
        var map: MutableMap<String, String> = mutableMapOf()
        val parts = stateString.split(",")
        for (part in parts) {
            if (part.contains("=")) {
                val keyValue = part.split("=")
                map.put(keyValue[0], keyValue[1])
            }
        }

        map.forEach { key, value ->
            if (key == "w") {
                width = value.toInt()
            }
            if (key == "g") {  // User's guesses
                // TODO - call the decodeGuesses() method...
                val ints = value.split(":")
                ints.forEach {theIntString ->
                    grid.add(theIntString.toInt())
                }
            }
            if (key == "h") {  // Hints
                // TODO - call a method to decode
                val hintList = value.split(":")
                hintList.forEach {theHintString ->
                    val downString = Direction.DOWN.toString()
                    val acrossString = Direction.ACROSS.toString()
                    var dir = Direction.DOWN
                    var index = -1
                    var total = 0
                    if (theHintString.contains(downString)) {
                        index = theHintString.substringBefore(downString, "-1").toInt()
                        total = theHintString.substringAfter(downString).toInt()
                    } else if (theHintString.contains(acrossString)) {
                        dir = Direction.ACROSS
                        index = theHintString.substringBefore(acrossString, "-1").toInt()
                        total = theHintString.substringAfter(acrossString).toInt()
                    }
                    hints.add(Hint(index, dir, total))
                }
            }
            if (key == "p") {  // User's possibles
                possibles = decodePossibles(value)
            }
        }

        return StateVariables(grid, width, hints, possibles)
    }

    private var gameplayHandler: ((im: InboundMessage) -> Unit)? = null

    private var restorePuzzleFunction: (() -> Unit)? = null

    companion object {
        private val TAG = GameServer::class.java.simpleName

        private val DEFAULTPUZZLE = "043100820006980071"

        // The GameServer always runs in it's own thread,
        // and stopGame() must be called as the App closes to avoid a memory leak.
        @SuppressLint("StaticFieldLeak")
        private var singletonGameServer: GameServer? = null

        fun activate(applicationContext: Context, sharedPreferences: SharedPreferences) {
            if (singletonGameServer == null) {
                Log.d(TAG, "Starting new GameServer ...")
                singletonGameServer = GameServer(applicationContext, sharedPreferences)
                singletonGameServer!!.start()
            } else {
                Log.d(TAG, "Already created GameServer.")
            }
        }

        fun getGameMode(): GameMode? {
            return singletonGameServer?.getGameMode()
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

        fun decodeState(stateString: String): StateVariables? {
            return singletonGameServer?.decodeState(stateString)
        }

        fun pluginGameplay(gameplayHandler: (im: InboundMessage) -> Unit) {
            Log.d(TAG, "Plugging in gameplay handler...")
            singletonGameServer?.gameplayHandler = gameplayHandler
        }

        fun pluginRestorePuzzle(restorePuzzleFunction: () -> Unit) {
            Log.d(TAG, "Plugging in gameplay handler...")
            singletonGameServer?.restorePuzzleFunction = restorePuzzleFunction
        }
    }
}