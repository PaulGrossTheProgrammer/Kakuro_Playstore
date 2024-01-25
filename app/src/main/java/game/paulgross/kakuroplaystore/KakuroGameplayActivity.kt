package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.shape.MaterialShapeDrawable


class KakuroGameplayActivity : AppCompatActivity() {

    var engine: GameEngine? = null

    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageInfo = applicationContext.packageManager.getPackageInfo(packageName, 0)
        Log.d(TAG, "onCreate() starting version: " + packageInfo.versionName)

        singleton = this

        // Test code to see what available features there are for each system:
//        applicationContext.packageManager.systemAvailableFeatures.forEach {
//            Log.d(TAG, "Feature: ${it}")
//        }

        if (applicationContext.packageManager.hasSystemFeature("android.software.leanback_only")) {
            Log.d(TAG, "THIS AN ANDROID TV")
            setContentView(R.layout.activity_kakurogameplay_landscape)
        } else {
            Log.d(TAG, "THIS NOT AN ANDROID TV")
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                setContentView(R.layout.activity_kakurogameplay)
            } else {
                setContentView(R.layout.activity_kakurogameplay_landscape)
            }
        }

        // Setup the D-pad cursoring visuals
        digitBackground_NotSelected = findViewById<TextView>(R.id.textViewButton1).background
        currSelectedView = findViewById<TextView>(R.id.textViewButton1)

        engine = GameEngine.activate(KakuroGameplayDefinition, this)

        // Request that the GameEngine send a state message to queueMessage() whenever the game state changes.
        enableQueuedMessages()
        engine?.queueMessageFromActivity(GameEngine.Message("RequestStateChanges"), ::queueMessage)
    }

//    override fun onBackPressed() {
//        confirmExitApp()
//    }  // Note: Replaced by code in override fun dispatchKeyEvent()

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
    // Controller handling: D-pad used by Google TV
    //


    // The current control selected by the D-pad
    var currSelectedView: View? = null

    // Backgrounds used to show where the D-pad cursor is located.
    var digitBackground_Selected: Drawable? = null
    var digitBackground_NotSelected: Drawable? = null
//    init {
//        digitBackground_Selected = MaterialShapeDrawable()
//        (digitBackground_Selected as MaterialShapeDrawable).fillColor =
//            ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
//        (digitBackground_Selected as MaterialShapeDrawable).setStroke(10.0f, ContextCompat.getColor(this, R.color.white))
//    }

//    https://developer.android.com/training/game-controllers/controller-input
//    https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input

    /*
    Possible way to show the currently selected View:
    https://stackoverflow.com/questions/3496269/how-to-put-a-border-around-an-android-textview
    you can programmatically apply a MaterialShapeDrawable:

    TextView textView = findViewById(R.id.textview);
    MaterialShapeDrawable shapeDrawable = new MaterialShapeDrawable();
    shapeDrawable.setFillColor(ContextCompat.getColorStateList(this,android.R.color.transparent));
    shapeDrawable.setStroke(1.0f, ContextCompat.getColor(this,R.color....));
    ViewCompat.setBackground(textView,shapeDrawable);

     */

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "KeyEvent: $event")

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            confirmExitApp()
            return true
        }

        val grid =  findViewById<PlayingGridView>(R.id.viewPlayGrid)

        // Rules: Cursoring or selecting without a selectedID will set the selected ID to the top left visible play square
        // TODO: Need a flag for mode for cursoring in the Controls Area.
        // controlCursorActiveView - a pointer to the active view
        // If controlCursorActiveView is not null, then select will put the cursor into playing grid.\
        // TODO: User needs a visible indication that the cursor is active in the Controls Area.

        // FIXME: This doesn't work
        val selectedIndex = grid.getSelectedIndex()
        Log.d(TAG, "selectedIndex BEFORE: $selectedIndex")
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
            if (currSelectedView == null) {
                Log.d(TAG, "Setting default selectedIndex...")
                grid.setIndexToDefault()

                findViewById<TextView>(R.id.textViewButton1).background = digitBackground_NotSelected
            } else {
                currSelectedView = findViewById<PlayingGridView>(R.id.textViewButton1)
                findViewById<TextView>(R.id.textViewButton1).background = digitBackground_Selected
            }

        }
        Log.d(TAG, "selectedIndex AFTER: ${grid.getSelectedIndex()}")

        return true
    }

    //
    // User touch controls
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

        // TODO: TEST ONLY
        // Try to indicate a  TextView is selected here:

/*        Log.d(TAG, "View background: ${view.background}")

        if (digitBackground_NotSelected == null) {
            digitBackground_NotSelected = view.background
        }

        if (view.background == digitBackground_NotSelected) {
            val shapeDrawable: MaterialShapeDrawable = MaterialShapeDrawable()
            shapeDrawable.fillColor = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
            shapeDrawable.setStroke(10.0f, ContextCompat.getColor(this, R.color.white))
            view.background = shapeDrawable
        } else {
            view.background = digitBackground_NotSelected
        }*/

//        val textView: TextView = findViewById(R.id.textViewButton1)
//        val shapeDrawable: MaterialShapeDrawable = MaterialShapeDrawable()
//        shapeDrawable.fillColor = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
//        shapeDrawable.fillColor = android.R.color.transparent
        // make strokeWidth relative
//        shapeDrawable.setStroke(10.0f, ContextCompat.getColor(this, R.color.white))
//        view.background = shapeDrawable
//        view.isSelected = true
//        val newBg = ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright)
//        view.background = newBg

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
        // For this CALLBACK to be used, enableQueuedMessages() must be executed at startup.
        // The UI thread will then call activityMessageReceiver() to handle each message.
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("Message", message.asString())
        sendBroadcast(intent)
    }

    private fun debugMessageHandler(text: String) {
        findViewById<TextView>(R.id.textViewDebug).text = text
    }

    companion object {
        private val TAG = KakuroGameplayActivity::class.java.simpleName

        var singleton: KakuroGameplayActivity? = null

        /**
         * This function is used to display debug messages on the screen.
         */
        fun displayDebugMessage(text: String) {
            singleton?.debugMessageHandler(text)
        }
   }
}