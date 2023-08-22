package game.paulgross.kakuroplaystore

import android.util.Log

object GameplayDefinition {

    private val TAG = GameplayDefinition::class.java.simpleName

    private var engine: GameServer? = null

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

    fun setEngine(engine: GameServer) {
        this.engine = engine

        Log.d(TAG, "Plug in the gameplay functions.")
        engine.pluginGameplay(::handleGameplayMessage)
        engine.pluginEncodeState(::encodeState)
        engine.pluginDecodeState(::decodeState)
        engine.pluginSavePuzzle(::savePuzzle)
        engine.pluginRestorePuzzle(::restorePuzzle)
    }

    private fun handleGameplayMessage(im: GameServer.InboundMessage): Boolean {
        // TODO - return state changed flag
        Log.d(TAG, "Handling: $im")
        if (im.message.contains("Guess=")) {
            return submitGuess(im.message)
        }

        if (im.message.contains("Possible=")) {
            return markUnMarkPossible(im.message)
        }

        if (im.message.contains("RestartPuzzle")) {
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

    private fun submitGuess(message: String): Boolean {
        Log.d(TAG, "The user sent a guess: $message")

        val split = message.split("=")
        if (split.size != 2) {
            Log.e(TAG, "Invalid guess: $message")
            return false
        }
        val guess = split[1].split(",")
        if (guess.size != 2) {
            Log.e(TAG, "Invalid guess: $message")
            return false
        }

        try {
            val index = guess[0].toInt()
            val value = guess[1].toInt()
            playerGrid[index] = value
            playerPossibles.remove(index)
            Log.d(TAG, "NEW: Implemented guess!!!")
            return true
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Invalid guess: $message")
            return false
        }
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

    // TODO - create methods for decode guesses, hints and possibles.

    fun decodeState(stateString: String): StateVariables {
        Log.d(TAG, "decodeState() for [$stateString]")

        var width = 0
        val grid: MutableList<Int> = mutableListOf()
        val hints: MutableList<Hint> = mutableListOf()
        var possibles: MutableMap<Int, String> = mutableMapOf()

        // Example
        //w=4,g=-1:-1:0:0:-1:-1:0:0:0:-1:-1:-1:0:0:-1:-1
        // split on commas into key-value pairs
        val map: MutableMap<String, String> = mutableMapOf()
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

    /**
     * Saves the current Game state.
     */
    private fun savePuzzle() {
        engine?.saveData("CurrPuzzle", currPuzzle)

        val guessesToSave = encodeGuesses()
        engine?.saveData("Guesses", guessesToSave)

        // TODO - store possibles.
        val possiblesToSave = encodePossibles()
        engine?.saveData("Possibles", possiblesToSave)
        Log.d(TAG, "Saved game state.")
    }

    fun restorePuzzle() {
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