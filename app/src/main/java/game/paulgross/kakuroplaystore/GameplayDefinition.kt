package game.paulgross.kakuroplaystore

import android.util.Log

class GameplayDefinition {

    init {
        GameServer.pluginGameplay(::handleGameplayMessage)
    }

    companion object {
        private val TAG = GameplayDefinition::class.java.simpleName

        private var currPuzzle = ""
        private var puzzleWidth = 5
        private var puzzleSolution: MutableList<Int> = mutableListOf()
        private var playerGrid: MutableList<Int> = mutableListOf()
        private var playerHints: MutableList<GameServer.Hint> = mutableListOf()

        // Possibles are user defined, and coded as 9-digit Strings.
        private var playerPossibles: MutableMap<Int, String> = mutableMapOf()

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
    }
}