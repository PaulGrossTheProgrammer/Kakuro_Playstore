package game.paulgross.kakuroplaystore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class GameplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)

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

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            Log.d("PlayingGridView", "Width = $measuredWidth, height = $measuredHeight")
            currViewWidth = measuredWidth
            currViewHeight = measuredHeight

            squareWidth = currViewWidth/(puzzleWidth + 1f)

            margin = squareWidth * 0.15f
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

            var currX = 0f
            var currY = 0f

            Log.d("PlayingGridView", "squareWidth = $squareWidth")
1
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
                            drawSquare(currX, currY, canvas, paint)
                            drawSquareGuess("9", currX, currY, canvas, paint)
                        }
                    }

                    currX += squareWidth

                    // If this isn't the top row or left column, increment index
                    if (puzzleSquare) {
                        index++
                    }
                }
                currX = 0f
                currY += squareWidth
            }

        }

        private fun drawSquare(x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.WHITE
            canvas?.drawRect(x + margin, y + margin,
                x + squareWidth - margin, y + squareWidth - margin, paint )
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