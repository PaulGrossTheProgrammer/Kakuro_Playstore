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

    private val loopDelayMilliseconds = 500L
    private val autoStatusDelayMilliseconds = 5000L
    private val autoStatusCount = autoStatusDelayMilliseconds.div(loopDelayMilliseconds)
    private var autoStatusCountdown = 0L

    override fun run() {

        restoreGameState()
//        initGameState(structure, puzzleWidth, puzzleSolution)

        while (gameIsRunning.get()) {
            val activityRequest = fromActivitiesToGameSeverQ.poll()  // Non-blocking read.
            if (activityRequest != null) {
                handleActivityMessage(activityRequest)
            }

            val clientHandlerMessage = fromClientHandlerToGameServerQ.poll()  // Non-blocking read.
            if (clientHandlerMessage != null) {
                // TODO - clear at least a few client requests if there are many queued up.
                handleClientHandlerMessage(clientHandlerMessage.requestString, clientHandlerMessage.responseQ)
            }

            val clientMessage = fromClientToGameServerQ.poll()  // Non-blocking read.
            if (clientMessage != null) {
                handleClientMessage(clientMessage.requestString, clientMessage.responseQ)
            }

            if (gameMode == GameMode.CLIENT) {
                // Automatically request a new status after a delay
                autoStatusCountdown--
                if (autoStatusCountdown < 1) {
                    autoStatusCountdown = autoStatusCount
                    socketClient?.messageFromGameServer("status:")
                }
            }

            sleep(loopDelayMilliseconds)  // Pause for a short time...
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

        autoStatusCountdown = 0
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
            responseQ.add("s:${encodeState()}")
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
        if (message.startsWith("s:", true)) {
            val remoteState = message.substringAfter("s:")
            autoStatusCountdown = autoStatusCount  // Reset the auto-request countdown.

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
        socketServer?.pushMessageToClients("s:${encodeState()}")
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
        Log.d(TAG, "TODO: Save game state...")
        // TODO:

        val editor = preferences.edit()

        // Sample of saving a state
//        editor.putString("CurrPlayer", currPlayer.toString())
//        editor.apply()
//        Log.d(TAG, "Saved game state.")
    }

    /**
     * Restores the App state from the last time it was running.
     */
    private fun restoreGameState() {
        Log.d(TAG, "TODO: Restoring previous game state...")
        // TODO:

        // Each puzzle is a  grid with blank squares as zeros,
        // and the solution as numbers from 1 to 9 in the non-zero squares.
        // THe player sees the grid with zeros as blanks, the guesses as integers,
        // and any square they haven't guessed is -1.
        // The player also sees the hints, which are ACROSS and DOWN clues derived
        // from the solution.

        // Sample of a saved state:
//        currPlayer = SquareState.valueOf(preferences.getString("CurrPlayer", "X").toString())

        // Testing - the "solution" grid with a "width" of 4:
        puzzleWidth = 4
        puzzleSolution = mutableListOf(
              3,  1,  0,  0,
              8,  2,  0,  0,
              0,  6,  9,  8,
              0,  0,  7,  1
        )

        // The player's initial "grid" view:
        // As the player makes guesses, the -1's are replaced with numbers from 1 to 9.
        playerGrid = mutableListOf(
            -1, -1,  0,  0,
            -1, -1,  0,  0,
             0, -1, -1, -1,
             0,  0, -1, -1
        )

        // Plus the "hints" for the player:
        // 0-ACROSS = 4, 0-DOWN = 11, 4-ACROSS = 10, ... etc
        generateHints()

        //        Log.d(TAG, "DEBUG All hints:")
        playerHints.forEach() { hint ->
            Log.d(TAG, "hint: $hint")
        }

        // Plus the player's own "possibles" list:
        // 0=3&1, 0=3/1, ... etc
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
            if (value  != 0) {
                // Check for ACROSS hints.
                // First column numbers always need a hint.
                val isFirstColumn = (index.mod(puzzleWidth) == 0)
                if (isFirstColumn || puzzleSolution[index - 1] == 0) {
                    // TODO - sum the group of numbers across
                    val sum = sumOfSquares(puzzleSolution, puzzleWidth, index, Direction.ACROSS)
                    playerHints.add(Hint(index, Direction.ACROSS, sum))
                }

                // Check for DOWN hints (don't check last row)
                // First colum row always need a hint.
                val isFirstRow = (index < puzzleWidth)
                if (isFirstRow || puzzleSolution[index - puzzleWidth] == 0) {
                    // TODO - sum the group of numbers down
                    val sum = sumOfSquares(puzzleSolution, puzzleWidth, index, Direction.DOWN)
                    playerHints.add(Hint(index, Direction.DOWN, sum))
                }
            }
        }
    }

    private fun sumOfSquares(grid: MutableList<Int>, width: Int, startIndex: Int, direction: Direction): Int {
        // TODO ...
        var sum = 0

        var stepSize = 1
        if (direction == Direction.DOWN) {
            stepSize = width
        }

        var index = startIndex
        while (index < grid.size && grid[index] != 0) {
            sum += grid[index]
            index += stepSize
        }

        return sum
    }

    private var puzzleWidth = 5
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var playerGrid: MutableList<Int> = mutableListOf()
    private var playerHints: MutableList<Hint> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Longs.
    var playerPossibles: Array<Long> = Array(10) {0} // Same size Array as the solution

    data class StateVariables(var playerGrid: MutableList<Int>, var puzzleWidth:Int)

    private fun encodeState(): String {
        var state = ""

        state += "w=$puzzleWidth,"
        state += "g="
        playerGrid.forEachIndexed {index, squareValue ->
            state += squareValue.toString()
            if (index < playerGrid.size - 1) {
                state += ":"
            }
        }

        // TODO - encode hints? Only need to send this once...

        return state
    }

    fun decodeState(stateString: String): StateVariables {
        Log.d(TAG, "decodeState() for [$stateString]")

        var width = 0
        var grid:MutableList<Int> = mutableListOf()

        // Example
        //w=4,g=-1:-1:0:0:-1:-1:0:0:0:-1:-1:-1:0:0:-1:-1
        // TODO
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
                ints.forEach{theIntString ->
                    grid.add(theIntString.toInt())
                }
            }
        }

        return StateVariables(grid, width)
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
        }

        fun queueClientHandlerMessage(message: String, responseQ: Queue<String>) {
            if (singletonGameServer?.gameIsRunning!!.get()) {
                singletonGameServer?.fromClientHandlerToGameServerQ?.add(ClientRequest(message, responseQ))
            }
        }

        fun queueClientMessage(message: String, responseQ: Queue<String>) {
            if (singletonGameServer?.gameIsRunning!!.get()) {
                singletonGameServer?.fromClientToGameServerQ?.add(ClientRequest(message, responseQ))
            }
        }

        fun decodeState(stateString: String): StateVariables? {
            return singletonGameServer?.decodeState(stateString)
        }
    }
}