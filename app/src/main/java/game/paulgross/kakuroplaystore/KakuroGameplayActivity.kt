package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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

            setupTvNav()
        } else {
            Log.d(TAG, "THIS NOT AN ANDROID TV")
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                setContentView(R.layout.activity_kakurogameplay)
            } else {
                setContentView(R.layout.activity_kakurogameplay_landscape)
            }
        }


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

//    https://developer.android.com/training/game-controllers/controller-input
//    https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input

    // The current control selected by the D-pad
    var currSelectedView: View? = null

    // Backgrounds used to show where the D-pad cursor is located.
    private var digitBackground_Selected: MaterialShapeDrawable? = null
    private var digitBackground_NotSelected: Drawable? = null
    private var generalBackgroundSelected: Drawable? = null

    private var gridView: PlayingGridView? = null // FIXME - this is a duplicate declaration....
    private var digit1View: View? = null
    private var digit2View: View? = null
    private var digit3View: View? = null
    private var digit4View: View? = null
    private var digit5View: View? = null
    private var digit6View: View? = null
    private var digit7View: View? = null
    private var digit8View: View? = null
    private var digit9View: View? = null
    private var digitClearView: View? = null

    private var scrollUpView: View? = null

    private data class NavCmd(
        val view: View,
        val direction: NavDirection
    )

    enum class NavDirection{
        CURSOR_UP, CURSOR_DOWN, CURSOR_LEFT, CURSOR_RIGHT
    }

    private var dpadNavLookup: MutableMap<NavCmd, View> = mutableMapOf()

    private fun setupTvNav() {
        // TODO: make strokeWidth relative. How to get the whole screen width???
        val strokeWidth = 4.0f
        digitBackground_Selected = MaterialShapeDrawable()
        (digitBackground_Selected as MaterialShapeDrawable).fillColor = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
        (digitBackground_Selected as MaterialShapeDrawable).setStroke(strokeWidth, ContextCompat.getColor(this, R.color.white))
        // TODO: Use digitBackground_Selected for possibles too

        generalBackgroundSelected = MaterialShapeDrawable()
        (generalBackgroundSelected as MaterialShapeDrawable).setStroke(strokeWidth, ContextCompat.getColor(this, R.color.white))

        gridView = findViewById(R.id.viewPlayGrid)
        gridView!!.setIndexToDefault()
        currSelectedView = gridView

        digit1View = findViewById<TextView>(R.id.textViewDigit1)
        digit2View = findViewById<TextView>(R.id.textViewDigit2)
        digit3View = findViewById<TextView>(R.id.textViewDigit3)
        digit4View = findViewById<TextView>(R.id.textViewDigit4)
        digit5View = findViewById<TextView>(R.id.textViewDigit5)
        digit6View = findViewById<TextView>(R.id.textViewDigit6)
        digit7View = findViewById<TextView>(R.id.textViewDigit7)
        digit8View = findViewById<TextView>(R.id.textViewDigit8)
        digit9View = findViewById<TextView>(R.id.textViewDigit9)
        digitClearView = findViewById<TextView>(R.id.textViewDigitClear)

        // TODO - add possibles views

        scrollUpView = findViewById(R.id.imageButtonScrollUp)

        digitBackground_NotSelected = (digit1View as TextView?)?.background

        // TODO: All the nav events for possibles and game controls.
        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_UP)] = gridView!!
        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_LEFT)] = gridView!!
        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_RIGHT)] = digit2View!!
        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_DOWN)] = digit4View!!

        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_UP)] = gridView!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_LEFT)] = digit1View!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_RIGHT)] = digit3View!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_DOWN)] = digit5View!!

        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_UP)] = gridView!!
        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_LEFT)] = digit2View!!
        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_DOWN)] = digit6View!!

        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_UP)] = digit1View!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_LEFT)] = gridView!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_RIGHT)] = digit5View!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_DOWN)] = digit7View!!

        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_UP)] = digit2View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_LEFT)] = digit4View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_RIGHT)] = digit6View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_DOWN)] = digit8View!!

        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_LEFT)] = digit5View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_UP)] = digit3View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_DOWN)] = digit9View!!

        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_UP)] = digit4View!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_LEFT)] = gridView!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_RIGHT)] = digit8View!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_UP)] = digit5View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_LEFT)] = digit7View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_RIGHT)] = digit9View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_UP)] = digit6View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_LEFT)] = digit8View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_UP)] = digit8View!!
    }

    private fun getDpadNavTarget(view: View, dir: NavDirection): View? {
        var targetView = dpadNavLookup[NavCmd(view, dir)]

        return targetView
    }

    private fun getNavDirection(event: KeyEvent): NavDirection? {
        // TODO: Aggregate all scrollable keycodes into a scroll action, including joysticks.

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return NavDirection.CURSOR_UP
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return NavDirection.CURSOR_DOWN
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return NavDirection.CURSOR_LEFT
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return NavDirection.CURSOR_RIGHT
            }
        }
        return null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
//        Log.d(TAG, "KeyEvent: $event")

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            confirmExitApp()
            return true
        }

        if (currSelectedView == null) {
            currSelectedView = gridView
        }

//        val grid = findViewById<PlayingGridView>(R.id.viewPlayGrid)

        // Rules: Cursoring or selecting without a selectedID will set the selected ID to the top left visible play square
        // TODO: Need a flag for mode for cursoring in the Controls Area. (this is currSelectedView != gridView)
        // controlCursorActiveView - a pointer to the active view
        // If controlCursorActiveView is not null, then select will put the cursor into playing grid.

        // TODO - until the D-pad buttons are used, there is no cursor.
        // TODO - The cursor starts at the default location in the grid at the first press of the D-pad buttons.

        // TODO - playing grid needs a square added to the active square that vanishes when in the controls area.

        // TODO - cursor up will return to grid, also cursor left from digits will return to grid.


        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
            if (currSelectedView == gridView) {
                currSelectedView = digit5View
                digit5View!!.background = digitBackground_Selected

                // TESTONLy - DELETEME:
                scrollUpView!!.background = generalBackgroundSelected
            } else {
                var index = gridView!!.getSelectedIndex()
                if (index == -1) {
                    gridView!!.setIndexToDefault()
                    index = gridView!!.getSelectedIndex()
                }
//                Log.d(TAG, "TODO: react to the chosen control...")
                // Can I just call the view's onClick already set by the layout here???
                if (currSelectedView != null) {
//                    onClickDigit(currSelectedView!!)
                    currSelectedView!!.callOnClick()
                }
            }
        }

        val cursorDir = getNavDirection(event)
        if (cursorDir != null) {
            if (currSelectedView != gridView) {
                val targetView = getDpadNavTarget(currSelectedView!!, cursorDir)

                if (targetView != null) {
                    unselectByNav(currSelectedView)
                    currSelectedView = targetView
                    selectByNav(currSelectedView)
                }
            } else {
                // TODO: Cursor around the grid
                Log.d(TAG, "Cursoring not yet implemented...")
            }
        }

        return true
    }

    private fun selectByNav(view: View?) {
        if (view == null) return

        if (view is TextView) {
            view.background = digitBackground_Selected
        }
    }

    private fun unselectByNav(view: View?) {
        if (view == null) return

        if (view is TextView) {
            view.background = digitBackground_NotSelected
        }
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