package game.paulgross.kakuroplaystore

//import android.R
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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

        // Attach the TouchListener to the custom PlayingGridView
        val viewPlayGrid = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        viewPlayGrid.setActivity(this)
        viewPlayGrid.setOnTouchListener(PlayingGridView.CustomListener(this, viewPlayGrid))

        enableMessagesFromGameServer()
        GameServer.activate(applicationContext, getPreferences(MODE_PRIVATE))

        GameServer.queueActivityMessage("Status")  // Request a new State message
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
     * The custom View to draw the playing grid.
     */
    class PlayingGridView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

        private var currViewWidth = 1
        private var currViewHeight = 1
        private var squareWidth = 1f
        private var margin = 1f
        private var squareTextSize = 1f
        private var borderThickness =1f

        var puzzleWidth = 1

        // Adjust these for scrolling around large puzzles
        private var firstDisplayRow = 1
        private var firstDisplayCol = 1
        private var maxDisplayRows = 6
        private var maxDisplayCols = 6

        var playerGrid: MutableList<Int> = mutableListOf()
        var playerHints: MutableList<GameServer.Hint> = mutableListOf()

        private val colourNonPlaySquareInside = Color.rgb(40, 71, 156)
        private val colourSquareBorder = Color.rgb(29, 51, 112)

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

            margin = squareWidth * 0.08f
        }

        private val paint = Paint()

        // TODO - learn how to draw bitmaps to the canvas
//        val paperTexture: Bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.papertexture_01)
//        var dpaperTexture: Drawable = BitmapDrawable(getResources(), paperTexture)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            Log.d("PlayingGridView", "onDraw() running")

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
                index = (col - 1) * puzzleWidth + firstDisplayCol - 1
                currX = startX
                currY += squareWidth
            }

            // TODO - learn how to draw bitmaps to the canvas
//            dpaperTexture.draw(canvas)
        }

        private fun drawGuessSquare(index : Int, content: String, selected: Boolean, addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint) {
            paint.color = Color.LTGRAY
            if (selected) {
                paint.color = Color.WHITE
            }
            canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )

            if (content != "-1") {
                // Display the player's guess.
                paint.color = Color.RED
                paint.textSize = squareTextSize
                canvas.drawText(content, x + squareWidth * 0.31f, y + squareWidth * 0.75f, paint)
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
                    val puzzleWidth = newState.puzzleWidth
                    val playerGrid = newState.playerGrid
                    val hints = newState.playerHints

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