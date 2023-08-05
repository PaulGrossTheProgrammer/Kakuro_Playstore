package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Color.rgb
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


class GameplayActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)

        // Attach the TouchListener to the custom PlayingGridView
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setActivity(this)
        viewPlayGrid.setOnTouchListener(PlayingGridView.CustomListener(this, viewPlayGrid))

        enableMessagesFromGameServer()
        GameServer.activate(applicationContext, getPreferences(MODE_PRIVATE))

        GameServer.queueActivityMessage("Status")
    }

    public override fun onBackPressed() {
        confirmExitApp()
    }

    private fun displayGrid(playerGrid: MutableList<Int>, puzzleWidth:Int, playerHints: MutableList<GameServer.Hint>) {

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.playerGrid = playerGrid
        playGridView.puzzleWidth = puzzleWidth
        playGridView.playerHints = playerHints
        playGridView.invalidate() // Trigger a redraw
    }

    /**
     * The custom View to draw the playing grid
     */
    class PlayingGridView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

        var currViewWidth = 1
        var currViewHeight = 1
        var squareWidth = 1f
        var margin = 1f
        var squareTextSize = 1f

        var puzzleWidth = 1

        // Adjust these for scrolling around large puzzles
        var firstDisplayRow = 1
        var firstDisplayCol = 1
        var maxDisplayRows = 5 // FIXME - doesn't work if less than puzzle width...
        var maxDisplayCols = 5

        var playerGrid: MutableList<Int> = mutableListOf()
        var playerHints: MutableList<GameServer.Hint> = mutableListOf()

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
            Log.d("PlayingGridView", "Width = $measuredWidth, height = $measuredHeight")
            currViewWidth = measuredWidth
            currViewHeight = measuredHeight

            var displayRows = maxDisplayRows
            if (puzzleWidth < displayRows) {
                displayRows = puzzleWidth
            }

            squareWidth = currViewWidth/(displayRows + 2f)
            Log.d("PlayingGridView", "squareWidth = $squareWidth")

            margin = squareWidth * 0.05f
            squareTextSize = squareWidth * 0.7f
        }

        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            Log.d("PlayingGridView", "onDraw() running")

            // Paint the canvas background
//            paint.color = Color.CYAN
            paint.color = Color.rgb(137,196,199)
            canvas.drawPaint(paint)
//            rgb(int,int,int)

            // TODO: Only add new touch areas if the existing ones are missing or outdated.
            // This will get complicated when the user can scroll around large puzzles
            // where only part of the whole puzzle is visible at any one time.
            var addTouchAreas = false
            if (playSquareTouchLookUpId.isEmpty()) {
                addTouchAreas = true
            }

            // Draw grid
            paint.color = Color.WHITE

            val startX = squareWidth/2f

            var currX = startX
            var currY = squareWidth/2f

            var rows = puzzleWidth + 1
            var cols = playerGrid.size.div(puzzleWidth) + 1

            if (rows > maxDisplayRows) {
                rows = maxDisplayRows
            }
            if (cols > maxDisplayCols) {
                cols = maxDisplayCols
            }

            var index = 0

            // TODO - probably don't need this ....
            var drawnHints: MutableList<GameServer.Hint> = mutableListOf() // TODO - decide if we still need this...?

            for (col in (firstDisplayCol..cols)) {

                for (row in (firstDisplayRow..rows)) {
                    // First row and colum are only used as space for showing hints.
                    val puzzleSquare = (col != 1 && row != 1)

                    if (puzzleSquare) {
                        val gridValue = playerGrid[index]
                        // Non-playable grid value is 0, -1 means no guess yet, > 0 means a player guess
                        if (gridValue != 0) {
                            val selected = (index == gameplayActivity?.selectedId)
                            drawGuessSquare(index, gridValue.toString(), selected, addTouchAreas, currX, currY, canvas, paint)
                        } else {
                            drawBlankSquare(currX, currY, canvas, paint)
                        }

                        playerHints.forEach { hint ->
                            if (!drawnHints.contains(hint) && index == hint.index) {
                                drawnHints.add(hint)
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



                    currX += squareWidth
                }
                currX = startX
                currY += squareWidth
            }
        }

        private fun drawGuessSquare(index : Int, content: String, selected: Boolean, addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            if (selected) {
                paint.color = Color.WHITE
            }
            canvas.drawRect(x + margin, y + margin,
                x + squareWidth - margin, y + squareWidth - margin, paint )

            if (content != "-1") {
                // Display the player's guess.
                paint.color = Color.RED
                paint.setTextSize(squareTextSize)
                canvas.drawText(content, x + squareWidth * 0.31f, y + squareWidth * 0.75f, paint)
            }

            if (addTouchAreas) {
                playSquareTouchLookUpId.put(TouchArea(x, y, x + squareWidth, y + squareWidth), index)
            }
        }

        //drawBlankSquare
        private fun drawBlankSquare(x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.GRAY

            canvas.drawRect(x + margin, y + margin,
                x + squareWidth - margin, y + squareWidth - margin, paint )
        }

        private fun drawDownHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.DKGRAY
            paint.setStrokeWidth(3f)  // TODO: Make proportional....

            canvas.drawLine(x + margin, y + margin - squareWidth, x + squareWidth - margin, y - margin, paint )
//            canvas.drawLine(x + margin, y + margin - squareWidth, x + margin, y - margin, paint )
//            canvas.drawLine(x + margin, y - margin, x + squareWidth - margin, y - margin, paint )

            paint.color = Color.BLACK
            paint.setTextSize(squareTextSize * 0.45f)
            canvas.drawText(hintString, x + squareWidth * 0.18f, y + squareWidth * 0.85f - squareWidth, paint)
        }

        private fun drawAcrossHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.DKGRAY
            paint.setStrokeWidth(3f)

            canvas.drawLine(x + margin - squareWidth, y + margin, x - margin, y - margin + squareWidth, paint )
//           canvas.drawLine(x + margin - squareWidth, y + margin, x - margin, y + margin, paint )
//            canvas.drawLine(x - margin, y + margin,x - margin, y - margin + squareWidth , paint )

            paint.color = Color.BLACK
            paint.setTextSize(squareTextSize * 0.45f)
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
            GameServer.queueActivityMessage("Guess=$selectedId,$digit")
        }
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
        GameServer.queueActivityMessage("StopGame")
    }

    private fun enableMessagesFromGameServer() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(packageName + MESSAGE_SUFFIX)
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

            val stateString = intent.getStringExtra("State")

            if (stateString != null && previousStateString != stateString) {
                Log.d(TAG, "Got a new state string [$stateString]")
                previousStateString = stateString
                val newState = GameServer.decodeState(stateString)
                if (newState != null) {
                    var puzzleWidth = newState.puzzleWidth
                    var playerGrid = newState.playerGrid
                    var hints = newState.playerHints

                    displayGrid(playerGrid, puzzleWidth, hints)
                }
            }
        }
    }

    companion object {
        private val TAG = GameplayActivity::class.java.simpleName
        val MESSAGE_SUFFIX = ".$TAG.display.UPDATE"
    }
}