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
import android.view.View.OnTouchListener
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


class GameplayActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)

        // Attach the touch handler to the custom Play View
        // TODO - can I move this inside the PlayingGridView class???
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setOnTouchListener(View.OnTouchListener { _, event ->
            val x = event.x
            val y = event.y

            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "ACTION_DOWN \nx: $x\ny: $y")
                    // Here I want to lookup the square touched.
                    viewPlayGrid.getTouchedGuessId(x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    Log.d(TAG, "ACTION_MOVE \nx: $x\ny: $y")
                }
                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "ACTION_UP \nx: $x\ny: $y")
                }
            }
            return@OnTouchListener  true
        })

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

    var theTest = 1
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
         * The lookup from touch areas to the index of the guess square.
         */
        data class TouchArea(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)
        var playSquareTouchLookId: MutableMap<TouchArea, Int> = mutableMapOf()

        fun getTouchedGuessId(x: Float, y: Float): Int {
            Log.d("PlayingGridView", "TODO: Lookup Guess Id for $x, $y")

            return -1
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
                    Log.d("PlayingGridView", "currX = $currX")
                    Log.d("PlayingGridView", "currY = $currY")

                    val puzzleSquare = (col != 1 && row != 1)

                    if (puzzleSquare) {
                        val gridValue = playerGrid[index]
                        if (gridValue == -1) {
                            drawGuessSquare(index, currX, currY, canvas, paint)
                            drawSquareGuess("9", currX, currY, canvas, paint)
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

        private fun drawGuessSquare(index : Int, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.WHITE
            canvas?.drawRect(x + margin, y + margin,
                x + squareWidth - margin, y + squareWidth - margin, paint )

            playSquareTouchLookId.put(TouchArea(x, y, x + squareWidth, y + squareWidth), index)
        }

        private fun drawSquareGuess(content: String, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.RED
            paint.setTextSize(squareTextSize)
            canvas.drawText(content, x + squareWidth * 0.31f, y + squareWidth * 0.75f, paint)
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