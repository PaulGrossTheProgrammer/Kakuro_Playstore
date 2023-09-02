package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


class KakuroGameplayActivity : AppCompatActivity() {

    var engine: GameEngine? = null

    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kakurogameplay)

        // Attach the custom TouchListener to the custom PlayingGridView
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setActivity(this)
        viewPlayGrid.setOnTouchListener(PlayingGridView.CustomListener(this, viewPlayGrid))

        enableQueuedMessages()

        engine = GameEngine.activate(
            KakuroGameplayDefinition,  // Defines the data and rules for playing the game.
            applicationContext.getSystemService(ConnectivityManager::class.java),  // Used for Internet access.
            getPreferences(MODE_PRIVATE)  // Use to save and load the game state.
        )

        // Request that the GameEngine call queueMessage() whenever the game state changes.
        engine?.queueActivityMessage(GameEngine.Message("RequestStateChanges"), ::queueMessage)
    }

    override fun onBackPressed() {
        confirmExitApp()
    }

    fun onClickSettings(view: View) {
        gotoSettings()
    }

    private fun gotoSettings() {
        val intent = Intent(this, GameEngineSettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("SelectedSetting", "") // Display the default settings menu
        startActivity(intent)
    }

    /**
     * Update the custom playGridView with the state and request a redraw.
     */
    private fun displayGrid(gameState: KakuroGameplayDefinition.StateVariables) {

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)

        playGridView.updateState(gameState)
        playGridView.invalidate() // Trigger a redraw
    }

    /**
     * The custom View to draw the playing grid.
     */
    class PlayingGridView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

        private var currViewWidth = 1
        private var currViewHeight = 1
        private var squareWidth = 1f
        private var margin = 1f
        private var squareTextSize = 1f
        private var possiblesTextSize = 1f
        private var borderThickness =1f

        private var maxDisplayRows = 10
        private var xOffsetPx = 0f
        private var yOffsetPx = 0f
        // TODO - implement a scale factor...

        private var gameState: KakuroGameplayDefinition.StateVariables? = null

        private val paperTexture: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.papertexture_02)

        private val colourNonPlaySquareInside = Color.argb(180, 40, 71, 156)
        private val colourSquareBorder = Color.argb(180, 29, 51, 112)
        private val colourGuessSquare = Color.argb(180, 188, 190, 194)
        private val colourGuessSquareSelected = Color.argb(180, 255, 255, 255)

        private var gameplayActivity: KakuroGameplayActivity? = null
        fun setActivity(gameplayActivity: KakuroGameplayActivity) {
            this.gameplayActivity = gameplayActivity
        }

        /**
         * Create a TouchArea lookup to find the index of the guess square that was touched.
         */
        data class TouchArea(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)
        var playSquareTouchLookUpId: MutableMap<TouchArea, Int> = mutableMapOf()

        class CustomListener(private val theActivity: KakuroGameplayActivity, private val view: PlayingGridView): View.OnTouchListener {
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val x = event.x
                val y = event.y

                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val touchedIndex = lookupGuessId(x, y)
                        if (touchedIndex != -1) {
                            Log.d(TAG, "Touched index: $touchedIndex")
                            theActivity.touchedGuess(touchedIndex)
                        } else {
                            theActivity.touchedGuessClear()
                        }
                    }
                }
                return true
            }

            private fun lookupGuessId(x: Float, y: Float): Int {
                for (entry in view.playSquareTouchLookUpId.entries.iterator()) {
                    if (x >= entry.key.xMin && x <= entry.key.xMax && y >= entry.key.yMin && y <= entry.key.yMax) {
                        return entry.value
                    }
                }
                return -1
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)

            setRelativeSizes()  // FIXME - why do I need this here as well as updateState() ???
        }

        fun updateState(newState: KakuroGameplayDefinition.StateVariables) {
            var updateSizes = false
            if (gameState == null) {
                updateSizes = true
            }

            // Any other conditions??? to force recalc of all relative sizes???

            gameState = newState

            if (updateSizes) {
                setRelativeSizes()
            }
        }


        private fun setRelativeSizes() {
            Log.d("PlayingGridView", "setRelativeSizes(): Width = $measuredWidth, height = $measuredHeight")
            if (gameState == null) {
                Log.d("PlayingGridView", "setRelativeSizes() exiting because gameState is null.")
                return
            }

            currViewWidth = measuredWidth
            currViewHeight = measuredHeight

            var displayRows = gameState!!.puzzleWidth + 1
            if (displayRows > maxDisplayRows) {
                displayRows = maxDisplayRows
            }

            squareWidth = (currViewWidth/displayRows).toFloat()
            Log.d("PlayingGridView", "squareWidth = $squareWidth")
            borderThickness = squareWidth * 0.06f

            squareTextSize = squareWidth * 0.7f
            possiblesTextSize = squareWidth * 0.25f

            margin = squareWidth * 0.08f


        }

        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            Log.d("PlayingGridView", "onDraw() running")
            if (gameState == null) {
                Log.d("PlayingGridView", "onDraw() exiting - No gameState to draw.")
                return
            }

            canvas.drawBitmap(paperTexture, 0f, 0f, paint) // TODO scale this to the final size

            // TODO: Only add new touch areas if the existing ones are missing or outdated.
            // This will get complicated when the user can scroll around large puzzles
            // where only part of the whole puzzle is visible at any one time.
            var addTouchAreas = false
            if (playSquareTouchLookUpId.isEmpty()) {
                addTouchAreas = true
            }

            // Draw grid
            paint.color = Color.WHITE

            val startX = xOffsetPx

            var currX = startX
            var currY = yOffsetPx

            var rows = gameState!!.puzzleWidth + 1
            var cols = gameState!!.playerGrid.size.div(gameState!!.puzzleWidth) + 1

            // Assume a square display area.
            if (rows > maxDisplayRows) {
                rows = maxDisplayRows
            }
            if (cols > maxDisplayRows) {
                cols = maxDisplayRows
            }

            var index = 0

            // TODO: Use x and y offsets to allow the player to move around large puzzles.
            for (col in (1..gameState!!.puzzleWidth + 1)) {
                for (row in (1..gameState!!.puzzleWidth + 1)) {
                    // First row and colum are only used as space for showing hints.
                    val puzzleSquare = (col != 1 && row != 1)

                    if (puzzleSquare) {
                        val gridValue = gameState!!.playerGrid[index]
                        // Non-playable grid value is -1, 0 means no guess yet, > 0 means a player guess
                        if (gridValue != -1) {
                            val selected = (index == gameplayActivity?.selectedIndex)

                            var possiblesString = gameState!!.possibles[index]
                            if (gridValue != 0) {
                                possiblesString = null
                            }

                            val error = gameState!!.playerErrors.contains(index)

                            drawGuessSquare(index, gridValue.toString(), possiblesString, selected, error, addTouchAreas, currX, currY, canvas, paint)
                        } else {
                            drawBlankSquare(currX, currY, canvas, paint)
                        }

                        gameState!!.playerHints.forEach { hint ->
                            if (index == hint.index) {
                                if (hint.direction == KakuroGameplayDefinition.Direction.DOWN) {
                                    drawDownHint(hint.total.toString(), currX, currY, canvas, paint)
                                } else if (hint.direction == KakuroGameplayDefinition.Direction.ACROSS) {
                                    drawAcrossHint(hint.total.toString(), currX, currY, canvas, paint)
                                }
                            }
                        }
                        index++
                    } else {
                        drawBlankSquare(currX, currY, canvas, paint)
                    }

                    drawSquareBorder(currX, currY, canvas, paint)

                    currX += squareWidth
                }
                index = (col - 1) * gameState!!.puzzleWidth

                // TODO - these two seem around the wrong way...
                currX = startX
                currY += squareWidth
            }
        }

        private fun drawGuessSquare(index : Int, content: String, possiblesString: String?, selected: Boolean, error: Boolean,
                                    addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            if (selected) {
                paint.color = Color.WHITE
            }
            canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )

            if (content != "0") {
                // Display the player's guess.
                paint.color = Color.BLUE
                if (error) {
                    paint.color = Color.RED
                }
                paint.textSize = squareTextSize
                canvas.drawText(content, x + squareWidth * 0.31f, y + squareWidth * 0.75f, paint)
            }

            if (possiblesString != null) {
                paint.textSize = possiblesTextSize
                paint.color = Color.BLACK

                val xStart = x + squareWidth * 0.10f
                val yStart = y + squareWidth * 0.30f
                var xPos = xStart
                var yPos = yStart
                var currIndex = 0
                for (row in 1 .. 3) {
                    for (col in 1..3) {
                        val indexPossible = possiblesString[currIndex].toString()
                        if (indexPossible != "0") {
                            canvas.drawText(indexPossible, xPos, yPos, paint)
                        }
                        xPos += squareWidth * 0.30f
                        currIndex ++
                    }
                    yPos += squareWidth * 0.30f
                    xPos = xStart
                }
            }

            if (addTouchAreas) {
                playSquareTouchLookUpId.put(TouchArea(x, y, x + squareWidth, y + squareWidth), index)
            }
        }

        private fun drawSquareBorder(x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = colourSquareBorder
            paint.strokeWidth = borderThickness
            canvas.drawLine(x, y, x + squareWidth, y, paint )
            canvas.drawLine(x, y, x, y + squareWidth, paint )
            canvas.drawLine(x, y + squareWidth, x + squareWidth, y + squareWidth, paint )
            canvas.drawLine(x + squareWidth, y, x + squareWidth, y + squareWidth, paint )
        }

        //drawBlankSquare
        private fun drawBlankSquare(x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = colourNonPlaySquareInside

            canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )
        }

        private fun drawDownHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            paint.strokeWidth = squareWidth * 0.02f

            canvas.drawLine(x + margin, y - squareWidth + margin, x + squareWidth - margin, y - margin, paint )

            paint.textSize = squareTextSize * 0.45f
            canvas.drawText(hintString, x + squareWidth * 0.18f, y + squareWidth * 0.85f - squareWidth, paint)
        }

        private fun drawAcrossHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            paint.strokeWidth = squareWidth * 0.02f

            canvas.drawLine(x + margin - squareWidth, y + margin, x - margin, y - margin + squareWidth, paint )

            paint.textSize = squareTextSize * 0.45f
            canvas.drawText(hintString, x + squareWidth * 0.56f  - squareWidth, y + squareWidth * 0.45f, paint)
        }
    }

    var selectedIndex: Int = -1

    private fun touchedGuess(touchedIndex: Int) {
        selectedIndex = touchedIndex
        findViewById<PlayingGridView>(R.id.viewPlayGrid).invalidate() // Trigger a redraw
    }

    private fun touchedGuessClear() {
        selectedIndex = -1
        findViewById<PlayingGridView>(R.id.viewPlayGrid).invalidate() // Trigger a redraw
    }

    fun onClickDigit(view: View) {
        Log.d(TAG, "Clicked a guess: ${view.tag}")
        val tag = view.tag.toString()
        val value = tag.substringAfter("Guess")

        if (selectedIndex != -1) {
            val message = GameEngine.Message("Guess")
            message.setKeyString("Index", selectedIndex.toString())
            message.setKeyString("Value", value)

            engine?.queueActivityMessage(message, ::queueMessage)
        }
    }

    fun onClickPossibleDigit(view: View) {
        val tag = view.tag.toString()
        val value = tag.substringAfter("Possible")
        Log.d(TAG, "Possible digit: $value")

        if (selectedIndex != -1) {
            val message = GameEngine.Message("Possible")
            message.setKeyString("Index", selectedIndex.toString())
            message.setKeyString("Value", value)

            engine?.queueActivityMessage(message, ::queueMessage)
        }
    }

    fun onClickReset(view: View) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Restart Puzzle")
        builder.setMessage("Are you sure you want to restart?")
        builder.setPositiveButton("Reset") { _, _ ->
            selectedIndex = -1
            engine?.queueActivityMessage(GameEngine.Message("RestartPuzzle"), ::queueMessage)
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
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
        engine?.queueActivityMessage(GameEngine.Message("StopGame"), ::queueMessage)
    }

    /**
     * Receive messages from the GameEngine.
     */
    private val activityMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val messageString = intent.getStringExtra("Message") ?: return

            val message = GameEngine.Message.decodeMessage(messageString)
            if (message.type == "State") {
                val newState = engine?.decodeState(message)
                if (newState is KakuroGameplayDefinition.StateVariables) {
                    displayGrid(newState)
                }
            }
        }
    }

    private var queuedMessageAction: String = "$packageName.$TAG.activity.MESSAGE"

    private fun enableQueuedMessages() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(queuedMessageAction)
        registerReceiver(activityMessageReceiver, intentFilter)
        Log.d(TAG, "Enabled message receiver for [${queuedMessageAction}]")
    }

    /**
     * This is the CALLBACK function to be used when a message needs to be queued for this Activity.
     */
    private fun queueMessage(message: String) {
        // The UI thread will call activityMessageReceiver() to handle the message.
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("Message", message)
        sendBroadcast(intent)
    }

    companion object {
        private val TAG = KakuroGameplayActivity::class.java.simpleName
   }
}