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

    private val loopDelayMilliseconds = 25L
    private val autoStatusDelayMilliseconds = 5000L
    private val autoStatusCount = autoStatusDelayMilliseconds.div(loopDelayMilliseconds)
    private var autoStatusCountdown = 0L

    override fun run() {

        restoreGameState()

        // Testing
//        Log.d(TAG,"calcHints ...")
//        calcHints(structure, gameWidth, solution)

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

            messageGameplayDisplayStatus()
        }

        // TODO: Normal gameplay commands here...

        if (message == "status:") {
            validRequest = true
            responseQ.add("s:${encodeState("")}")
        }
        if (message == "shutdown" || message == "abandoned") {
            validRequest = true
            responseQ.add(message)
            remotePlayers.remove(responseQ)
            messageGameplayDisplayStatus()
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
                messageGameplayDisplayStatus()
            }
        }
        if (message == "shutdown" || message == "abandoned") {
            responseQ.add(message)
            switchToPureLocalMode()
        }
    }

    private fun handleActivityMessage(message: String) {
        if (message == "reset:") {
            resetGame()
            messageGameplayDisplayStatus()
        }
        if (message == "status:") {
            messageGameplayDisplayStatus()
        }

        // TODO - handle the normal gameplay commands

        if (message == "StartServer:") {
            if (gameMode != GameMode.SERVER) {
                switchToLocalServerMode()
            }
        }
        if (message == "StartLocal:") {
            if (gameMode != GameMode.LOCAL) {
                switchToPureLocalMode()
            }
        }
        if (message.startsWith("RemoteServer:")) {
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
        socketServer?.pushMessageToClients("s:${encodeState("")}")
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
     * Saves the current App state.
     */
    private fun saveGameState() {
        // TODO:

        val editor = preferences.edit()

        // Sample of saving a state
//        editor.putString("CurrPlayer", currPlayer.toString())
        editor.apply()
        Log.d(TAG, "Saved game state.")
    }

    /**
     * Restores the App state from the last time it was running.
     */
    private fun restoreGameState() {
        Log.d(TAG, "Restoring previous game state...")
        // TODO:

        // Sample of a saved state:
//        currPlayer = SquareState.valueOf(preferences.getString("CurrPlayer", "X").toString())
    }

    private fun messageGameplayDisplayStatus() {
        val intent = Intent()
        intent.action = context.packageName + GameplayActivity.DISPLAY_MESSAGE_SUFFIX
        intent.putExtra("State", encodeState(""))

        context.sendBroadcast(intent)
    }

    private fun resetGame() {
        // TODO:

        saveGameState()
        pushStateToClients()
    }

    data class StateVariables(var dummy: String)

    companion object {
        private val TAG = GameServer::class.java.simpleName

        enum class Direction {ACROSS, DOWN}
        data class Hint(val index: Int, val direction: Direction, var total: Int)

        /**
         * Calculate all the ACROSS and DOWN hints based on the structure and solution.
         */
        fun calcHints(structure: Array<Boolean>, width: Int, solution: Array<Int>): Array<Hint> {
            var hints = mutableListOf<Hint>()

            // Create the full array by combining the structure with the solution
            // 0 = empty, otherwise it's a solution number
            val grid = mutableListOf<Int>()
            var solutionIndex = 0
            structure.forEach {item ->
                if (!item) {
                    grid.add(0)
                } else {
                    grid.add(solution[solutionIndex])
                    solutionIndex++
                }
            }

            // Traverse the whole grid and look for empty squares with numbers to the right and/or below
            val maxIndex = grid.size - 1
            grid.forEachIndexed { index, value ->
                if (grid[index] == 0) {
                    // Check for ACROSS hints (don't check last column)
                    // Last column is when: (index + 1) DIV width has no remainder ???
                    if (index + 1 < maxIndex && grid[index + 1] != 0) {
                        // TODO - add up the group of numbers
                        val sum = sumOfSquares(grid, index + 1, Direction.ACROSS)
                        hints.add(Hint(index + 1, Direction.ACROSS, sum))
                    }

                    // Check for DOWN hints (don't check last row)
                    // Last row is when maxIndex - index < width ???
                    // Below is index + width
                    if (index + width < maxIndex && grid[index + width] != 0) {
                        // TODO - add up the group of numbers
                        val sum = sumOfSquares(grid, index + width, Direction.DOWN)
                        hints.add(Hint(index + width, Direction.DOWN, sum))
                    }
                }
            }

            Log.d(TAG, "All hints:")
            hints.forEach() {hint ->
                Log.d(TAG, "hint: $hint")
            }

            return hints.toTypedArray()
        }

        private fun sumOfSquares(grid: List<Int>, startIndex: Int, direction: Direction): Int {
            // TODO ...
            return 0
        }

        // TODO - can I put the game state here???
        // \ \ \ \ \
        // \ 3 1 \ \
        // \ 8 2 1 \
        // \ \ 6 9 8
        // \ \ \ 7 1

        var gameWidth = 5
        var structure: Array<Boolean> = arrayOf(
            false, false, false, false, false,
            false,  true,  true, false, false,
            false,  true,  true,  true, false,
            false, false,  true,  true,  true,
            false, false, false,  true,  true
        )
        // Note that the Hints can be derived from the solution and the structure
        // Note the size of the solution must exactly match the number of true flags in the structure
        // Need to check that this is always true.
        var solution: Array<Int> = arrayOf(3, 1, 8, 2, 1, 6, 9, 8, 7, 1)
        val hints = calcHints(structure, gameWidth, solution)
        // TODO: How are hints stored and transmitted?
        // Possibly: Mapping An array index plus a direction to a total.
        // Such as 1-Down = 11, 2-Down = 9, 5-Across = 4, etc ...
        // Scan whole array for empty squares
        // - any to the left of a number, calc Across total.
        // - any above a number, calc down total.

        // The user's current guesses.
        var guesses: Array<Int> = Array(10) {0}  // Same size Array as the solution

        // Possibles are user defined, and coded as 9-digit Longs.
        var possibles: Array<Long> = Array(10) {0} // Same size Array as the solution


        fun encodeState(dummy: String): String {
            var state = "TODO"

            return state
        }

        fun decodeState(stateString: String): StateVariables {
            Log.d(TAG, "decodeState() for [$stateString]")

            // TODO

            return StateVariables("TODO")
        }

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
    }
}