package game.paulgross.kakuroplaystore

import android.util.Log

object KakuroGameplayDefinition: GameplayDefinition {

    private val TAG = KakuroGameplayDefinition::class.java.simpleName

    private var engine: GameEngine? = null

    // First 2 digits is the puzzle width.
    // From there, 0 is for non-playable squares, and any digit from 1..9 is for solution squares.
    private const val builtinPuzzlesFilename = "builtin_puzzles.txt"

    private val builtinPuzzles: MutableList<String> = mutableListOf()
    private val puzzleKeys: MutableMap<String, String> = mutableMapOf()

    private var currPuzzle = ""
    private var puzzleWidth = 1
    private var puzzleHeight = 1
    private var puzzleSolution: MutableList<Int> = mutableListOf()
    private var puzzleHints: MutableList<Hint> = mutableListOf()
    private val playerErrors: MutableSet<Int> = mutableSetOf()

    // Same as puzzle string but:
    // 0 for non-puzzle squares
    // -1 for not yet guessed
    // digit for guess
    private var playerGuesses: MutableList<Int> = mutableListOf()

    // Possibles are user defined, and coded as 9-digit Strings, with each digit position matching the possible value.
    private var playerPossibles: MutableMap<Int, String> = mutableMapOf()

    data class StateVariables(var playerGrid: MutableList<Int>, var puzzleWidth:Int, var puzzleHeight:Int,
                              var playerHints:MutableList<Hint>, var possibles: MutableMap<Int, String>,
                                var playerErrors: MutableSet<Int> ,var solved: Boolean)

    enum class Direction {ACROSS, DOWN}
    data class Hint(val index: Int, val direction: Direction, var total: Int)

    override fun setEngine(engine: GameEngine) {
        this.engine = engine

        // Load the built-in puzzles.
        engine.assets.open(builtinPuzzlesFilename).bufferedReader().forEachLine () {
            if (!it.startsWith("#")) {
                val currPuzzleString = it.replace("\\s".toRegex(), "")
                if (currPuzzleString.isNotEmpty()) {
                    builtinPuzzles.add(currPuzzleString)
                    val puzzleKey = obfuscatePuzzleString(currPuzzleString)
                    puzzleKeys[currPuzzleString] = puzzleKey
                    // TODO - use the keys as a string to store info about each puzzle.
                }
            }
        }
        Log.d(TAG, "Loaded ${builtinPuzzles.size} built-in puzzles.")

        Log.d(TAG, "Plugin the gameplay functions ...")
        engine.registerHandler("Guess", ::submitGuess)
        engine.registerHandler("Possible", ::togglePossible)
        engine.registerHandler("RestartPuzzle", ::restartPuzzle)

        engine.registerHandler("PrevPuzzle", ::prevPuzzle)
        engine.registerHandler("NextPuzzle", ::nextPuzzle)

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

    private fun obfuscatePuzzleString(puzzleString: String) : String {
        // Pair up the string into decimal pairs (0 - 99), with a zero-pad if there is an odd string length.
        // convert each decimal pair to HEX (0 - 63) and add one by one to a string
        // then reverse the HEX digits.

        var hexString = ""
        var index = 0

        while (index < puzzleString.length) {
            var pairDecimal = 0
            val digit1 = puzzleString[index]
            if (digit1 != null) {
                pairDecimal = 10 * digit1.toString().toInt()
            }
            if (index + 1 < puzzleString.length) {
                val digit2 = puzzleString[index + 1]
                pairDecimal += digit2.toString().toInt()
            }
            val hex = Integer.toHexString(pairDecimal)
            hexString += hex

            index += 2
        }
        Log.d(TAG, "Puzzle Key: ${hexString.reversed()}")

        return hexString.reversed()
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
            return false
        }
        if (value < 1 || value > 9) {
            return false
        }

        // Don't allow possibles if there is currently a guess
        if (playerGuesses[index] > 0) {
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

        // Remove or update the range of possibles.
        if (possible == "000000000") {
            playerPossibles.remove(index)
        } else {
            playerPossibles[index] = possible
        }

        return true
    }

    private fun encodeState(): GameEngine.Message {
        val message = GameEngine.Message("State")
        message.setKeyString("w", puzzleWidth.toString())
        message.setKeyString("g", encodePlayerGuesses(playerGuesses))
        message.setKeyString("h", encodeHints(puzzleHints))
        if (playerPossibles.isNotEmpty()) {
            message.setKeyString("p", encodePossibles(playerPossibles))
        }
        if (playerErrors.isNotEmpty()) {
            message.setKeyString("e", encodeErrors(playerErrors))
        }
        if (isSolved()) {
            message.setKeyString("s", "1")
        }
        return message
    }

    private fun decodeState(message: GameEngine.Message): StateVariables {
        Log.d(TAG, "DecodeState() for [$message]")

        if (message.missingString("w") || message.missingString("g") || message.missingString("h") ) {
            Log.d(TAG, "Missing width, grid and and/or hints.")
            return StateVariables(mutableListOf(), 0, 0, mutableListOf(), mutableMapOf(), mutableSetOf(), false)
        }

        val width = message.getString("w")?.toInt()
        if (width == null || width < 1) {
            Log.d(TAG, "Invalid width ${message.getString("w")}.")
            return StateVariables(mutableListOf(), 0, 0, mutableListOf(), mutableMapOf(), mutableSetOf(), false)
        }

        var possibles: MutableMap<Int, String> = mutableMapOf()
        if (message.hasString("p")) {
            possibles = decodePossibles(message.getString("p")!!)
        }

        var playerErrors: MutableSet<Int> = mutableSetOf()
        if (message.hasString("e")) {
            playerErrors = decodeErrors(message.getString("e")!!)
        }

        val guesses = decodePlayerGuesses(message.getString("g")!!)
        val height = guesses.size / width

        val solved = message.getString("s") != null

        return StateVariables(guesses, width, height,
            decodeHints(message.getString("h")!!), possibles, playerErrors, solved)
    }

    private fun encodePlayerGuesses(playerGuesses: Any): String {
        if (playerGuesses !is MutableList<*>) {
            return ""
        }

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

    private fun encodeHints(puzzleHints: Any): String {
        if (puzzleHints !is MutableList<*>) {
            return ""
        }

        var hints = ""
        puzzleHints.forEachIndexed { index, hint ->
            if (hint is Hint) {
                hint.index
                hint.direction
                hint.total
                hints += "${hint.index}${hint.direction}${hint.total}"
                if (index < puzzleHints.size - 1) {
                    hints += ":"
                }
            }
        }

        return hints
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

        val guessesToSave = encodePlayerGuesses(playerGuesses)
        val guessesToSaveString = "$currPuzzle.Guesses"
        engine?.saveDataString(guessesToSaveString, guessesToSave)

        val possiblesToSave = encodePossibles(playerPossibles)
        engine?.saveDataString("$currPuzzle.Possibles", possiblesToSave)
        Log.d(TAG, "Saved game state.")
    }

    private fun restoreState() {
        if (engine == null) {
            Log.d(TAG, "ERROR - no engine...")
            return
        }
        val restoredGame = engine?.loadDataString("CurrPuzzle", "").toString()
        currPuzzle = if (restoredGame == "") {
            Log.d(TAG, "USING DEFAULT PUZZLE!!!!")
            builtinPuzzles[0]
        } else {
            restoredGame
        }
        Log.d(TAG, "restoreState(): currPuzzle = $currPuzzle")

        startPuzzleFromString(currPuzzle)
    }

    private fun restoreCurrentPuzzleState() {

        val guessesKeyString = "$currPuzzle.Guesses"
        val guessesString = engine?.loadDataString(guessesKeyString, "")

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

        val possiblesString = engine?.loadDataString("$currPuzzle.Possibles", "")
        playerPossibles = decodePossibles(possiblesString!!)

        markPlayerErrors()
    }

    private fun prevPuzzle(message: GameEngine.Message): Boolean {
        for (index in 1..< builtinPuzzles.size) {
            if (currPuzzle == builtinPuzzles[index]) {
                startPuzzleFromString(builtinPuzzles[index - 1])
                return true
            }
        }
        // TODO - If no puzzle found, use default puzzle

        return false
    }
    private fun nextPuzzle(message: GameEngine.Message): Boolean {
        for (index in 0..< builtinPuzzles.size-1) {
            if (currPuzzle == builtinPuzzles[index]) {
                startPuzzleFromString(builtinPuzzles[index + 1])
                return true
            }
        }
        // TODO - If no puzzle found, use default puzzle

        return false
    }

    private fun startPuzzleFromString(puzzleString: String) {
        currPuzzle = puzzleString

        puzzleWidth = puzzleString.substring(0, 2).toInt()

        playerGuesses.clear()  // need to set this up as for the new puzzle case...

        puzzleSolution.clear()
        for (char in puzzleString.substring(2)) {
            if (char == '0') {
                puzzleSolution.add(-1)
                playerGuesses.add(-1)
            } else {
                puzzleSolution.add(char.digitToInt())
                playerGuesses.add(0)
            }
        }
        puzzleHeight = puzzleSolution.size / puzzleWidth
        Log.d(TAG, "Puzzle height = $puzzleHeight")
        puzzleHints.clear()
        generateHints()

        playerErrors.clear()
        playerPossibles.clear()

        restoreCurrentPuzzleState()
    }

    private fun encodeErrors(playerErrors: Any): String {
        if (playerErrors !is MutableSet<*>) {
            return ""
        }

        var errorString = ""
        playerErrors.forEach { squareIndex ->
            if (errorString.isNotEmpty()) {
                errorString += ":"
            }
            errorString += squareIndex.toString()
        }

        return errorString
    }

    private fun decodeErrors(errorString: String): MutableSet<Int> {
        val output: MutableSet<Int> = mutableSetOf()

        errorString.split(":").forEach { index ->
            // TODO: Handle invalid strings
            output.add(index.toInt())
        }
        return output
    }

    private fun encodePossibles(playerPossibles: Any): String {
        if (playerPossibles !is MutableMap<*, *>) {
            return ""
        }

        var possiblesString = ""
        // ampersand separated entries, colon separated index and possibles.
        // 0:1234567&5:100450000
        // TODO - eliminate firstEntry variable by using possiblesString.isNotEmpty() instead.
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

    private fun isSolved(): Boolean {
        if (playerErrors.isNotEmpty()) {
            return false
        }

        // Look for any blank guesses
        this.playerGuesses.forEach {
            if (it == 0) {
                return false
            }
        }

        return true
    }

    private fun markPlayerErrors() {
        playerErrors.clear()

        // Compare user's guesses against the hints, and look for duplicate numbers in rows and columns.
        puzzleHints.forEach { hint ->
            val index = hint.index
            val actualTotal = hint.total

            val squares = findSolutionSquares(playerGuesses, puzzleWidth, index, hint.direction)

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
                // Numbers in the first column always need a hint.
                val isFirstColumn = (index.mod(puzzleWidth) == 0)
                if (isFirstColumn || puzzleSolution[index - 1] == -1) {
                    val squares = findSolutionSquares(puzzleSolution, puzzleWidth, index, Direction.ACROSS)
                    var sum = 0
                    squares.forEach { puzzleIndex -> sum += puzzleSolution[puzzleIndex] }
                    puzzleHints.add(Hint(index, Direction.ACROSS, sum))
                }

                // Check for DOWN hints (don't check last row)
                // Numbers in the first row always need a hint.
                val isFirstRow = (index < puzzleWidth)
                if (isFirstRow || puzzleSolution[index - puzzleWidth] == -1) {
                    val squares = findSolutionSquares(puzzleSolution, puzzleWidth, index, Direction.DOWN)
                    var sum = 0
                    squares.forEach { puzzleIndex -> sum += puzzleSolution[puzzleIndex] }
                    puzzleHints.add(Hint(index, Direction.DOWN, sum))
                }
            }
        }
    }

    /**
     * Makes a list of all sequential solution squares starting at the index square,
     * going in the specified direction, ending before the first -1 square or the boundary of the grid.
     */
    private fun findSolutionSquares(grid: MutableList<Int>, width: Int, startIndex: Int, direction: Direction): List<Int> {
        val squares: MutableList<Int> = mutableListOf()

        var limit  = grid.size
        var stepSize = 1

        if (direction == Direction.DOWN) {
            stepSize = width
        }
        if (direction == Direction.ACROSS) {
            val currRow = (startIndex + 1).div(width)
            limit = width * (currRow + 1)
        }

        var index = startIndex
        while (index < limit && grid[index] != -1) {
            squares.add(index)
            index += stepSize
        }

        return squares
    }
}