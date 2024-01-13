package game.paulgross.kakuroplaystore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


class KakuroGameplayActivity_Tv : AppCompatActivity() {
    var engine: GameEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        singleton = this

        setContentView(R.layout.activity_kakurogameplay)

        engine = GameEngine.activate(KakuroGameplayDefinition, this)

        // Request that the GameEngine send a state message to queueMessage() whenever the game state changes.
        enableQueuedMessages()
        engine?.queueMessageFromActivity(GameEngine.Message("RequestStateChanges"), ::queueMessage)

    }

    override fun onBackPressed() {
        confirmExitApp()
    }

    fun onClickSettings(view: View) {
        engine?.gotoSettingsActivity(this)
    }

    private var gameState: KakuroGameplayDefinition.StateVariables? = null

    /**
     * Update the custom playGridView with the new state and request a redraw.
     */
    private fun displayGrid(newestGameState: KakuroGameplayDefinition.StateVariables) {
        gameState = newestGameState

        if (checkForSolved == true) {
            if (gameState!!.solved) {
                Toast.makeText(this, "SOLVED!", Toast.LENGTH_LONG).show()
            }
            checkForSolved = false
        }

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.setGameState(newestGameState)
        playGridView.setScreenSizes()  // TODO: Is there a way to avoid redundant calls to setScreenSizes()???
        // NOTE: setScreenSizes() also forces a redraw.
    }

    //
    // User interface controls
    //

    fun onClickScrollUp(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).scrollGrid(0, 1)
    }

    fun onClickScrollDown(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).scrollGrid(0, -1)
    }

    fun onClickScrollLeft(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).scrollGrid(1, 0)
    }

    fun onClickScrollRight(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).scrollGrid(-1, 0)
    }

    fun onClickZoomIn(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).zoomGrid(-1)
    }

    fun onClickZoomOut(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).zoomGrid(1)
    }

    private var checkForSolved = false

    fun onClickDigit(view: View) {
        Log.d(TAG, "Clicked a guess: ${view.tag}")

        val selectedIndex = findViewById<PlayingGridView>(R.id.viewPlayGrid).getSelectedIndex()
        if (selectedIndex == -1) {
            return
        }

        checkForSolved = true  // This flag is used by the message receiver to react to the change if required

        val tag = view.tag.toString()
        val value = tag.substringAfter("Guess")
        val message = GameEngine.Message("Guess")
        message.setKeyString("Index", selectedIndex.toString())
        message.setKeyString("Value", value)
        engine?.queueMessageFromActivity(message, ::queueMessage)
    }

    fun onClickPossibleDigit(view: View) {
        val tag = view.tag.toString()

        val selectedIndex = findViewById<PlayingGridView>(R.id.viewPlayGrid).getSelectedIndex()
        if (selectedIndex == -1) {
            return
        }

        val value = tag.substringAfter("Possible")
        Log.d(TAG, "Possible digit: $value")

        val message = GameEngine.Message("Possible")
        message.setKeyString("Index", selectedIndex.toString())
        message.setKeyString("Value", value)
        engine?.queueMessageFromActivity(message, ::queueMessage)
    }

    fun onClickReset(view: View) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Restart Puzzle")
        builder.setMessage("Are you sure you want to restart?")
        builder.setPositiveButton("Reset") { _, _ ->
            findViewById<PlayingGridView>(R.id.viewPlayGrid).clearSelectedIndex()
            engine?.queueMessageFromActivity(GameEngine.Message("RestartPuzzle"), ::queueMessage)
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    fun onClickPrevPuzzle(view: View) {
        // Determine if there are user guesses.
        var guesses = false
        gameState?.playerGrid?.forEach {
            if (it > 0) {
                guesses = true
            }
        }
        if (guesses == false) {
            prevPuzzle()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Change Puzzle")
        builder.setMessage("Are you sure you want to change puzzle?")
        builder.setPositiveButton("Change") { _, _ ->
            prevPuzzle()
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    fun onClickNextPuzzle(view: View) {
        Log.d(TAG, "Clicked Puzzle Next")
        // Determine if there are user guesses.
        var guesses = false
        gameState?.playerGrid?.forEach {
            if (it > 0) {
                guesses = true
            }
        }
        if (guesses == false) {
            nextPuzzle()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Change Puzzle")
        builder.setMessage("Are you sure you want to change puzzle?")
        builder.setPositiveButton("Change") { _, _ ->
            nextPuzzle()
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    private fun prevPuzzle() {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
        engine?.queueMessageFromActivity(GameEngine.Message("PrevPuzzle"), ::queueMessage)
    }

    private fun nextPuzzle() {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
        engine?.queueMessageFromActivity(GameEngine.Message("NextPuzzle"), ::queueMessage)
    }

    private fun confirmExitApp() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Exit")
        builder.setMessage("Are you sure you want to exit?")
        builder.setPositiveButton("Exit") { _, _ ->
            exitApp()
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    private fun exitApp() {
        stopGameServer()
        finishAndRemoveTask()
    }

    private fun stopGameServer() {
        Log.d(TAG, "Stopping the game server ...")
        engine?.queueMessageFromActivity(GameEngine.Message("StopGame"), ::queueMessage)
    }

    fun onClickGotoSettings(view: View) {
        Log.d(TAG, "onClickGotoSettings")
        engine?.gotoSettingsActivity(this)
    }

    /**
     * Receive messages from the GameEngine.
     */
    private val activityMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Received new message ...")

            val messageString = intent.getStringExtra("Message") ?: return
            Log.d(TAG, "Message: $messageString")

            val message = GameEngine.Message.decodeMessage(messageString)
            if (message.type == "State") {
                val newState = engine?.decodeState(message)
                if (newState is KakuroGameplayDefinition.StateVariables) {
                    displayGrid(newState)
                }
            }
        }
    }

    private var queuedMessageAction: String = "$TAG.activity.MESSAGE"

    private fun enableQueuedMessages() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(queuedMessageAction)
        registerReceiver(activityMessageReceiver, intentFilter)
        Log.d(TAG, "Enabled message receiver for [${queuedMessageAction}]")
    }

    /**
     * This is the CALLBACK function to be used when a message needs to be queued for this Activity.
     */
    private fun queueMessage(message: GameEngine.Message) {
        // The UI thread will call activityMessageReceiver() to handle the message.
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("Message", message.asString())
        sendBroadcast(intent)
    }

    private fun debugMessageHandler(text: String) {
        findViewById<TextView>(R.id.textViewDebug).text = text
    }

    companion object {
        private val TAG = KakuroGameplayActivity_Tv::class.java.simpleName

        var singleton: KakuroGameplayActivity_Tv? = null

        /**
         * This function is used to display debug messages on the screen.
         */
        fun displayDebugMessage(text: String) {
            singleton?.debugMessageHandler(text)
        }
    }
}