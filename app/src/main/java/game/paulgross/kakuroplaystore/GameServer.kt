package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
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

class GameServer(private val context: Context, private val preferences: SharedPreferences): Thread() {
    private var socketServer: SocketServer? = null
    private var socketClient: SocketClient? = null

    private val gameIsRunning = AtomicBoolean(true)  // TODO - this might not be needed.

    // We use a BlockingQueue here to block thread progress if needed.
    // https://developer.android.com/reference/java/util/concurrent/BlockingQueue
    private val fromClientHandlerToGameServerQ: BlockingQueue<ClientRequest> = LinkedBlockingQueue()
    private val fromClientToGameServerQ: BlockingQueue<ClientRequest> = LinkedBlockingQueue()
    private val fromActivitiesToGameSeverQ: BlockingQueue<String> = LinkedBlockingQueue()

    enum class GameMode {
        /** Game only responds to local Activity requests. */
        LOCAL,

        /** Allow remote users to play by joining this GameServer over the network. */
        SERVER,

        /** Joined a network GameServer. */
        CLIENT
    }
    private var gameMode: GameMode = GameMode.LOCAL

    /**
     * The playing grid.
     */
    // TODO:

    private var remotePlayers: MutableList<Queue<String>> = mutableListOf()  // Only used in SERVER mode.

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

    private val messageAvailable: BlockingQueue<Boolean> = LinkedBlockingQueue()

    override fun run() {

        restoreGameState()

        while (gameIsRunning.get()) {
            messageAvailable.take() //  We don't need the message contents, since this is just an activation flag.
            // TODO - use a "false" message to routinely trigger the queue (once per second?)
            //  to hopefully recover if this trigger system fails.
            messageAvailable.clear() // Don't ever allow the queue to build up.
            Log.d(TAG, "A message is available.")

            // TODO - clear at least a few client requests in a single loop in case there are many queued up.
            // TODO - monitor queue length for accidental build-up.

            val activityRequest = fromActivitiesToGameSeverQ.poll()  // Non-blocking read.
            if (activityRequest != null) {
                handleActivityMessage(activityRequest)
            }

            val clientHandlerMessage = fromClientHandlerToGameServerQ.poll()  // Non-blocking read.
            if (clientHandlerMessage != null) {
                handleClientHandlerMessage(clientHandlerMessage.requestString, clientHandlerMessage.responseQ)
            }

            val clientMessage = fromClientToGameServerQ.poll()  // Non-blocking read.
            if (clientMessage != null) {
                handleClientMessage(clientMessage.requestString, clientMessage.responseQ)
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
        socketServer = SocketServer(fromClientHandlerToGameServerQ)
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

    private fun handleClientHandlerMessage(message: String, responseQ: Queue<String>) {

        var validRequest = false
        if (message == "Initialise") {
            validRequest = true
            remotePlayers.add(responseQ)

            messageGameplayDisplayState()
        }

        // TODO: Normal gameplay commands here...

        if (message == "status") {
            validRequest = true
            responseQ.add("state,${encodeState()}")
        }
        if (message == "shutdown" || message == "abandoned") {
            validRequest = true
            responseQ.add(message)
            remotePlayers.remove(responseQ)
            messageGameplayDisplayState()
        }

        if (!validRequest) {
            Log.d(TAG, "invalid request: [$message]")
        }
    }

    private fun handleClientMessage(message: String, responseQ: Queue<String>) {
        if (message.startsWith("state,", true)) {
            val remoteState = message.substringAfter("state,")

            if (previousStateUpdate != remoteState) {
                Log.d(TAG, "REMOTE Game Server sent state change: [$remoteState]")

                previousStateUpdate = remoteState

                val stateVars = decodeState(remoteState)
                // TODO:

                saveGameState()
                messageGameplayDisplayState()
            }
        }
        if (message == "shutdown" || message == "abandoned") {
            responseQ.add(message)
            switchToPureLocalMode()
        }
    }

    private fun handleActivityMessage(message: String) {
        if (message == "Reset") {
            resetGame()
            messageGameplayDisplayState()
        }
        if (message == "Status") {
            messageGameplayDisplayState()
        }
        if (message.startsWith("Guess=")) {
            Log.d(TAG, "The user sent a guess: $message")
            val split = message.split("=")
            val guess = split[1].split(",")

            val index = guess[0].toInt()
            var value = guess[1].toInt()
            if (value == 0) { value = -1 }
            playerGrid[index] = value
            saveGameState()

            messageGameplayDisplayState()
        }

        // TODO - handle the normal gameplay commands

        if (message == "StartServer") {
            if (gameMode != GameMode.SERVER) {
                switchToLocalServerMode()
            }
        }
        if (message == "StartLocal") {
            if (gameMode != GameMode.LOCAL) {
                switchToPureLocalMode()
            }
        }
        if (message.startsWith("RemoteServer")) {
            if (gameMode != GameMode.CLIENT) {
                val ip = message.substringAfter(":", "")
                if (ip != "") {
                    switchToRemoteServerMode(ip)
                }
            }
        }
        if (message == "StopGame") {
            stopGame()
        }
    }

    private fun pushStateToClients() {
        socketServer?.pushMessageToClients("state,${encodeState()}")
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
        Log.d(TAG, "Saving game state ...")

        val editor = preferences.edit()

        editor.putString("CurrGame", currGame)

        // Save the player's guesses
        val guessesToSave = encodeGuesses()
        Log.d(TAG, "Saving guesses: [$guessesToSave]")
        editor.putString("Guesses", guessesToSave)

        // TODO - store possibles.

        editor.apply()
        Log.d(TAG, "Saved game state.")
    }

    /**
     * Restores the Game state from the last time it was saved.
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")

        var restoredGame = preferences.getString("CurrGame", null)
        if (restoredGame == null) {
            currGame = "043100820006980071"
        } else {
            currGame = restoredGame
        }
        convertGameString(currGame!!)

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

        generateHints()

        // TODO: The player's own "possibles" list:
        // 0=3&1, 0=3/1, ... etc
    }

    private fun convertGameString(gameString: String) {
        puzzleWidth = gameString.substring(0, 2).toInt()

        puzzleSolution.clear()
        for (char in gameString.substring(2)) {
            if (char == '0') {
                puzzleSolution.add(-1)
            } else {
                puzzleSolution.add(char.digitToInt())
            }
        }
    }

    private fun messageGameplayDisplayState() {
        val intent = Intent()
        intent.action = context.packageName + GameplayActivity.MESSAGE_SUFFIX
        intent.putExtra("State", encodeState())

        context.sendBroadcast(intent)
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

    private var currGame = ""
    private var puzzleWidth = 5
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var playerGrid: MutableList<Int> = mutableListOf()
    private var playerHints: MutableList<Hint> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Longs.
    var playerPossibles: Array<Long> = Array(10) {0} // Same size Array as the solution

    data class StateVariables(var playerGrid: MutableList<Int>, var puzzleWidth:Int, var playerHints:MutableList<Hint>)

    private fun encodeState(): String {
        var state = ""

        state += "w=$puzzleWidth,"
        state += "g=" + encodeGuesses()

        // TODO - encode hints? Only need to send this once...
        // h=2ACROSS13:2DOWN23 ... etc
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

    fun decodeState(stateString: String): StateVariables {
        Log.d(TAG, "decodeState() for [$stateString]")

        var width = 0
        var grid:MutableList<Int> = mutableListOf()
        var hints: MutableList<Hint> = mutableListOf()

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
            if (key == "g") {
                val ints = value.split(":")
                ints.forEach {theIntString ->
                    grid.add(theIntString.toInt())
                }
            }
            if (key == "h") {
                Log.d(TAG, "TODO: Decode the hints...")
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
        }

        return StateVariables(grid, width, hints)
    }

    companion object {
        private val TAG = GameServer::class.java.simpleName

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

        fun queueActivityMessage(message: String) {
            if (singletonGameServer?.gameIsRunning!!.get()) {
                singletonGameServer?.fromActivitiesToGameSeverQ?.add(message)
            }
            singletonGameServer?.messageAvailable?.put(true)
        }

        fun queueClientHandlerMessage(message: String, responseQ: Queue<String>) {
            if (singletonGameServer?.gameIsRunning!!.get()) {
                singletonGameServer?.fromClientHandlerToGameServerQ?.add(ClientRequest(message, responseQ))
            }
            singletonGameServer?.messageAvailable?.put(true)
        }

        fun queueClientMessage(message: String, responseQ: Queue<String>) {
            if (singletonGameServer?.gameIsRunning!!.get()) {
                singletonGameServer?.fromClientToGameServerQ?.add(ClientRequest(message, responseQ))
            }
            singletonGameServer?.messageAvailable?.put(true)
        }

        fun decodeState(stateString: String): StateVariables? {
            return singletonGameServer?.decodeState(stateString)
        }
    }
}