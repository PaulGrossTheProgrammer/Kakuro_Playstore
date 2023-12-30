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
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


class KakuroGameplayActivity : AppCompatActivity() {

    var engine: GameEngine? = null


    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        singleton = this
        setContentView(R.layout.activity_kakurogameplay)

        // Attach the custom TouchListener to the custom PlayingGridView
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setOnTouchListener(PlayingGridView.CustomListener(this, viewPlayGrid))

        enableQueuedMessages()

        engine = GameEngine.activate(KakuroGameplayDefinition, applicationContext, this)

        // Request that the GameEngine call queueMessage() whenever the game state changes.
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
     * Update the custom playGridView with the state and request a redraw.
     */
    private fun displayGrid(newestGameState: KakuroGameplayDefinition.StateVariables) {
        gameState = newestGameState

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.setScreenSizes()
        playGridView.invalidate()
    }

    /**
     * The custom View to draw the playing grid.
     */
    class PlayingGridView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

        private val outsideGridMargin = 0.4f
        private val maxDisplayRows = 10
        private val minDisplayRows = 5

        private var currViewWidth = 1
        private var currViewHeight = 1
        private var squareWidth = 1f
        private var slashMargin = 1f
        private var squareTextSize = 1f
        private var possiblesTextSize = 1f
        private var borderThickness =1f

        private var currDisplayRows = maxDisplayRows
        private var displayZoom = 0
        private var xSquaresOffset = 0
        private var ySquaresOffset = 0

        private var selectedIndex: Int = -1

        private val paperTexture: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.papertexture_02)
        // TODO - scale the bitmap...

        private val colourNonPlaySquareInside = Color.argb(180, 40, 71, 156)
        private val colourSquareBorder = Color.argb(180, 29, 51, 112)
        private val colourGuessSquare = Color.argb(180, 188, 190, 194)
        private val colourGuessSquareSelected = Color.argb(180, 255, 255, 255)

        private lateinit var gameplayActivity: KakuroGameplayActivity

        init {
            if (context is KakuroGameplayActivity) {
                gameplayActivity = context
            }
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
                        val touchedIndex = lookupTouchedGuessId(x, y)
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

            private fun lookupTouchedGuessId(x: Float, y: Float): Int {
                for (entry in view.playSquareTouchLookUpId.entries.iterator()) {
                    if (x >= entry.key.xMin && x <= entry.key.xMax && y >= entry.key.yMin && y <= entry.key.yMax) {
                        return entry.value
                    }
                }
                return -1
            }
        }

        fun setSelectedIndex(index: Int) {
            selectedIndex = index
            invalidate()  // Force the grid to be redrawn
        }
        fun getSelectedIndex(): Int {
            return selectedIndex
        }

        fun zoomGrid(changeZoom: Int) {
            if (currDisplayRows + changeZoom < minDisplayRows || currDisplayRows + changeZoom > maxDisplayRows) {
                return
            }
            displayZoom += changeZoom
            setScreenSizes()
            invalidate()  // Force the grid to be redrawn
        }

        fun scrollGrid(xDeltaSquares: Int, yDeltaSquares: Int) {
            displayDebugMessage("xSO = $xSquaresOffset, xDS = $xDeltaSquares, currDR = $currDisplayRows")
            if (xSquaresOffset + xDeltaSquares > 0) {
                return
            }
            if (ySquaresOffset + yDeltaSquares > 0) {
                return
            }
            // FIXME - right and bottom limits don't work.
/*            if (xSquaresOffset + xDeltaSquares < currDisplayRows) {
                return
            }
            if (ySquaresOffset + yDeltaSquares < currDisplayRows) {
                return
            }*/

            xSquaresOffset += xDeltaSquares
            ySquaresOffset += yDeltaSquares
            playSquareTouchLookUpId.clear()

            invalidate()  // Force the grid to be redrawn
        }

        fun setScreenSizes() {
            Log.d("PlayingGridView", "setScreenSizes(): Width = $measuredWidth, height = $measuredHeight")
            if (gameplayActivity?.gameState == null) {
                Log.d("PlayingGridView", "setScreenSizes() exiting because gameState is null.")
                return
            }

            playSquareTouchLookUpId.clear()

            currViewWidth = measuredWidth
            currViewHeight = measuredHeight

            currDisplayRows = gameplayActivity.gameState!!.puzzleWidth + 1 + displayZoom
            if (currDisplayRows > maxDisplayRows) {
                currDisplayRows = maxDisplayRows
            }
            if (currDisplayRows < minDisplayRows) {
                currDisplayRows = minDisplayRows
            }


            squareWidth = (currViewWidth/(currDisplayRows + outsideGridMargin)).toFloat()
            Log.d("PlayingGridView", "squareWidth = $squareWidth")
            borderThickness = squareWidth * 0.06f

            squareTextSize = squareWidth * 0.7f
            possiblesTextSize = squareWidth * 0.25f

            slashMargin = squareWidth * 0.08f

            invalidate()  // Force a redraw
        }

        fun resetOptions() {
            displayZoom = 0
            selectedIndex = -1
        }

        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            Log.d("PlayingGridView", "onDraw() running")
            if (gameplayActivity.gameState == null) {
                Log.d("PlayingGridView", "onDraw() exiting - No gameState to draw.")
                return
            }

            canvas.drawBitmap(paperTexture, 0f, 0f, paint) // TODO scale this to the final size

            // Add the touch areas if there are none.
            var addTouchAreas = false
            if (playSquareTouchLookUpId.isEmpty()) {
                addTouchAreas = true
            }

            // Draw grid
            paint.color = Color.WHITE

            val xStart = (squareWidth * outsideGridMargin)/2 + xSquaresOffset * squareWidth
            val yStart = (squareWidth * outsideGridMargin)/2 + ySquaresOffset * squareWidth

            var currX = xStart
            var currY = yStart

            var rows = gameplayActivity.gameState!!.puzzleWidth + 1
            var cols = gameplayActivity.gameState!!.playerGrid.size.div(gameplayActivity.gameState!!.puzzleWidth) + 1

            // Assume a square display area.
            if (rows > maxDisplayRows) {
                rows = maxDisplayRows
            }
            if (cols > maxDisplayRows) {
                cols = maxDisplayRows
            }

            var index = 0

            for (col in (1..gameplayActivity.gameState!!.puzzleWidth + 1)) {
                for (row in (1..gameplayActivity.gameState!!.puzzleWidth + 1)) {
                    // First row and colum are only used as space for showing hints.
                    val puzzleSquare = (col != 1 && row != 1)

                    if (puzzleSquare) {
                        val gridValue = gameplayActivity.gameState!!.playerGrid[index]
                        // Non-playable grid value is -1, 0 means no guess yet, > 0 means a player guess
                        if (gridValue != -1) {
                            val selected = (index == selectedIndex)

                            var possiblesString = gameplayActivity.gameState!!.possibles[index]
                            if (gridValue != 0) {
                                possiblesString = null
                            }

                            val error = gameplayActivity.gameState!!.playerErrors.contains(index)

                            drawGuessSquare(index, gridValue.toString(), possiblesString, selected, error, addTouchAreas, currX, currY, canvas, paint)
                        } else {
                            drawBlankSquare(currX, currY, canvas, paint)
                        }

                        gameplayActivity.gameState!!.playerHints.forEach { hint ->
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
                index = (col - 1) * gameplayActivity.gameState!!.puzzleWidth

                currX = xStart
                currY += squareWidth
            }
        }

        private fun drawGuessSquare(index : Int, content: String, possiblesString: String?, selected: Boolean, error: Boolean,
                                    addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            // Determine if this square is within the visible area.
            var visible = true
            if (x < 0 || y < 0 || x > measuredWidth || y > measuredHeight) {
                visible = false
            }

            // Clear selectedIndex if the square is not visible.
            if (selectedIndex == index && !visible) {
                selectedIndex = -1
            }

            if (!visible) {
                return
            }

            if (addTouchAreas) {
                playSquareTouchLookUpId.put(TouchArea(x, y, x + squareWidth, y + squareWidth), index)
            }

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
        }

        private fun drawSquareBorder(x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = colourSquareBorder
            paint.strokeWidth = borderThickness
            canvas.drawLine(x, y, x + squareWidth, y, paint )
            canvas.drawLine(x, y, x, y + squareWidth, paint )
            canvas.drawLine(x, y + squareWidth, x + squareWidth, y + squareWidth, paint )
            canvas.drawLine(x + squareWidth, y, x + squareWidth, y + squareWidth, paint )
        }

        private fun drawBlankSquare(x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = colourNonPlaySquareInside

            canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )
        }

        private fun drawDownHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            paint.strokeWidth = squareWidth * 0.02f

            canvas.drawLine(x + slashMargin, y - squareWidth + slashMargin, x + squareWidth - slashMargin, y - slashMargin, paint )

            paint.textSize = squareTextSize * 0.45f
            canvas.drawText(hintString, x + squareWidth * 0.18f, y + squareWidth * 0.85f - squareWidth, paint)
        }

        private fun drawAcrossHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            paint.strokeWidth = squareWidth * 0.02f

            canvas.drawLine(x + slashMargin - squareWidth, y + slashMargin, x - slashMargin, y - slashMargin + squareWidth, paint )

            paint.textSize = squareTextSize * 0.45f
            canvas.drawText(hintString, x + squareWidth * 0.56f  - squareWidth, y + squareWidth * 0.45f, paint)
        }
    }


    // User interface controls

    private fun touchedGuess(touchedIndex: Int) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).setSelectedIndex(touchedIndex)
    }

    private fun touchedGuessClear() {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).setSelectedIndex(-1)
    }

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


    // Game commands

    fun onClickDigit(view: View) {
        Log.d(TAG, "Clicked a guess: ${view.tag}")

        val selectedIndex = findViewById<PlayingGridView>(R.id.viewPlayGrid).getSelectedIndex()
        if (selectedIndex == -1) {
            return
        }

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
            findViewById<PlayingGridView>(R.id.viewPlayGrid).setSelectedIndex(-1)
            engine?.queueMessageFromActivity(GameEngine.Message("RestartPuzzle"), ::queueMessage)
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    fun onClickPrevPuzzle(view: View) {
        Log.d(TAG, "Clicked Puzzle 2")
        engine?.queueMessageFromActivity(GameEngine.Message("NewPuzzle1"), ::queueMessage)
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
    }
    fun onClickNextPuzzle(view: View) {
        Log.d(TAG, "Clicked Puzzle 2")
        engine?.queueMessageFromActivity(GameEngine.Message("NewPuzzle2"), ::queueMessage)
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
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
//            if (message.type == "Debug") {
//                Log.d(TAG, "ACTIVITY received a debug message...")
//                val msg = message.getString("Message")
//                (findViewById<PlayingGridView>(R.id.textViewDebug) as TextView).text = msg
//            }
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