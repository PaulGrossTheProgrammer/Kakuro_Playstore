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

    private var gameState: KakuroGameplayDefinition.StateVariables? = null

    private var currViewWidth = 1
    private var currViewHeight = 1

    private var squareWidth = 1f
    private var slashMargin = 1f
    private var squareTextSize = 1f
    private var possiblesTextSize = 1f
    private var borderThickness =1f

    // TODO - store these so that restarts and screen rotations will preserve the settings.
    private var currDisplayRows = MAX_DISPLAY_ROWS
    private var displayZoom = 0
    private var xSquaresOffset = 0
    private var ySquaresOffset = 0

    private var navigatedByDpad = false  // Indicates that the dpad has navigated to this grid.
    private var selectedIndex: Int = -1
    private var defaultIndex = -1

    private var paperTexture: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.papertexture_02)

    private val colourNonPlaySquareInside = Color.argb(180, 40, 71, 156)
    private val colourSquareBorder = Color.argb(180, 29, 51, 112)

    private lateinit var gameplayActivity: KakuroGameplayActivity

    // TODO: Make a bunch of paint objects here to execute onDraw() faster...
    private val paint = Paint()
    private val selectedByNavPaint = Paint()

    init {
        if (context is KakuroGameplayActivity) {
            gameplayActivity = context
        }

        val preferences = gameplayActivity.getPreferences(Context.MODE_PRIVATE)
        val zoom = preferences.getString("UI.grid.zoom", null)
        if (zoom != null) {
            // FIXME - handle invalid string.
            displayZoom = zoom.toInt()
        }
        val xOffset = preferences.getString("UI.grid.xOffset", null)
        if (xOffset != null) {
            // FIXME - handle invalid string.
            xSquaresOffset = xOffset.toInt()
        }
        val yOffset = preferences.getString("UI.grid.yOffset", null)
        if (yOffset != null) {
            // FIXME - handle invalid string.
            ySquaresOffset = yOffset.toInt()
        }

        val index = preferences.getString("UI.grid.selectedIndex", null)
        if (index != null) {
            // FIXME - handle invalid string.
            selectedIndex = index.toInt()
        }

        // Setup Paint objects for drawing the grid.
        selectedByNavPaint.style = Paint.Style.STROKE
        selectedByNavPaint.color = Color.CYAN

        setOnTouchListener(CustomListener(this))
    }

    fun setGameState(newestGameState: KakuroGameplayDefinition.StateVariables) {
        var needNewSizes = (gameState == null)

        var oldWidth = 0
        if (gameState != null) {
            oldWidth = gameState!!.puzzleWidth
        }

        gameState = newestGameState

        if (oldWidth != gameState!!.puzzleWidth) {
            needNewSizes = true
        }

        if (needNewSizes || lastKnownWidth == 0 || lastKnownHeight == 0 || lastKnownWidth != measuredWidth || lastKnownHeight != measuredHeight) {
            Log.d(TAG, "setGameState calling setScreenSizes()")
            setScreenSizes()
        } else {
            Log.d(TAG, "setGameState SKIPPING setScreenSizes()")
        }
        invalidate()
    }

    /**
     * Create a TouchArea lookup to find the index of the guess square that was touched.
     * Each TouchArea defines the top-left and bottom right of the touchscreen rectangle.
     */
    data class TouchArea(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)

    var playSquareTouchLookUpId: MutableMap<TouchArea, Int> = mutableMapOf()

    var boundaryLeftTouchLookupId: MutableMap<TouchArea, Int> = mutableMapOf()
    var boundaryRightTouchLookupId: MutableMap<TouchArea, Int> = mutableMapOf()
    var boundaryTopTouchLookupId: MutableMap<TouchArea, Int> = mutableMapOf()
    var boundaryBottomTouchLookupId: MutableMap<TouchArea, Int> = mutableMapOf()

    /**
     * This CustomListener uses TouchAreas in several lookup: Maps to determine what to do with the touched area.
     */
    class CustomListener(private val gridView: PlayingGridView):
        View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gridView.selectedIndex = lookupTouchedId(gridView.playSquareTouchLookUpId, event.x, event.y)

                    // Also autoscroll if a special square was selected
                    if (lookupTouchedId(gridView.boundaryLeftTouchLookupId, event.x, event.y) != -1) {
                        gridView.scrollGridLeft(2)
                    }
                    if (lookupTouchedId(gridView.boundaryRightTouchLookupId, event.x, event.y) != -1) {
                        gridView.scrollGridRight(2)
                    }

                    if (lookupTouchedId(gridView.boundaryTopTouchLookupId, event.x, event.y) != -1) {
                        gridView.scrollGridUp(2)
                    }
                    if (lookupTouchedId(gridView.boundaryBottomTouchLookupId, event.x, event.y) != -1) {
                        gridView.scrollGridDown(2)
                    }

                    gridView.saveUIState()

                    gridView.invalidate()  // Force the grid to be redrawn
                }
            }
            return true
        }

        /**
         * Search through all the TouchArea entries in the lookup: Map to find the Id located at x, y.
         * Returns -1 if the touch isn't inside any of the defined TouchArea entries.
         */
        private fun lookupTouchedId(lookup: Map<TouchArea, Int>, x: Float, y: Float): Int {
            for (entry in lookup.entries.iterator()) {
                if (x >= entry.key.xMin && x <= entry.key.xMax && y >= entry.key.yMin && y <= entry.key.yMax) {
                    return entry.value
                }
            }
            return -1
        }
    }

    fun getSelectedIndex(): Int {
        return selectedIndex
    }

    fun setIndexToDefault() {
        selectedIndex = defaultIndex
        invalidate()
    }

    fun zoomGrid(changeZoom: Int) {
        if (currDisplayRows + changeZoom < MIN_DISPLAY_ROWS || currDisplayRows + changeZoom > MAX_DISPLAY_ROWS) {
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

        saveUIState()

        setScreenSizes()  // This also forces a redraw
    }

    private fun saveUIState() {
        val preferences = gameplayActivity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()
        editor.putString("UI.grid.zoom", displayZoom.toString())
        editor.putString("UI.grid.xOffset", xSquaresOffset.toString())
        editor.putString("UI.grid.yOffset", ySquaresOffset.toString())
        editor.putString("UI.grid.selectedIndex", selectedIndex.toString())
        editor.apply()
    }

    private fun resetTouchAreas() {
        playSquareTouchLookUpId.clear()
    }

    fun scrollGridDir(dir: KakuroGameplayActivity.NavDirection, repeats: Int) {
        when (dir) {
            KakuroGameplayActivity.NavDirection.CURSOR_LEFT -> scrollGridLeft(repeats)
            KakuroGameplayActivity.NavDirection.CURSOR_RIGHT -> scrollGridRight(repeats)
            KakuroGameplayActivity.NavDirection.CURSOR_UP -> scrollGridUp(repeats)
            KakuroGameplayActivity.NavDirection.CURSOR_DOWN -> scrollGridDown(repeats)
        }
    }

    fun scrollGridLeft(repeats: Int) {
        for (i in 1..repeats) {
            scrollGridGeneral(1,0)
        }
    }

    fun scrollGridRight(repeats: Int) {
        for (i in 1..repeats) {
            scrollGridGeneral(-1,0)
        }
    }

    fun scrollGridUp(repeats: Int) {
        for (i in 1..repeats) {
            scrollGridGeneral(0,1)
        }
    }

    fun scrollGridDown(repeats: Int) {
        for (i in 1..repeats) {
            scrollGridGeneral(0,-1)
        }
    }

    private fun scrollGridGeneral(xDeltaSquares: Int, yDeltaSquares: Int) {
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

        saveUIState()

        // The current touch areas are invalid after scrolling
        resetTouchAreas()

        invalidate()  // Force the grid to be redrawn
    }

    private fun checkAutoscrollForDpad() {
        if (boundaryLeftTouchLookupId.containsValue(selectedIndex)) {
            scrollGridLeft(2)
            invalidate()  // Force the grid to be redrawn
        }
        if (boundaryRightTouchLookupId.containsValue(selectedIndex)) {
            scrollGridRight(2)
            invalidate()  // Force the grid to be redrawn
        }

        if (boundaryTopTouchLookupId.containsValue(selectedIndex)) {
            scrollGridUp(2)
            invalidate()  // Force the grid to be redrawn
        }
        if (boundaryBottomTouchLookupId.containsValue(selectedIndex)) {
            scrollGridDown(2)
            invalidate()  // Force the grid to be redrawn
        }
    }

    private var lastKnownWidth = 0
    private var lastKnownHeight = 0

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

        Log.d(TAG, "setScreenSizes using w = $measuredWidth, h = $measuredHeight")
        lastKnownWidth = measuredWidth
        lastKnownHeight = measuredHeight

        // TODO - pre-allocate the TouchArea for each on screen index.
        // So that we can remove the allocation code and boundary intersect code from onDraw().

        resetTouchAreas()

        currViewWidth = measuredWidth
        currViewHeight = measuredHeight

        val maxGridDimension = if (gameState!!.puzzleWidth > gameState!!.puzzleHeight) {
            gameState!!.puzzleWidth } else {gameState!!.puzzleHeight}

        currDisplayRows = maxGridDimension + 1 + displayZoom
        if (currDisplayRows > MAX_DISPLAY_ROWS) {
            currDisplayRows = MAX_DISPLAY_ROWS
        }
        if (currDisplayRows < MIN_DISPLAY_ROWS) {
            currDisplayRows = MIN_DISPLAY_ROWS
        }

        squareWidth = (currViewWidth/(currDisplayRows + OUTSIDE_GRID_MARGIN)).toFloat()
        Log.d("PlayingGridView", "squareWidth = $squareWidth")
        borderThickness = squareWidth * 0.06f

        squareTextSize = squareWidth * 0.7f
        possiblesTextSize = squareWidth * 0.25f

        slashMargin = squareWidth * 0.08f

        // scale the paperTexture
        paperTexture = getResizedBitmap(paperTexture, measuredWidth, measuredHeight)

        selectedByNavPaint.strokeWidth = squareWidth * 0.18f

        invalidate()  // Force a redraw
    }

    fun resetOptions() {
        displayZoom = 0
        selectedIndex = -1
        defaultIndex = -1
        xSquaresOffset = 0
        ySquaresOffset = 0

        resetTouchAreas()
        saveUIState()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d("PlayingGridView", "onDraw() running")
        if (gameState == null) {
            Log.d("PlayingGridView", "onDraw() exiting - No gameState to draw.")
            return
        }

        canvas.drawBitmap(paperTexture, 0f, 0f, paint)

        // Add new touch areas if there are currently none.
        val addTouchAreas = playSquareTouchLookUpId.isEmpty()

        if (addTouchAreas) {
            Log.d(TAG, "Adding touch areas....")
            boundaryLeftTouchLookupId.clear()
        }


        paint.color = Color.WHITE

        val xStart = (squareWidth * OUTSIDE_GRID_MARGIN)/2 + xSquaresOffset * squareWidth
        val yStart = (squareWidth * OUTSIDE_GRID_MARGIN)/2 + ySquaresOffset * squareWidth

        var currX = xStart
        var currY = yStart

        var rows = gameState!!.puzzleWidth + 1
        var cols = gameState!!.playerGrid.size.div(gameState!!.puzzleWidth) + 1

        // Assume a square display area.
        if (rows > MAX_DISPLAY_ROWS) {
            rows = MAX_DISPLAY_ROWS
        }
        if (cols > MAX_DISPLAY_ROWS) {
            cols = MAX_DISPLAY_ROWS
        }

        var index = 0

        for (row in (1..gameState!!.puzzleHeight + 1)) {
            for (col in (1..gameState!!.puzzleWidth + 1)) {

                if (addTouchAreas) {
                    // Add any boundary squares for auto scrolling
                    if (currX < 0 && currX + squareWidth > 0) {
                        boundaryLeftTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                    if (currX < measuredWidth && currX + squareWidth > measuredWidth) {
                        boundaryRightTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                    if (currY < 0 && currY + squareWidth > 0) {
                        boundaryTopTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                    if (currY < measuredHeight && currY + squareWidth > measuredHeight) {
                        boundaryBottomTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                }

                // First row and colum are only used as space for showing hints.
                val puzzleSquare = (row != 1 && col != 1)

                if (puzzleSquare) {
                    val gridValue = gameState!!.playerGrid[index]
                    // Non-playable grid value is -1, 0 means no guess yet, > 0 means a player guess
                    if (gridValue != -1) {
                        // Determine if this square is within the visible area.
                        var visible = true
                        if (currX + squareWidth < 0 || currY + squareWidth < 0 || currX > measuredWidth || currY > measuredHeight) {
                            visible = false
                        }

                        if (selectedIndex == -1 && defaultIndex == -1 && visible) {
                            defaultIndex = index
                            Log.d(TAG, "Setting default index to $defaultIndex")
                            selectedIndex = index // Is this OK for both TV and phone/tablet???
                        }
                        val selected = (index == selectedIndex)

                        var possiblesString = gameState!!.possibles[index]
                        if (gridValue != 0) {
                            possiblesString = null
                        }

                        val error = gameState!!.playerErrors.contains(index)

                        // TODO: Use navigatedByDpad to add a selected border if true.

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

    private fun drawGuessSquare(index : Int, content: String, possiblesString: String?, selected: Boolean,
                                visible: Boolean,error: Boolean, solved: Boolean,
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

        if (navigatedByDpad && selected) {
            canvas.drawRect(x, y, x + squareWidth, y + squareWidth, selectedByNavPaint)
        }

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

    fun navigateGrid(dir: KakuroGameplayActivity.NavDirection) {
        Log.d(TAG, "navigateGrid $dir")
        if (selectedIndex == -1) {
            selectedIndex = defaultIndex
            invalidate()
        }

        val nextSquare = searchForNextSquare(selectedIndex, dir)
        Log.d(TAG, "navigateGrid: New index $nextSquare")
        if (nextSquare != -1) {
            selectedIndex = nextSquare
            checkAutoscrollForDpad()
            invalidate()
        } else {
            scrollGridDir(dir, 1)
        }

        saveUIState()
    }

    private fun searchForNextSquare(startIndex: Int, direction: KakuroGameplayActivity.NavDirection): Int {
        val delta = when (direction) {
            KakuroGameplayActivity.NavDirection.CURSOR_UP -> -gameState!!.puzzleWidth
            KakuroGameplayActivity.NavDirection.CURSOR_DOWN -> +gameState!!.puzzleWidth
            KakuroGameplayActivity.NavDirection.CURSOR_LEFT -> -1
            KakuroGameplayActivity.NavDirection.CURSOR_RIGHT -> +1
        }

        var currTestLocation = startIndex
        // Keep moving in one direction until we reach a play square, or leave the grid.
        do {
            currTestLocation += delta

            // Exit with -1 if the search has gone outside the grid.
            if (currTestLocation < 0 || currTestLocation >= gameState!!.playerGrid.size) {
                return -1
            }
            // Exit with -1 if the search has crossed the right edge.
            if (direction == KakuroGameplayActivity.NavDirection.CURSOR_RIGHT
                && currTestLocation.mod(gameState!!.puzzleWidth) == 0) {
                return -1
            }
            // Exit with -1 if the search has crossed the left edge.
            if (direction == KakuroGameplayActivity.NavDirection.CURSOR_LEFT
                && currTestLocation.mod(gameState!!.puzzleWidth) == gameState!!.puzzleWidth - 1) {
                return -1
            }
        } while (gameState!!.playerGrid[currTestLocation] == -1)

        return currTestLocation
    }

    fun setDpadNavSelected() {
        navigatedByDpad = true
        invalidate()
    }

    fun unsetDpadNavSelected() {
        navigatedByDpad = false
        invalidate()
    }

    companion object {
        private val TAG = PlayingGridView::class.java.simpleName

        const val OUTSIDE_GRID_MARGIN = 1.4f
        const val MAX_DISPLAY_ROWS = 10
        const val MIN_DISPLAY_ROWS = 5
    }
}