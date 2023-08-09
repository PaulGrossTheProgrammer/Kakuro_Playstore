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

    private val gameIsRunning = AtomicBoolean(true)  // TODO - this might not need to be Atomic.

    // We use a BlockingQueue here to block thread progress if needed.
    // https://developer.android.com/reference/java/util/concurrent/BlockingQueue
    private val fromClientHandlerToGameServerQ: BlockingQueue<ClientRequest> = LinkedBlockingQueue()
    private val fromClientToGameServerQ: BlockingQueue<ClientRequest> = LinkedBlockingQueue()
    private val fromActivitiesToGameSeverQ: BlockingQueue<String> = LinkedBlockingQueue()

    enum class GameMode {
        /** Game only responds to messages within the App. */
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
            messageAvailable.take() //  Blocking read. We don't need the message contents, since this is just an activation flag.
            messageAvailable.clear() // Don't ever allow the queue to build up.

            // TODO - clear at least a few messages in a single loop in case there are many queued up.
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

        // TODO - handle the normal gameplay commands

        if (message.startsWith("Guess=")) {
            Log.d(TAG, "The user sent a guess: $message")
            val split = message.split("=")
            val guess = split[1].split(",")

            val index = guess[0].toInt()
            val value = guess[1].toInt()
            playerGrid[index] = value
            saveGameState()
            messageGameplayDisplayState()
        }
        if (message.startsWith("Possible=")) {
            Log.d(TAG, "The user sent a possible: $message")
            val split = message.split("=")
            val guess = split[1].split(",")

            val index = guess[0].toInt()
            val value = guess[1].toInt()
            markUnMarkPossible(index, value)
            saveGameState()
            messageGameplayDisplayState()
        }
        if (message == "Reset") {
            resetPuzzle()
            messageGameplayDisplayState()
        }
    }

    private fun resetPuzzle() {
        for (i in 0 until playerGrid.size) {
            if (playerGrid[i] != -1) {
                playerGrid[i] = 0
            }
        }
        saveGameState()
    }

    private fun markUnMarkPossible(index: Int, value: Int) {
        // determine the current possibles for the index
        var possible = playerPossibles[index]
        if (possible == null) {
            possible = "000000000"
        }
        Log.d(TAG, "Original possible: $possible")

        // Get the position from the value
        val digit = possible[value - 1]
        var replacement = "0"
        if (digit == '0') {
            replacement = value.toString()
        }

        possible = possible.substring(0, value - 1) + replacement + possible.substring(value)
        Log.d(TAG, "Final possible   : $possible")
        if (possible == "000000000") {
            Log.d(TAG, "Removing index ...")
            playerPossibles.remove(index)
        } else {
            playerPossibles[index] = possible
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
        val editor = preferences.edit()

        editor.putString("CurrPuzzle", currPuzzle)

        val guessesToSave = encodeGuesses()
        editor.putString("Guesses", guessesToSave)

        // TODO - store possibles.
//        val possiblesToSave = encodePossibles()
//        editor.putString("Possibles", possiblesToSave)

        editor.apply()
        Log.d(TAG, "Saved game state.")
    }

    /**
     * Restores the Game state from the last time it was saved.
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")

        var restoredGame = preferences.getString("CurrPuzzle", null)
        currPuzzle = if (restoredGame == null) {
            DEFAULTPUZZLE
        } else {
            restoredGame
        }
        extractPuzzleFromString(currPuzzle!!)

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

        // TODO: Restore the player's own "possibles" list:
        playerPossibles.clear()
        // 0=103000000, 0=103000000, ... etc
    }

    private fun extractPuzzleFromString(puzzleString: String) {
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

    private var currPuzzle = ""
    private var puzzleWidth = 5
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var playerGrid: MutableList<Int> = mutableListOf()
    private var playerHints: MutableList<Hint> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Longs.
    var playerPossibles: MutableMap<Int, String> = mutableMapOf()

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

        // TODO - encode possibles

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
        var guessString = ""
        // TODO
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
                Log.d(TAG, "Decode the hints...")
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

        // TODO - decode the possibles

        return StateVariables(grid, width, hints)
    }

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