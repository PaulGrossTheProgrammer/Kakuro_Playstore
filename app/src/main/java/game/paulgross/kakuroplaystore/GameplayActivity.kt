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
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue


class GameplayActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)

        // Attach the TouchListener to the custom PlayingGridView
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setActivity(this)
        viewPlayGrid.setOnTouchListener(PlayingGridView.CustomListener(this, viewPlayGrid))

        instance = this  // TODO - beware of memory leak. How to clear this?

        enableMessagesFromGameServer()
        enableQueuedMessages()
        GameServer.activate(applicationContext, getPreferences(MODE_PRIVATE))

        GameServer.queueActivityMessage("RequestStateChanges", ::queueMessage)  // Request a new State message
    }

    override fun onBackPressed() {
        confirmExitApp()
    }

    private fun displayGrid(playerGrid: MutableList<Int>, puzzleWidth:Int,
                            playerHints: MutableList<GameServer.Hint>, possibles: MutableMap<Int, String>) {

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.playerGrid = playerGrid
        playGridView.puzzleWidth = puzzleWidth
        playGridView.playerHints = playerHints
        playGridView.playerPossibles = possibles
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

        var puzzleWidth = 1

        // Adjust these for scrolling around large puzzles
        private var firstDisplayRow = 1
        private var firstDisplayCol = 1 // FIXME - Doesn't work past 1.
        private var maxDisplayRows = 5  // FIXME - Doesn't work when smaller than puzzle width.
        private var maxDisplayCols = 5

        var playerGrid: MutableList<Int> = mutableListOf()
        var playerHints: MutableList<GameServer.Hint> = mutableListOf()
        var playerPossibles: MutableMap<Int, String> = mutableMapOf()

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

            var displayRows = puzzleWidth + 1
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

            var rows = puzzleWidth + 1
            var cols = playerGrid.size.div(puzzleWidth) + 1

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
                        val gridValue = playerGrid[index]
                        // Non-playable grid value is -1, 0 means no guess yet, > 0 means a player guess
                        if (gridValue != -1) {
                            val selected = (index == gameplayActivity?.selectedId)

                            var possiblesString = playerPossibles[index]
                            if (gridValue != 0) {
                                possiblesString = null
                            }

                            drawGuessSquare(index, gridValue.toString(), possiblesString, selected, addTouchAreas, currX, currY, canvas, paint)
                        } else {
                            drawBlankSquare(currX, currY, canvas, paint)
                        }

                        playerHints.forEach { hint ->
                            if (index == hint.index) {
                                if (hint.direction == GameServer.Direction.DOWN) {
                                    drawDownHint(hint.total.toString(), currX, currY, canvas, paint)
                                } else if (hint.direction == GameServer.Direction.ACROSS) {
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
                index = (col - 1) * puzzleWidth + firstDisplayCol - 1
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
        selectedId = touchedIndex
        findViewById<PlayingGridView>(R.id.viewPlayGrid).invalidate() // Trigger a redraw
    }

    private fun touchedGuessClear() {
        selectedId = -1
        findViewById<PlayingGridView>(R.id.viewPlayGrid).invalidate() // Trigger a redraw
    }

    fun onClickDigit(view: View) {
        Log.d(TAG, "Clicked a guess: ${view.tag}")
        val tag = view.tag.toString()
        val digit = tag.substringAfter("Guess")

        if (selectedId != -1) {
            GameServer.queueActivityMessage("Guess=$selectedId,$digit", ::queueMessage)
        }
    }

    fun onClickPossibleDigit(view: View) {
        val tag = view.tag.toString()
        val digit = tag.substringAfter("Possible")
        Log.d(TAG, "Possible digit: $digit")
        if (selectedId != -1) {
            GameServer.queueActivityMessage("Possible=$selectedId,$digit", ::queueMessage)
        }
    }

    fun onClickReset(view: View) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Reset")
        builder.setMessage("Are you sure you want to reset?")
        builder.setPositiveButton("Reset") { _, _ ->
            selectedId = -1
            GameServer.queueActivityMessage("Reset", ::queueMessage)
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
        GameServer.queueActivityMessage("StopGame", ::queueMessage)
    }

    private var responseMessageAction: String? = null

    private fun enableMessagesFromGameServer() {
        responseMessageAction = packageName + MESSAGE_SUFFIX
        val intentFilter = IntentFilter()
        intentFilter.addAction(responseMessageAction)
        registerReceiver(gameMessageReceiver, intentFilter)
        Log.d(TAG, "Enabled message receiver for [${packageName + MESSAGE_SUFFIX}]")
    }

    private var previousStateString = ""
    var selectedId: Int = -1

    /**
    Receive messages from the GameServer.
     */
    private val gameMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            Log.d(TAG, "DISABLED old message receiver.")
            return

            val stateString = intent.getStringExtra("MessageType=State,")

            if (stateString != null && previousStateString != stateString) {
                Log.d(TAG, "Got a new state string [$stateString]")
                previousStateString = stateString
                val newState = GameServer.decodeState(stateString)
                if (newState != null) {
                    val puzzleWidth = newState.puzzleWidth
                    val playerGrid = newState.playerGrid
                    val hints = newState.playerHints
                    val possibles = newState.possibles

                    displayGrid(playerGrid, puzzleWidth, hints, possibles)
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

    /**
    Receive messages from the GameServer.
     */
    private val activityMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG,"START: Size of inboundMessageQueue = ${inboundMessageQueue.size}")

            val message = inboundMessageQueue.take()
            if (message != null) {
                if (message.startsWith("MessageType=State")) {

                    val stateString = message.substringAfter(",", "")

                    if (stateString != "" && previousStateString != stateString) {
                        Log.d(TAG, "Got a new state string [$stateString]")
                        previousStateString = stateString
                        val newState = GameServer.decodeState(stateString)
                        if (newState != null) {
                            val puzzleWidth = newState.puzzleWidth
                            val playerGrid = newState.playerGrid
                            val hints = newState.playerHints
                            val possibles = newState.possibles

                            displayGrid(playerGrid, puzzleWidth, hints, possibles)
                        }
                    }
                }

            }
            Log.d(TAG,"DONE: Size of inboundMessageQueue = ${inboundMessageQueue.size}")
        }

    }

    private val inboundMessageQueue: BlockingQueue<String> = LinkedBlockingQueue()

    private fun queueAndNotifyMessage(message: String) {
        Log.d(TAG, "Queuing [$message]")
        inboundMessageQueue.put(message)

        // This Intent broadcast is used to notify the UI thread of the message on the inboundMessageQueue.
        // TODO: Maybe there is another way to do this???
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("MessageQueued", true)
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent ...")
    }

    companion object {
        private val TAG = GameplayActivity::class.java.simpleName
        val MESSAGE_SUFFIX = ".$TAG.display.UPDATE"
        val MESSAGE_SUFFIX_NEW = ".$TAG.activity.MESSAGE"

        // TODO: Does this ever need to be set back to null?
        var instance: GameplayActivity? = null // Set by onCreate()


        // Callback function attached to messages.
        fun queueMessage(message: String) {
            Log.d(TAG, "Successfully called the response function with [$message]")
            instance?.queueAndNotifyMessage(message)

            // TODO - maybe go back to the old Intent notify method instead of the inboundMessageQueue...???
        }
    }
}