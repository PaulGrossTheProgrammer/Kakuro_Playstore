package game.paulgross.kakuroplaystore

import android.util.Log

object KakuroGameplayDefinition: GameplayDefinition {

    private val TAG = KakuroGameplayDefinition::class.java.simpleName

    private var engine: GameEngine? = null

    private const val DEFAULT_PUZZLE = "043100820006980071"

    private var currPuzzle = ""
    private var puzzleWidth = 1
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var puzzleHints: MutableList<Hint> = mutableListOf()
    private var playerGuesses: MutableList<Int> = mutableListOf()
    private val playerErrors: MutableSet<Int> = mutableSetOf()

    // Possibles are user defined, and coded as 9-digit Strings, with each digit position matching the possible value.
    private var playerPossibles: MutableMap<Int, String> = mutableMapOf()

    data class StateVariables(var playerGrid: MutableList<Int>, var puzzleWidth:Int,
                              var playerHints:MutableList<Hint>, var possibles: MutableMap<Int, String>,
                                var playerErrors: MutableSet<Int>)

    enum class Direction {ACROSS, DOWN}
    data class Hint(val index: Int, val direction: Direction, var total: Int)

    override fun setEngine(engine: GameEngine) {
        this.engine = engine

        Log.d(TAG, "Plugin the gameplay functions ...")
        engine.registerHandler("Guess", ::submitGuess)
        engine.registerHandler("Possible", ::togglePossible)
        engine.registerHandler("RestartPuzzle", ::restartPuzzle)
        // TODO - implement an undo button...

        // TODO - Allow a pluginDecodeState() that is used to restore the saved game by default.
        // TODO - BUT decodeState returns StateVariables from this class, which isn't generic.
        // Maybe a Map is generic???
        engine.pluginEncodeState(::encodeState)
        engine.pluginDecodeState(::decodeState)

        // Override the default save and load usage of encode and decode state.
        engine.pluginSaveState(::saveState)
        engine.pluginRestoreState(::restoreState)
    }

    private fun restartPuzzle(message: GameEngine.Message): Boolean {
        for (i in 0 until playerGuesses.size) {
            if (playerGuesses[i] != -1) {
                playerGuesses[i] = 0
            }
        }
        playerPossibles.clear()
        playerErrors.clear()
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

        if (index < 0 || index >= playerGuesses.size) {
            Log.d(TAG, "Index is outside of grid boundary.")
            return false
        }
        if (value < 0 || value > 9) {
            Log.d(TAG, "Digit must be from 0 to 9.")
            return false
        }

        playerGuesses[index] = value
        playerPossibles.remove(index)

        markPlayerErrors()
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

        if (index < 0 || index >= playerGuesses.size) {
            Log.d(TAG, "Index is outside of grid boundary.")
            return false
        }
        if (value < 1 || value > 9) {
            Log.d(TAG, "Digit must be from 1 to 9.")
            return false
        }

        // Don't allow possibles if there is currently a guess
        if (playerGuesses[index] > 0) {
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
        state += "g=" + encodePlayerGuesses()

        // h=2ACROSS13:2DOWN23 ... etc
        // TODO - call a method to encode
        state += ",h="
        puzzleHints.forEachIndexed { index, hint ->
            hint.index
            hint.direction
            hint.total
            state += "${hint.index}${hint.direction}${hint.total}"
            if (index < puzzleHints.size - 1) {
                state += ":"
            }
        }

        // p=2:0123000000,8:0000006780
        if (playerPossibles.isNotEmpty()) {
            state += ",p="
            state += encodePossibles()
        }

        if (playerErrors.isNotEmpty()) {
            state += ",e="
            state += encodeErrors()
        }

        return state
    }

    private fun decodeState(message: GameEngine.Message): StateVariables {
        Log.d(TAG, "DecodeState() for [$message]")

        if (message.missingString("w") || message.missingString("g") || message.missingString("h") ) {
            Log.d(TAG, "Missing width, grid and and/or hints.")
            return StateVariables(mutableListOf(), 0, mutableListOf(), mutableMapOf(), mutableSetOf())
        }

        val width = message.getString("w")?.toInt()
        if (width == null || width < 1) {
            Log.d(TAG, "Invalid width ${message.getString("w")}.")
            return StateVariables(mutableListOf(), 0, mutableListOf(), mutableMapOf(), mutableSetOf())
        }

        var possibles: MutableMap<Int, String> = mutableMapOf()
        if (message.hasString("p")) {
            possibles = decodePossibles(message.getString("p")!!)
        }

        var playerErrors: MutableSet<Int> = mutableSetOf()
        if (message.hasString("e")) {
            playerErrors = decodeErrors(message.getString("e")!!)
        }

        return StateVariables(decodePlayerGuesses(message.getString("g")!!), width,
            decodeHints(message.getString("h")!!), possibles, playerErrors)
    }

    private fun encodePlayerGuesses(): String {
        var guessString = ""
        playerGuesses.forEachIndexed { index, squareValue ->
            guessString += squareValue.toString()
            if (index < playerGuesses.size - 1) {
                guessString += ":"
            }
        }
        return guessString
    }

    private fun decodePlayerGuesses(guessesString: String): MutableList<Int> {
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
        engine?.saveDataString("CurrPuzzle", currPuzzle)

        val guessesToSave = encodePlayerGuesses()
        engine?.saveDataString("Guesses", guessesToSave)

        val possiblesToSave = encodePossibles()
        engine?.saveDataString("Possibles", possiblesToSave)
        Log.d(TAG, "Saved game state.")
    }

    private fun restoreState() {
        if (engine == null) { // TODO - can I avoid this check???
            Log.d(TAG, "ERROR - no engine...")
            return
        }

        val restoredGame = engine?.loadDataString("CurrPuzzle", "").toString()
        currPuzzle = if (restoredGame == "") {
            Log.d(TAG, "USING DEFAULT PUZZLE!!!!")
            DEFAULT_PUZZLE
        } else {
            restoredGame
        }
        Log.d(TAG, "currPuzzle = $currPuzzle")

        startPuzzleFromString(currPuzzle)
        puzzleHints.clear()
        generateHints()

        val guessesString = engine?.loadDataString("Guesses", "")

        Log.d(TAG, "guessesString = $guessesString")

        playerGuesses.clear()
        if (guessesString == "") {
            puzzleSolution.forEach { square ->
                if (square == -1) {
                    playerGuesses.add(-1)
                } else {
                    playerGuesses.add(0)
                }
            }
        } else {
            val guessList = guessesString?.split(":")
            guessList?.forEach {guessString ->
                playerGuesses.add(guessString.toInt())
            }
        }
        Log.d(TAG, "Size of play grid = ${playerGuesses.size}")

        val possiblesString = engine?.loadDataString("Possibles", "")
        playerPossibles = decodePossibles(possiblesString!!)

        markPlayerErrors()
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

    private fun encodeErrors(): String {
        var errorString = ""
        playerErrors.forEachIndexed {index, squareIndex ->
            errorString += squareIndex.toString()
            if (index < playerErrors.size - 1) {
                errorString += ":"
            }
        }
        return errorString
    }

    private fun decodeErrors(errorString: String): MutableSet<Int> {
        var output: MutableSet<Int> = mutableSetOf()

        errorString.split(":").forEach { index ->
            // TODO: Handle invalid strings
            output.add(index.toInt())
        }
        return output
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

    private fun markPlayerErrors() {
        playerErrors.clear()

        // Compare user's guesses against the hints, and look for duplicate numbers in rows and columns.
        puzzleHints.forEach { hint ->
            val index = hint.index
            val actualTotal = hint.total

            val squares = allSquares(playerGuesses, puzzleWidth, index, hint.direction)

            var playerSum = 0
            var incomplete = false
            val valuesSet: MutableSet<Int> = mutableSetOf()
            val duplicatesSet: MutableSet<Int> = mutableSetOf()
            squares.forEach {square ->
                val value = playerGuesses[square]
                if (value == 0) {
                    incomplete = true
                } else {
                    playerSum += value
                    if (valuesSet.contains(value)) {
                        duplicatesSet.add(value)
                    } else {
                        valuesSet.add(value)
                    }
                }
            }

            // Mark any complete row/col if it doesn't match the hint.
            if (!incomplete && playerSum != actualTotal) {
                playerErrors.addAll(squares)
            }

            // Mark all squares that match found duplicates.
            if (duplicatesSet.size != 0) {
                // Determine the indexes with duplicates and mark them as errors.
                squares.forEach {square ->
                    if (duplicatesSet.contains(playerGuesses[square])) {
                        playerErrors.add(square)
                    }
                }
            }
        }
    }

    /**
     * Create all the ACROSS and DOWN hints based on the puzzleSolution.
     */
    private fun generateHints() {

        // Traverse the solution grid and create hints for any number squares with empty squares to the left and/or above.
        puzzleSolution.forEachIndexed { index, value ->
            if (value  != -1) {
                // Check for ACROSS hints.
                // First column numbers always need a hint.
                val isFirstColumn = (index.mod(puzzleWidth) == 0)
                if (isFirstColumn || puzzleSolution[index - 1] == -1) {
                    val squares = allSquares(puzzleSolution, puzzleWidth, index, Direction.ACROSS)
                    Log.d(TAG, "Hint: $index ACROSS squares = $squares")
                    var sum = 0
                    squares.forEach { puzzleIndex -> sum += puzzleSolution[puzzleIndex] }
                    puzzleHints.add(Hint(index, Direction.ACROSS, sum))
                }

                // Check for DOWN hints (don't check last row)
                // First colum row always need a hint.
                val isFirstRow = (index < puzzleWidth)
                if (isFirstRow || puzzleSolution[index - puzzleWidth] == -1) {
                    val squares = allSquares(puzzleSolution, puzzleWidth, index, Direction.DOWN)
                    Log.d(TAG, "Hint: $index DOWN squares = $squares")
                    var sum = 0
                    squares.forEach { puzzleIndex -> sum += puzzleSolution[puzzleIndex] }
                    puzzleHints.add(Hint(index, Direction.DOWN, sum))
                }
            }
        }
    }

    /**
     * Makes a list of all squares starting at the index square, going in the specified direction,
     * ending before the -1 square or the boundary of the grid.
     */
    private fun allSquares(grid: MutableList<Int>, width: Int, startIndex: Int, direction: Direction): List<Int> {
        val squares: MutableList<Int> = mutableListOf()

        var stepSize = 1
        if (direction == Direction.DOWN) {
            stepSize = width
        }

        var index = startIndex
        while (index < grid.size && grid[index] != -1) {
            squares.add(index)
            index += stepSize
        }

        return squares
    }
}