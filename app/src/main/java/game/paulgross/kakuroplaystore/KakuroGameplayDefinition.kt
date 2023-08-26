package game.paulgross.kakuroplaystore

import android.util.Log

object KakuroGameplayDefinition: GameplayDefinition {

    private val TAG = KakuroGameplayDefinition::class.java.simpleName

    private var engine: GameEngine? = null

    private const val DEFAULT_PUZZLE = "043100820006980071"

    private var currPuzzle = ""
    private var puzzleWidth = 5
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var playerGrid: MutableList<Int> = mutableListOf()
    private var playerHints: MutableList<Hint> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Strings.
    private var playerPossibles: MutableMap<Int, String> = mutableMapOf()

    data class StateVariables(var playerGrid: MutableList<Int>, var puzzleWidth:Int,
                              var playerHints:MutableList<Hint>, var possibles: MutableMap<Int, String>)

    enum class Direction {ACROSS, DOWN}
    data class Hint(val index: Int, val direction: Direction, var total: Int)

    override fun setEngine(engine: GameEngine) {
        this.engine = engine

        Log.d(TAG, "Plugin the gameplay functions.")
        engine.pluginGameplay(::handleGameplayMessage)
        engine.pluginEncodeState(::encodeState)

        // TODO - Allow a pluginDecodeState() that is used to restore the saved game.
        engine.pluginSaveState(::saveState)
        engine.pluginRestoreState(::restoreState)
    }

    private fun handleGameplayMessage(message: GameEngine.Message): Boolean {
        // TODO - break this into separate handler functions for Message types like Guess, Possible etc,
        // and register those with the handler.

        Log.d(TAG, "Handling: $message")
        if (message.type == "Guess") {
            return submitGuess(message)
        }

        if (message.type == "Possible") {
            return togglePossible(message)
        }

        if (message.type == "RestartPuzzle") {
            return restartPuzzle()
        }

        return false
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

    private fun submitGuess(message: GameEngine.Message): Boolean {
        Log.d(TAG, "The user sent a guess: $message")

        if (!message.hasString("Index") || !message.hasString("Value")) {
            Log.d(TAG, "Missing [Index] or [Value].")
            return false
        }

        var index = -1
        var value = -1
        try {
            index = message.getString("Index")?.toInt()!!
            value = message.getString("Value")?.toInt()!!
        } catch (e: NumberFormatException) {
            Log.d(TAG, "Invalid [Index] or [Value].")
            return false
        }

        if (index < 0 || index >= playerGrid.size) {
            Log.d(TAG, "Index is outside of grid boundary.")
            return false
        }
        if (value < 0 || value > 9) {
            Log.d(TAG, "Digit must be from 0 to 9.")
            return false
        }

        playerGrid[index] = value
        playerPossibles.remove(index)
        return true
    }

    private fun togglePossible(message: GameEngine.Message): Boolean {
        Log.d(TAG, "The user sent a possible: $message")

        if (message.missingString("Index") || message.missingString("Value")) {
            Log.d(TAG, "Missing [Index] or [Value].")
            return false
        }

        var index = -1
        var value = -1
        try {
            index = message.getString("Index")?.toInt()!!
            value = message.getString("Value")?.toInt()!!
        } catch (e: NumberFormatException) {
            Log.d(TAG, "Invalid [Index] or [Value].")
            return false
        }

        if (index < 0 || index >= playerGrid.size) {
            Log.d(TAG, "Index is outside of grid boundary.")
            return false
        }
        if (value < 1 || value > 9) {
            Log.d(TAG, "Digit must be from 1 to 9.")
            return false
        }

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

        // Get the replacement position from the value
        val digit = possible[value - 1]
        var replacement = "0"
        if (digit == '0') {
            replacement = value.toString()
        }

        // Insert the replacement at the digit value position.
        possible = possible.substring(0, value - 1) + replacement + possible.substring(value)

        // Remove ir update the range of possibles.
        if (possible == "000000000") {
            Log.d(TAG, "Removing index ...")
            playerPossibles.remove(index)
        } else {
            playerPossibles[index] = possible
        }

        return true
    }

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

    fun decodeState(message: GameEngine.Message): StateVariables {
        Log.d(TAG, "DecodeState() for [$message]")

        if (message.missingString("w") || message.missingString("g") || message.missingString("h") ) {
            Log.d(TAG, "Missing width, grid and and/or hints.")
            return StateVariables(mutableListOf(), 0, mutableListOf(), mutableMapOf())
        }

        val width = message.getString("w")?.toInt()
        if (width == null || width < 1) {
            Log.d(TAG, "Invalid width ${message.getString("w")}.")
            return StateVariables(mutableListOf(), 0, mutableListOf(), mutableMapOf())
        }

        var possibles: MutableMap<Int, String> = mutableMapOf()
        if (message.hasString("p")) {
            possibles = decodePossibles(message.getString("p")!!)
        }

        return StateVariables(decodeGrid(message.getString("g")!!), width,
            decodeHints(message.getString("h")!!), possibles)
    }

    // TODO - create methods for decode guesses, hints and possibles.
    private fun decodeGrid(guessesString: String): MutableList<Int> {
        val grid: MutableList<Int> = mutableListOf()

        val ints = guessesString.split(":")
        ints.forEach {theIntString ->
            grid.add(theIntString.toInt())
        }

        return grid
    }

    private fun decodeHints(hintsString: String): MutableList<Hint> {
        val hints: MutableList<Hint> = mutableListOf()

        val hintList = hintsString.split(":")
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

        return hints
    }

    /**
     * Saves the current Game state.
     */
    private fun saveState() {
        engine?.saveData("CurrPuzzle", currPuzzle)

        val guessesToSave = encodeGuesses()
        engine?.saveData("Guesses", guessesToSave)

        // TODO - store possibles.
        val possiblesToSave = encodePossibles()
        engine?.saveData("Possibles", possiblesToSave)
        Log.d(TAG, "Saved game state.")
    }

    fun restoreState() {
        Log.d(TAG, "NEW - restoring puzzle")

        if (engine == null) {
            Log.d(TAG, "ERROR - no engine...")
            return
        }

        val restoredGame = engine?.restoreData("CurrPuzzle", "").toString()
        currPuzzle = if (restoredGame == "") {
            Log.d(TAG, "USING DEFAULT PUZZLE!!!!")
            DEFAULT_PUZZLE
        } else {
            restoredGame
        }
        Log.d(TAG, "currPuzzle = $currPuzzle")

        startPuzzleFromString(currPuzzle)
        playerHints.clear()
        generateHints()

        val guessesString = engine?.restoreData("Guesses", "")

        Log.d(TAG, "guessesString = $guessesString")

        playerGrid.clear()
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
        Log.d(TAG, "Size of play grid = ${playerGrid.size}")

        val possiblesString = engine?.restoreData("Possibles", "")
        playerPossibles = decodePossibles(possiblesString!!)
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
}