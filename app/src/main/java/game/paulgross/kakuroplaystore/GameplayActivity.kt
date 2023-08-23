package game.paulgross.kakuroplaystore

//import android.R
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


class GameplayActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)

        // Attach the custom TouchListener to the custom PlayingGridView
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setActivity(this)
        viewPlayGrid.setOnTouchListener(PlayingGridView.CustomListener(this, viewPlayGrid))

        instance = this  // Enables the Companion object to receive messages for this Class.

        enableQueuedMessages()
//        GameServer.activate(applicationContext, getPreferences(MODE_PRIVATE), GameplayDefinition)
        GameServer.activate(applicationContext.getSystemService(ConnectivityManager::class.java), getPreferences(MODE_PRIVATE), GameplayDefinition)

        // Request that the GameServer call queueMessage() whenever the game state changes.
        GameServer.queueActivityMessage(GameServer.Message("RequestStateChanges"), ::queueMessage)
    }

    override fun onBackPressed() {
        confirmExitApp()
    }

    /**
     * Update the custom playGridView with the state and request a redraw.
     */
    private fun displayGrid(gameState: GameplayDefinition.StateVariables) {

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.gameState = gameState
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

        // Adjust these for scrolling around large puzzles
        private var firstDisplayRow = 1
        private var firstDisplayCol = 1 // FIXME - Doesn't work past 1.
        private var maxDisplayRows = 5  // FIXME - Doesn't work when smaller than puzzle width.
        private var maxDisplayCols = 5

        var gameState: GameplayDefinition.StateVariables? = null

        private val paperTexture: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.papertexture_02)

        private val colourNonPlaySquareInside = Color.argb(180, 40, 71, 156)
        private val colourSquareBorder = Color.argb(180, 29, 51, 112)
        private val colourGuessSquare = Color.argb(180, 188, 190, 194)
        private val colourGuessSquareSelected = Color.argb(180, 255, 255, 255)

        private var gameplayActivity: GameplayActivity? = null
        fun setActivity(gameplayActivity: GameplayActivity) {
            this.gameplayActivity = gameplayActivity
        }

        /**
         * Create a TouchArea lookup to find the index of the guess square that was touched.
         */
        data class TouchArea(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)
        var playSquareTouchLookUpId: MutableMap<TouchArea, Int> = mutableMapOf()

        class CustomListener(private val theActivity: GameplayActivity, private val view: PlayingGridView): View.OnTouchListener {
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

            // FIXME: This doesn't work for screen rotations !?!?!?
            Log.d("PlayingGridView", "Width = $measuredWidth, height = $measuredHeight")
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

            val startX = 0f

            var currX = startX
            var currY = 0f

            var rows = gameState!!.puzzleWidth + 1
            var cols = gameState!!.playerGrid.size.div(gameState!!.puzzleWidth) + 1

            if (rows > maxDisplayRows) {
                rows = maxDisplayRows
            }
            if (cols > maxDisplayCols) {
                cols = maxDisplayCols
            }

            var index = 0

            for (col in (firstDisplayCol..firstDisplayCol + maxDisplayCols - 1)) {
                for (row in (firstDisplayRow..firstDisplayRow + maxDisplayRows - 1)) {
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

                            drawGuessSquare(index, gridValue.toString(), possiblesString, selected, addTouchAreas, currX, currY, canvas, paint)
                        } else {
                            drawBlankSquare(currX, currY, canvas, paint)
                        }

                        gameState!!.playerHints.forEach { hint ->
                            if (index == hint.index) {
                                if (hint.direction == GameplayDefinition.Direction.DOWN) {
                                    drawDownHint(hint.total.toString(), currX, currY, canvas, paint)
                                } else if (hint.direction == GameplayDefinition.Direction.ACROSS) {
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
                // next index needs to be offset if we are NOT viewing from the 1, 1 position.
                // FIXME - doesn't work for puzzles larger than max rows
                index = (col - 1) * gameState!!.puzzleWidth + firstDisplayCol - 1
                currX = startX
                currY += squareWidth
            }
        }

        private fun drawGuessSquare(index : Int, content: String, possiblesString: String?, selected: Boolean,
                                    addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            if (selected) {
                paint.color = Color.WHITE
            }
            canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )

            if (content != "0") {
                // Display the player's guess.
                paint.color = Color.BLUE
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

        // TODO - convert to index=? and value=?

        if (selectedIndex != -1) {
            val gm = GameServer.Message("Guess")
            gm.setKeyString("Index", selectedIndex.toString())
            gm.setKeyString("Value", value)

            GameServer.queueActivityMessage(gm, ::queueMessage)
        }
    }

    fun onClickPossibleDigit(view: View) {
        val tag = view.tag.toString()
        val value = tag.substringAfter("Possible")
        Log.d(TAG, "Possible digit: $value")

        // TODO - convert to index=? and value=?

        if (selectedIndex != -1) {
            val gm = GameServer.Message("Possible")
            gm.setKeyString("Index", selectedIndex.toString())
            gm.setKeyString("Value", value)

            GameServer.queueActivityMessage(gm, ::queueMessage)
        }
    }

    fun onClickReset(view: View) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Restart Puzzle")
        builder.setMessage("Are you sure you want to restart?")
        builder.setPositiveButton("Reset") { _, _ ->
            selectedIndex = -1
            GameServer.queueActivityMessage(GameServer.Message("RestartPuzzle"), ::queueMessage)
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
        GameServer.queueActivityMessage(GameServer.Message("StopGame"), ::queueMessage)
    }

    private var previousStateString = ""
    var selectedIndex: Int = -1

    /**
    Receive messages from the GameServer.
     */
    private val activityMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val message = intent.getStringExtra("Message")

            // FIXME - allow other message types.
            val stateString = message?.substringAfter("MessageType=State,", "")

            if (stateString != null && previousStateString != stateString) {
                Log.d(TAG, "Got a new state string [$stateString]")
                previousStateString = stateString
                // FIXME - This Activity should NOT get the un-decoded message string.
                //  It should only get the already decoded objects. The engine should do that String conversion.
                // PLUS: The engine should avoid string conversions if the network wasn't the source of the data.
                // The engine should make a threadsafe copy of the data for the activity.
                val newState = GameServer.decodeState(stateString)
                if (newState != null) {
                    displayGrid(newState)
                }
            }
        }
    }

    private var queuedMessageAction: String? = null

    private fun enableQueuedMessages() {
        queuedMessageAction = packageName + MESSAGE_SUFFIX_NEW
        val intentFilter = IntentFilter()
        intentFilter.addAction(queuedMessageAction)
        registerReceiver(activityMessageReceiver, intentFilter)
        Log.d(TAG, "Enabled message receiver for [${queuedMessageAction}]")
    }

    private fun queueMessage(message: String) {
        // The UI thread will call activityMessageReceiver() to handle the message.
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("Message", message)
        sendBroadcast(intent)
    }

    companion object {
        private val TAG = GameplayActivity::class.java.simpleName
        val MESSAGE_SUFFIX_NEW = ".$TAG.activity.MESSAGE"

        var instance: GameplayActivity? = null // Set by onCreate()

        // Callback function attached to messages sent to other queues.
        fun queueMessage(message: String) {
            instance?.queueMessage(message)
        }
    }
}