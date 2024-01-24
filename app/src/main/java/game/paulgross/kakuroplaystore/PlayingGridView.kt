package game.paulgross.kakuroplaystore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

fun getResizedBitmap(bitmap: Bitmap , newWidth: Int , newHeight: Int ): Bitmap {

    val scaleWidth = newWidth.toFloat() / bitmap.width
    val scaleHeight = newHeight.toFloat() / bitmap.height

    val matrix = Matrix()
    matrix.postScale(scaleWidth, scaleHeight)

    val resizedBitmap = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    bitmap.recycle()
    return resizedBitmap
}

/**
 * The custom View to draw the playing grid.
 */
class PlayingGridView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var gameState: KakuroGameplayDefinition.StateVariables? = null // TODO: Get a copy of the latest gamestate

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
    private var defaultIndex = -1

    private var paperTexture: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.papertexture_02)

    private val colourNonPlaySquareInside = Color.argb(180, 40, 71, 156)
    private val colourSquareBorder = Color.argb(180, 29, 51, 112)

    private lateinit var gameplayActivity: KakuroGameplayActivity

    init {
        if (context is KakuroGameplayActivity) {
            gameplayActivity = context
        }

        // Attach the custom TouchListener
        setOnTouchListener(CustomListener(this))
    }

    fun setGameState(newestGameState: KakuroGameplayDefinition.StateVariables) {
        this.gameState = newestGameState

        // TODO: Can I detect the need to call setScreenSizes() here
        // TODO: So that the caller doesn't need to ALWAYS call setScreenSizes()???
        // TODO: Maybe look for changes in width, by storing the last measuredWidth ???
    }

    /**
     * Create a TouchArea lookup to find the index of the guess square that was touched.
     */
    data class TouchArea(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)
    var playSquareTouchLookUpId: MutableMap<TouchArea, Int> = mutableMapOf()

    /**
     * This CustomListener uses areas defined in the playSquareTouchLookUpId: Map to determine the Id for user screen touches.
     */
    class CustomListener(private val gridView: PlayingGridView):
        View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gridView.selectedIndex = lookupTouchedGuessId(event.x, event.y)
                    gridView.invalidate()  // Force the grid to be redrawn
                }
            }
            return true
        }

        /**
         * Search through all the TouchArea entries in the playSquareTouchLookUpId: Map to find the Id located at x, y.
         * Returns -1 if the touch isn't inside any of the defined TouchArea entries.
         */
        private fun lookupTouchedGuessId(x: Float, y: Float): Int {
            for (entry in gridView.playSquareTouchLookUpId.entries.iterator()) {
                if (x >= entry.key.xMin && x <= entry.key.xMax && y >= entry.key.yMin && y <= entry.key.yMax) {
                    return entry.value
                }
            }
            return -1
        }
    }

    fun clearSelectedIndex() {
        selectedIndex = -1
        invalidate()  // Force the grid to be redrawn
    }

    fun getSelectedIndex(): Int {
        return selectedIndex
    }

    fun setIndexToDefault() {
        // FIXME: Can't see why this doesn't work...
        // defaultIndex should NOT be -1 here!!!
        Log.d(TAG, "setIndexToDefault(): Setting selectedIndex to the default of $defaultIndex")
        selectedIndex = defaultIndex
        invalidate()
    }

    fun zoomGrid(changeZoom: Int) {
        if (currDisplayRows + changeZoom < minDisplayRows || currDisplayRows + changeZoom > maxDisplayRows) {
            return
        }
        if (currDisplayRows + displayZoom + changeZoom > gameState!!.puzzleWidth + 1 ) {
            return
        }
        displayZoom += changeZoom

        // Make sure the bottom right does not move too high or too far left.
        if (xSquaresOffset < 0) {
            xSquaresOffset++
        }
        if (ySquaresOffset < 0) {
            ySquaresOffset++
        }

        setScreenSizes()  // This alo forces a redraw
    }

    fun scrollGrid(xDeltaSquares: Int, yDeltaSquares: Int) {
        if (xSquaresOffset + xDeltaSquares > 0) {
            return
        }
        if (ySquaresOffset + yDeltaSquares > 0) {
            return
        }
        if (xSquaresOffset + xDeltaSquares < currDisplayRows - gameState!!.puzzleWidth -1) {
            return
        }
        if (ySquaresOffset + yDeltaSquares < currDisplayRows - gameState!!.puzzleHeight -1) {
            return
        }

        xSquaresOffset += xDeltaSquares
        ySquaresOffset += yDeltaSquares

        // The current touch areas are invalid after scrolling
        playSquareTouchLookUpId.clear()

        invalidate()  // Force the grid to be redrawn
    }

    fun setScreenSizes() {
        Log.d("PlayingGridView", "setScreenSizes(): Width = $measuredWidth, height = $measuredHeight")
        if (gameState == null) {
            Log.d("PlayingGridView", "setScreenSizes() exiting because there is not yet a gameState.")
            return
        }

        if (measuredWidth == 0 || measuredHeight == 0) {
            Log.d("PlayingGridView", "setScreenSizes() exiting because of invalid width or height.")
            return
        }

        playSquareTouchLookUpId.clear()

        currViewWidth = measuredWidth
        currViewHeight = measuredHeight

        val maxGridDimension = if (gameState!!.puzzleWidth > gameState!!.puzzleHeight) {
            gameState!!.puzzleWidth } else {gameState!!.puzzleHeight}

        currDisplayRows = maxGridDimension + 1 + displayZoom
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

        // scale the paperTexture
        paperTexture = getResizedBitmap(paperTexture, measuredWidth, measuredHeight)

        invalidate()  // Force a redraw
    }

    fun resetOptions() {
        displayZoom = 0
        selectedIndex = -1
        defaultIndex = -1
        xSquaresOffset = 0
        ySquaresOffset = 0
    }

    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d("PlayingGridView", "onDraw() running")
        if (gameState == null) {
            Log.d("PlayingGridView", "onDraw() exiting - No gameState to draw.")
            return
        }

        Log.d(TAG, "Start of onDraw(), now defaultIndex = $defaultIndex")

        canvas.drawBitmap(paperTexture, 0f, 0f, paint)

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
//        var defaultIndex = -1

        for (row in (1..gameState!!.puzzleHeight + 1)) {
            for (col in (1..gameState!!.puzzleWidth + 1)) {
                // First row and colum are only used as space for showing hints.
                val puzzleSquare = (row != 1 && col != 1)

                if (puzzleSquare) {
                    val gridValue = gameState!!.playerGrid[index]
                    // Non-playable grid value is -1, 0 means no guess yet, > 0 means a player guess
                    if (gridValue != -1) {
                        // Determine if this square is within the visible area.
                        var visible = true
                        if (x + squareWidth < 0 || y + squareWidth < 0 || x > measuredWidth || y > measuredHeight) {
                            visible = false
                        }

                        // FIXME - only do this for visible squares
                        if (defaultIndex == -1 && visible) {
                            defaultIndex = index
                            Log.d(TAG, "Setting default index to $defaultIndex")
                        }
                        val selected = (index == selectedIndex)

                        var possiblesString = gameState!!.possibles[index]
                        if (gridValue != 0) {
                            possiblesString = null
                        }

                        val error = gameState!!.playerErrors.contains(index)

                        drawGuessSquare(index, gridValue.toString(), possiblesString, selected, visible, error,
                            gameState!!.solved, addTouchAreas, currX, currY, canvas, paint)
                    } else {
                        drawBlankSquare(currX, currY, canvas, paint)
                    }

                    // TODO: add scroll touch areas for boundary squares.
                    // Any square that is partially on the visible area can be used to scroll.

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
            index = (row - 1) * gameState!!.puzzleWidth

            currX = xStart
            currY += squareWidth
        }
        Log.d(TAG, "End of onDraw(), now defaultIndex = $defaultIndex")
    }

    private fun drawGuessSquare(index : Int, content: String, possiblesString: String?, selected: Boolean, visible: Boolean,error: Boolean,
                                solved: Boolean,
                                addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint
    ) {
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
        if (solved) {
            paint.color = Color.GRAY
        } else {
            if (selected) {
                paint.color = Color.WHITE
            }
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

    companion object {
        private val TAG = PlayingGridView::class.java.simpleName
    }
}