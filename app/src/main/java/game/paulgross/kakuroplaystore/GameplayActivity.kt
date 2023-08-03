package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private fun displayGrid(playerGrid: MutableList<Int>, puzzleWidth:Int) {
        // DEBUG text grid first
        val debugView = findViewById<TextView>(R.id.textViewDebug)
        var debugText = "width = $puzzleWidth\n"

        var squareCounter = 0
        while (squareCounter < playerGrid.size) {

            val isFirstColumn = (squareCounter.mod(puzzleWidth) == 0)
            if (isFirstColumn) { debugText += "\n" }
            debugText += playerGrid[squareCounter].toString()

            squareCounter++
        }
        debugView.text = debugText

        // Send new data to the Gameplay View
        val playGridView = findViewById<View>(R.id.viewPlayGrid)
        (playGridView as PlayingGridView).playerGrid = playerGrid
        (playGridView as PlayingGridView).puzzleWidth = puzzleWidth
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
        var playerGrid: MutableList<Int> = mutableListOf()

        /**
         * TouchArea lookup to the index of the guess square.
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
                            return true
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

            squareWidth = currViewWidth/(puzzleWidth + 2f)
            Log.d("PlayingGridView", "squareWidth = $squareWidth")

            margin = squareWidth * 0.05f
            squareTextSize = squareWidth * 0.7f
        }

        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            Log.d("PlayingGridView", "onDraw() running")

            // Paint the canvas background
            paint.color = Color.BLUE
            canvas.drawPaint(paint)

            // Draw grid
            paint.color = Color.WHITE

            val startX = squareWidth/2f

            var currX = startX
            var currY = squareWidth/2f

            val rows = puzzleWidth + 1
            val cols = playerGrid.size.div(puzzleWidth) + 1
            var index = 0
            for (col in (1..cols)) {

                for (row in (1..rows)) {
                    val puzzleSquare = (col != 1 && row != 1)

                    if (puzzleSquare) {
                        val gridValue = playerGrid[index]
                        if (gridValue != 0) {
                            val selected = (index == gameplayActivity?.selectedId)
                            drawGuessSquare(index, selected, currX, currY, canvas, paint)
                            if (gridValue > 0) {
                                drawSquareGuess(gridValue.toString(), currX, currY, canvas, paint)
                            }
                        }
                    }

                    currX += squareWidth

                    // If this isn't the top row or left column, increment index
                    if (puzzleSquare) {
                        index++
                    }
                }
                currX = startX
                currY += squareWidth
            }
        }

        private fun drawGuessSquare(index : Int, selected: Boolean ,x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.GRAY
            if (selected) {
                paint.color = Color.WHITE
            }
            canvas?.drawRect(x + margin, y + margin,
                x + squareWidth - margin, y + squareWidth - margin, paint )

            playSquareTouchLookUpId.put(TouchArea(x, y, x + squareWidth, y + squareWidth), index)
        }

        private fun drawSquareGuess(content: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.RED
            paint.setTextSize(squareTextSize)
            canvas.drawText(content, x + squareWidth * 0.31f, y + squareWidth * 0.75f, paint)
        }

        private var gameplayActivity: GameplayActivity? = null
        fun setActivity(gameplayActivity: GameplayActivity) {
            this.gameplayActivity = gameplayActivity
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

                    // TODO:
                    displayGrid(playerGrid, puzzleWidth)
                }
            }
        }
    }

    companion object {
        private val TAG = GameplayActivity::class.java.simpleName
        val MESSAGE_SUFFIX = ".$TAG.display.UPDATE"
    }
}