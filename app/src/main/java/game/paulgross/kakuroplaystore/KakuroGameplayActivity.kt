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
    var versionName = "Working..."

    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        versionName = "V" + packageManager.getPackageInfo(packageName, 0).versionName
        Log.d(TAG, "Version $versionName")

        val packageInfo = applicationContext.packageManager.getPackageInfo(packageName, 0)
        Log.d(TAG, "onCreate() starting version: " + packageInfo.versionName)

        singleton = this

        val isGoogleTv = applicationContext.packageManager.hasSystemFeature("android.software.leanback_only")

        if (applicationContext.packageManager.hasSystemFeature("android.software.leanback_only")) {
            Log.d(TAG, "TV DETECTED")
            setContentView(R.layout.activity_kakurogameplay_landscape)
            setupTvNav()
        } else {
            Log.d(TAG, "THIS NOT A TV")
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                setContentView(R.layout.activity_kakurogameplay)
            } else {
                setContentView(R.layout.activity_kakurogameplay_landscape)
            }
        }
        setupTvNav()

        findViewById<TextView>(R.id.textViewVersion).text = versionName

        engine = GameEngine.activate(KakuroGameplayDefinition, this)

        // Request that the GameEngine send a state message to queueMessage() whenever the game state changes.
        enableQueuedMessages()
        engine?.queueMessageFromActivity(GameEngine.Message("RequestStateChanges"), ::queueMessage)
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
//        playGridView.setScreenSizes()  // TODO: Is there a way to avoid redundant calls to setScreenSizes()???
        // Maybe cache non-zero measuredWidth values and compare???
        // NOTE: setScreenSizes() also forces a redraw.
    }

    //
    // Controller handling: D-pad used by Google TV
    //
//  https://developer.android.com/training/tv/start/navigation
//    https://developer.android.com/training/game-controllers/controller-input
//    https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input

    // The current control selected by the D-pad
    private var currSelectedView: View? = null

    // Backgrounds used to show where the D-pad cursor is located.
    private var textViewBackgroundSelected: MaterialShapeDrawable? = null
    private var generalBackgroundSelected: Drawable? = null

    private var gridView: PlayingGridView? = null

    private var digit1View: View? = null
    private var digit2View: View? = null
    private var digit3View: View? = null
    private var digit4View: View? = null
    private var digit5View: View? = null
    private var digit6View: View? = null
    private var digit7View: View? = null
    private var digit8View: View? = null
    private var digit9View: View? = null

    private var possible1View: View? = null
    private var possible2View: View? = null
    private var possible3View: View? = null
    private var possible4View: View? = null
    private var possible5View: View? = null
    private var possible6View: View? = null
    private var possible7View: View? = null
    private var possible8View: View? = null
    private var possible9View: View? = null

    private var digitClearView: View? = null

    private var zoomInView: View? = null
    private var zoomOutView: View? = null

    private var settingsView: View? = null
    private var resetView: View? = null
    private var prevPuzzleView: View? = null
    private var nextPuzzleView: View? = null

    private data class NavCmd(
        val view: View,
        val direction: NavDirection
    )

    enum class NavDirection{
        CURSOR_UP, CURSOR_DOWN, CURSOR_LEFT, CURSOR_RIGHT
    }

    private val dpadNavLookup: MutableMap<NavCmd, View> = mutableMapOf()

    private fun setupTvNav() {
        // TODO: make strokeWidth relative. How to get the whole screen width???
        // Maybe I wait for the next full execution of the playing grid's setScreenSizes()

        val strokeWidth = 4.0f
        textViewBackgroundSelected = MaterialShapeDrawable()
        (textViewBackgroundSelected as MaterialShapeDrawable).fillColor = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
        (textViewBackgroundSelected as MaterialShapeDrawable).setStroke(strokeWidth, ContextCompat.getColor(this, R.color.white))

        generalBackgroundSelected = MaterialShapeDrawable()
        (generalBackgroundSelected as MaterialShapeDrawable).setStroke(strokeWidth, ContextCompat.getColor(this, R.color.white))

        gridView = findViewById(R.id.viewPlayGrid)

        if (gridView!!.getSelectedIndex() == -1) {
            gridView!!.setIndexToDefault()
        }
        currSelectedView = gridView
        navOnto(gridView)

        digit1View = findViewById(R.id.textViewDigit1)
        digit2View = findViewById(R.id.textViewDigit2)
        digit3View = findViewById(R.id.textViewDigit3)
        digit4View = findViewById(R.id.textViewDigit4)
        digit5View = findViewById(R.id.textViewDigit5)
        digit6View = findViewById(R.id.textViewDigit6)
        digit7View = findViewById(R.id.textViewDigit7)
        digit8View = findViewById(R.id.textViewDigit8)
        digit9View = findViewById(R.id.textViewDigit9)

        possible1View = findViewById(R.id.textViewPossible1)
        possible2View = findViewById(R.id.textViewPossible2)
        possible3View = findViewById(R.id.textViewPossible3)
        possible4View = findViewById(R.id.textViewPossible4)
        possible5View = findViewById(R.id.textViewPossible5)
        possible6View = findViewById(R.id.textViewPossible6)
        possible7View = findViewById(R.id.textViewPossible7)
        possible8View = findViewById(R.id.textViewPossible8)
        possible9View = findViewById(R.id.textViewPossible9)

        digitClearView = findViewById(R.id.textViewDigitClear)

        zoomInView = findViewById(R.id.imageButtonZoomIn)
        zoomOutView = findViewById(R.id.imageButtonZoomOut)

        settingsView = findViewById(R.id.imageButtonSettings)
        resetView = findViewById(R.id.textViewReset)
        nextPuzzleView = findViewById(R.id.textViewNextPuzzle)
        prevPuzzleView = findViewById(R.id.textViewPrevPuzzle)

//        digitBackground_NotSelected = (digit1View as TextView?)?.background  // TODO: DO I still need this???

        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_RIGHT)] = digit2View!!
        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_DOWN)] = digit4View!!

        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_LEFT)] = digit1View!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_RIGHT)] = digit3View!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_DOWN)] = digit5View!!

        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_LEFT)] = digit2View!!
        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_RIGHT)] = possible1View!!
        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_DOWN)] = digit6View!!

        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_UP)] = digit1View!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_RIGHT)] = digit5View!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_DOWN)] = digit7View!!

        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_UP)] = digit2View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_LEFT)] = digit4View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_RIGHT)] = digit6View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_DOWN)] = digit8View!!

        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_UP)] = digit3View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_LEFT)] = digit5View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_RIGHT)] = possible4View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_DOWN)] = digit9View!!

        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_UP)] = digit4View!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_RIGHT)] = digit8View!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_UP)] = digit5View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_LEFT)] = digit7View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_RIGHT)] = digit9View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_UP)] = digit6View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_LEFT)] = digit8View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_RIGHT)] = possible7View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_UP)] = digit8View!!
        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_RIGHT)] = possible7View!!
        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_DOWN)] = zoomInView!!

        dpadNavLookup[NavCmd(zoomInView!!, NavDirection.CURSOR_UP)] = digitClearView!!
        dpadNavLookup[NavCmd(zoomInView!!, NavDirection.CURSOR_RIGHT)] = zoomOutView!!
        dpadNavLookup[NavCmd(zoomInView!!, NavDirection.CURSOR_DOWN)] = resetView!!

        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_UP)] = digitClearView!!
        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_LEFT)] = zoomInView!!
        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_RIGHT)] = possible7View!!
        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_DOWN)] = prevPuzzleView!!

        dpadNavLookup[NavCmd(possible1View!!, NavDirection.CURSOR_LEFT)] = digit3View!!
        dpadNavLookup[NavCmd(possible1View!!, NavDirection.CURSOR_RIGHT)] = possible2View!!
        dpadNavLookup[NavCmd(possible1View!!, NavDirection.CURSOR_DOWN)] = possible4View!!

        dpadNavLookup[NavCmd(possible2View!!, NavDirection.CURSOR_LEFT)] = possible1View!!
        dpadNavLookup[NavCmd(possible2View!!, NavDirection.CURSOR_RIGHT)] = possible3View!!
        dpadNavLookup[NavCmd(possible2View!!, NavDirection.CURSOR_DOWN)] = possible5View!!

        dpadNavLookup[NavCmd(possible3View!!, NavDirection.CURSOR_LEFT)] = possible2View!!
        dpadNavLookup[NavCmd(possible3View!!, NavDirection.CURSOR_DOWN)] = possible6View!!

        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_UP)] = possible1View!!
        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_LEFT)] = digit6View!!
        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_RIGHT)] = possible5View!!
        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_DOWN)] = possible7View!!

        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_UP)] = possible2View!!
        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_LEFT)] = possible4View!!
        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_RIGHT)] = possible6View!!
        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_DOWN)] = possible8View!!

        dpadNavLookup[NavCmd(possible6View!!, NavDirection.CURSOR_LEFT)] = possible5View!!
        dpadNavLookup[NavCmd(possible6View!!, NavDirection.CURSOR_UP)] = possible3View!!
        dpadNavLookup[NavCmd(possible6View!!, NavDirection.CURSOR_DOWN)] = possible9View!!

        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_UP)] = possible4View!!
        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_LEFT)] = digit9View!!
        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_RIGHT)] = possible8View!!
        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_DOWN)] = prevPuzzleView!!

        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_UP)] = possible5View!!
        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_LEFT)] = possible7View!!
        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_RIGHT)] = possible9View!!
        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_DOWN)] = nextPuzzleView!!

        dpadNavLookup[NavCmd(possible9View!!, NavDirection.CURSOR_UP)] = possible6View!!
        dpadNavLookup[NavCmd(possible9View!!, NavDirection.CURSOR_LEFT)] = possible8View!!
        dpadNavLookup[NavCmd(possible9View!!, NavDirection.CURSOR_DOWN)] = nextPuzzleView!!

        dpadNavLookup[NavCmd(resetView!!, NavDirection.CURSOR_UP)] = zoomInView!!
        dpadNavLookup[NavCmd(resetView!!, NavDirection.CURSOR_RIGHT)] = prevPuzzleView!!

        dpadNavLookup[NavCmd(prevPuzzleView!!, NavDirection.CURSOR_UP)] = zoomOutView!!
        dpadNavLookup[NavCmd(prevPuzzleView!!, NavDirection.CURSOR_LEFT)] = resetView!!
        dpadNavLookup[NavCmd(prevPuzzleView!!, NavDirection.CURSOR_RIGHT)] = nextPuzzleView!!

        dpadNavLookup[NavCmd(nextPuzzleView!!, NavDirection.CURSOR_UP)] = zoomOutView!!
        dpadNavLookup[NavCmd(nextPuzzleView!!, NavDirection.CURSOR_LEFT)] = prevPuzzleView!!
        dpadNavLookup[NavCmd(nextPuzzleView!!, NavDirection.CURSOR_RIGHT)] = settingsView!!

        dpadNavLookup[NavCmd(settingsView!!, NavDirection.CURSOR_UP)] = possible9View!!
        dpadNavLookup[NavCmd(settingsView!!, NavDirection.CURSOR_LEFT)] = nextPuzzleView!!
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            // NOTE: TV apps are not permitted to "gate" the back button from the main screen.
            // The back button must always return the TV "Live" page, so exitApp() is called.
            if (currSelectedView == gridView) {
                exitApp()
            } else {
                navAwayFrom(currSelectedView)
                currSelectedView = gridView
                navOnto(gridView)
            }
            return true
        }

        if (currSelectedView == null) {
            currSelectedView = gridView
            navOnto(currSelectedView)
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
            if (currSelectedView == gridView) {
                navAwayFrom(gridView)
                currSelectedView = digit5View
                navOnto(currSelectedView)
            } else {
                val index = gridView!!.getSelectedIndex()
                if (index == -1) {
                    gridView!!.setIndexToDefault()
                }
                if (currSelectedView != null) {
                    currSelectedView!!.callOnClick()
                }
            }
        }

        val cursorDir = getNavDirection(event)
        if (cursorDir != null) {
            if (currSelectedView != gridView) {
                val targetView = dpadNavLookup[NavCmd(currSelectedView!!, cursorDir)]

                if (targetView != null) {
                    navAwayFrom(currSelectedView)
                    currSelectedView = targetView
                    navOnto(currSelectedView)
                }
            } else {
                gridView!!.navigateGrid(cursorDir)
            }
        }

        return true
    }

    /**
     * Cache for navigation highlight. The cache stores the original background
     * for when the View is no longer the navigation focus.
     */
    private val cachedBackgroundLookup: MutableMap<View, Drawable> = mutableMapOf()

    private fun navOnto(view: View?) {
        if (view == null) return

        if (view == gridView) {
            gridView!!.setDpadNavSelected()
            return
        }

        gridView!!.unsetDpadNavSelected()

        // Cache the original background so that we can set it back with navAwayFrom().
        val cachedBackground: Drawable? = cachedBackgroundLookup[view]
        if (cachedBackground == null) {
            cachedBackgroundLookup[view] = view.background
        }

        if (view is TextView) {
            view.background = textViewBackgroundSelected
        } else {
            view.background = generalBackgroundSelected
        }
    }

    private fun navAwayFrom(view: View?) {
        if (view == null) return

        if (view == gridView) {
            gridView!!.unsetDpadNavSelected()
        }

        val cachedBackground: Drawable? = cachedBackgroundLookup[view]
        if (cachedBackground != null) {
            view.background = cachedBackground
        }
    }


    //
    // User touch controls
    //

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
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetTouchAreas()
        engine?.queueMessageFromActivity(GameEngine.Message("PrevPuzzle"), ::queueMessage)
    }

    private fun nextPuzzle() {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetTouchAreas()
        engine?.queueMessageFromActivity(GameEngine.Message("NextPuzzle"), ::queueMessage)
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

    companion object {
        private val TAG = KakuroGameplayActivity::class.java.simpleName

        var singleton: KakuroGameplayActivity? = null
   }
}