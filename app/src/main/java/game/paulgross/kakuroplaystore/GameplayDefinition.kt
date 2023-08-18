package game.paulgross.kakuroplaystore

import android.util.Log

object GameplayDefinition {

    private val TAG = GameplayDefinition::class.java.simpleName

    init {
        // TODO - also need the preferences pointer (getPreferences(MODE_PRIVATE)) to load and save state.
        Log.d(TAG, "Initialising the gameplay definition...")
        GameServer.pluginGameplay(::handleGameplayMessage)
    }

    private var currPuzzle = ""
    private var puzzleWidth = 5
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var playerGrid: MutableList<Int> = mutableListOf()
    private var playerHints: MutableList<GameServer.Hint> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Strings.
    private var playerPossibles: MutableMap<Int, String> = mutableMapOf()

    private var engine: GameServer? = null
    fun setEngine(engine: GameServer) {
        this.engine = engine
    }

    // TODO -- Call from the GameServer
    fun restoreGame() {
        if (engine == null) {
            return
        }

        val currPuzzle = engine?.restoreData("CurrPuzzle", "")
        // TODO etc...

    }

    private fun handleGameplayMessage(im: GameServer.InboundMessage) {
        Log.d(TAG, "Handling: $im")
        if (im.message.contains("Guess=")) {
            submitGuess(im.message)
        }
    }

    private fun submitGuess(message: String): Boolean {
        Log.d(TAG, "The user sent a guess: $message")

        // For now, just abort...
        return false

        val split = message.split("=")
        val guess = split[1].split(",")

        // TODO - handle invalid Ints...
        val index = guess[0].toInt()
        val value = guess[1].toInt()
        playerGrid[index] = value
        playerPossibles.remove(index)

        return true
    }

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
                    val sum = sumOfSquares(puzzleSolution, puzzleWidth, index, GameServer.Direction.ACROSS)
                    playerHints.add(GameServer.Hint(index, GameServer.Direction.ACROSS, sum))
                }

                // Check for DOWN hints (don't check last row)
                // First colum row always need a hint.
                val isFirstRow = (index < puzzleWidth)
                if (isFirstRow || puzzleSolution[index - puzzleWidth] == -1) {
                    val sum = sumOfSquares(puzzleSolution, puzzleWidth, index, GameServer.Direction.DOWN)
                    playerHints.add(GameServer.Hint(index, GameServer.Direction.DOWN, sum))
                }
            }
        }
    }

    private fun sumOfSquares(grid: MutableList<Int>, width: Int, startIndex: Int, direction: GameServer.Direction): Int {
        var sum = 0

        var stepSize = 1
        if (direction == GameServer.Direction.DOWN) {
            stepSize = width
        }

        var index = startIndex
        while (index < grid.size && grid[index] != -1) {
            sum += grid[index]
            index += stepSize
        }

        return sum
    }
}