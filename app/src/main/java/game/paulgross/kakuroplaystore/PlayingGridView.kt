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
import kotlin.math.ceil
import kotlin.math.floor

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

    private var currDisplayRows = MAX_DISPLAY_ROWS
    private var displayZoom = 0
    private var xSquaresOffset = 0
    private var ySquaresOffset = 0

    private var showHelp = false

    private var navigatedByDpad = false  // Indicates that the dpad has navigated to this grid.
    private var selectedIndex: Int = -1
    private var defaultIndex = -1

    private val downHelpSets = HelpSets()
    private val acrossHelpSets = HelpSets()

    // These variables control the momentary flash of the chosen digit.
    var flashIndex = -1
    var flashIndexRatio = 1.0f

    private var paperTexture: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.papertexture_02)

    private val colourNonPlaySquareInside = Color.argb(180, 40, 71, 156)
    private val colourSquareBorder = Color.argb(180, 29, 51, 112)

    private lateinit var gameplayActivity: KakuroGameplayActivity

    private val paint = Paint()
    private val selectedByNavPaint = Paint()

    init {
        println("#### PlayingGridView - init() ...")
        // FIXME - How to force a siz e recalc and redraw on app resume???
        currViewWidth = 1
        currViewHeight = 1

        if (context is KakuroGameplayActivity) {
            gameplayActivity = context
        }

        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        selectedByNavPaint.strokeJoin = Paint.Join.ROUND
        selectedByNavPaint.strokeCap = Paint.Cap.ROUND

        val preferences = gameplayActivity.getPreferences(Context.MODE_PRIVATE)

        val savedHelpState = preferences.getString("UI.grid.showHelp", null)
        if (savedHelpState != null) {
            showHelp = savedHelpState.toBoolean()
        }
        val zoom = preferences.getString("UI.grid.zoom", null)
        if (zoom != null) {
            val converted = zoom.toIntOrNull()
            displayZoom = when (converted) {
                null -> 0
                else -> converted
            }
        }
        val xOffset = preferences.getString("UI.grid.xOffset", null)
        if (xOffset != null) {
            val converted = xOffset.toIntOrNull()
            xSquaresOffset = when (converted) {
                null -> 0
                else -> converted
            }
        }
        val yOffset = preferences.getString("UI.grid.yOffset", null)
        if (yOffset != null) {
            val converted = yOffset.toIntOrNull()
            ySquaresOffset = when (converted) {
                null -> 0
                else -> converted
            }
        }

        val index = preferences.getString("UI.grid.selectedIndex", null)
        if (index != null) {
            val converted = index.toIntOrNull()
            selectedIndex = when (converted) {
                null -> 0
                else -> converted
            }
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

        val prevKey = gameState?.puzzleKey
        gameState = newestGameState
        val currKey = gameState?.puzzleKey
        if (currKey != prevKey) {
            acrossHelpSets.indexLookup.clear()
            downHelpSets.indexLookup.clear()
        }

        if (oldWidth != gameState!!.puzzleWidth) {
            needNewSizes = true
        }

        if (needNewSizes || lastKnownWidth == 0 || lastKnownHeight == 0 || lastKnownWidth != measuredWidth || lastKnownHeight != measuredHeight) {
            Log.d(TAG, "setGameState calling rescaleScreenObjects()")
            rescaleScreenObjects()
        } else {
            Log.d(TAG, "setGameState SKIPPING rescaleScreenObjects()")
            invalidate()
        }
    }

    fun setHelpSets(newDownHelpSet: HelpSets, newAcrossHelpSet: HelpSets ) {
        downHelpSets.indexLookup = newDownHelpSet.indexLookup
        acrossHelpSets.indexLookup = newAcrossHelpSet.indexLookup
        invalidate()
    }

    fun toggleShowHelp() {
        showHelp = !showHelp
        saveUIState()
        rescaleScreenObjects()
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

        if (changeZoom < 0) {
            // When zooming in, ensure the selected index does not move off the screen.
            val rightHalf = (currDisplayRows - xSquaresOffset)/2 < selectedIndex.rem(gameState!!.puzzleWidth)
            if (rightHalf) {
                xSquaresOffset--  // Track back to the right
            }

            val bottomHalf = (currDisplayRows - ySquaresOffset)/2 < selectedIndex.div(gameState!!.puzzleWidth)
            if (bottomHalf) {
                ySquaresOffset--  // Track back down
            }
        } else {
            // Make sure the bottom right does not move too high or too far left.
            if (xSquaresOffset < 0) {
                xSquaresOffset++
            }
            if (ySquaresOffset < 0) {
                ySquaresOffset++
            }
        }

        saveUIState()
        rescaleScreenObjects()
    }

    private fun saveUIState() {
        val preferences = gameplayActivity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()
        editor.putString("UI.grid.showHelp", showHelp.toString())
        editor.putString("UI.grid.zoom", displayZoom.toString())
        editor.putString("UI.grid.xOffset", xSquaresOffset.toString())
        editor.putString("UI.grid.yOffset", ySquaresOffset.toString())
        editor.putString("UI.grid.selectedIndex", selectedIndex.toString())
        editor.apply()
    }

    private fun resetTouchAreas() {
        playSquareTouchLookUpId.clear()
    }

    private fun scrollGridDir(dir: KakuroGameplayActivity.NavDirection, repeats: Int) {
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


    /**
     * Changes the sizes of objects drawn on the screen, clears the touch areas on the screen, and then requests a redraw.
     *
     * Note that if the touch areas have been cleared, a redraw will rebuild the touch areas.
     *
     * This is required when either the screen changes size, such as when it is rotated between portrait and landscape,
     * or the zoom level of the grid is changed.
     */
    private fun rescaleScreenObjects() {
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

        // maxGridDimension is the larger of either puzzleWidth or puzzleHeight
        val maxGridDimension = if (gameState!!.puzzleWidth > gameState!!.puzzleHeight) {
            gameState!!.puzzleWidth } else {gameState!!.puzzleHeight}

        currDisplayRows = maxGridDimension + 1 + displayZoom
        if (currDisplayRows > maxGridDimension + 1) {
            currDisplayRows = maxGridDimension + 1
            displayZoom = 0
        }
        if (currDisplayRows > MAX_DISPLAY_ROWS) {
            currDisplayRows = MAX_DISPLAY_ROWS
            displayZoom = currDisplayRows - maxGridDimension - 1
        }
        if (currDisplayRows < MIN_DISPLAY_ROWS) {
            currDisplayRows = MIN_DISPLAY_ROWS
            displayZoom = currDisplayRows - maxGridDimension - 1
        }

        var borderOffset = GRID_RIGHT_MARGIN

        if (showHelp) {
            borderOffset = (GRID_LEFT_MARGIN_HELP + GRID_RIGHT_MARGIN)/2
        }

        // Determine squareWidth from max of row vs. columns.
        if (gameState!!.puzzleWidth > gameState!!.puzzleHeight) {
            squareWidth = (currViewWidth/(gameState!!.puzzleWidth + 1 + displayZoom + borderOffset)).toFloat()
        } else {
            squareWidth = (currViewWidth/(gameState!!.puzzleHeight + 1 + displayZoom + borderOffset)).toFloat()
        }

        Log.d("PlayingGridView", "squareWidth = $squareWidth")
        borderThickness = squareWidth * 0.06f

        squareTextSize = squareWidth * 0.7f
        possiblesTextSize = squareWidth * 0.25f

        slashMargin = squareWidth * 0.08f

        // scale the paperTexture
        paperTexture = getResizedBitmap(paperTexture, measuredWidth, measuredHeight)

        selectedByNavPaint.strokeWidth = squareWidth * 0.1f

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

        var xStart = (squareWidth * GRID_RIGHT_MARGIN)/2 + xSquaresOffset * squareWidth
        var yStart = (squareWidth * GRID_BOTTOM_MARGIN)/2 + ySquaresOffset * squareWidth

        if (showHelp) {
            xStart = (squareWidth * GRID_LEFT_MARGIN_HELP)/2 + xSquaresOffset * squareWidth
            yStart = (squareWidth * GRID_TOP_MARGIN_HELP)/2 + ySquaresOffset * squareWidth
        }

        var currX = xStart
        var currY = yStart

        var index = 0
        var selectedX: Float? = null
        var selectedY: Float? = null
        var alpha = 255

        for (row in (1..gameState!!.puzzleHeight + 1)) {
            for (col in (1..gameState!!.puzzleWidth + 1)) {

                if (addTouchAreas) {
                    // Add any boundary squares for auto scrolling
                    if (currX <= 0 && currX + squareWidth > 0) {
                        boundaryLeftTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                    if (currX < measuredWidth && currX + squareWidth > measuredWidth) {
                        boundaryRightTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                    if (currY <= 0 && currY + squareWidth > 0) {
                        boundaryTopTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                    if (currY < measuredHeight && currY + squareWidth > measuredHeight) {
                        boundaryBottomTouchLookupId.put(TouchArea(currX, currY, currX + squareWidth, currY + squareWidth), index)
                    }
                }

                // Make the top edge square fainter, so the help combinations are more visible.
                if (showHelp && (currX < squareWidth * 0.8 || currY < squareWidth * 0.8)) {
                    alpha = 90
                } else {
                    alpha = 255
                }

                // First row and colum are only used as space for showing hints.
                val puzzleSquare = (row != 1 && col != 1)

                if (puzzleSquare) {
                    val gridValue = gameState!!.playerGrid[index]
                    // Non-playable grid value is -1, 0 means no guess yet, > 0 means a player guess
                    if (gridValue != -1) {
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
                        if (selected) {
                            selectedX = currX
                            selectedY = currY
                        }

                        val flash = (index == flashIndex)

                        var possiblesString = gameState!!.possibles[index]
                        if (gridValue != 0) {
                            possiblesString = null
                        }

                        val error = gameState!!.playerErrors.contains(index)

                        drawGuessSquare(index, gridValue.toString(), possiblesString, selected, visible, error, flash,
                            gameState!!.solved, addTouchAreas, currX, currY, canvas, paint, alpha)
                    } else {
                        drawBlankSquare(currX, currY, canvas, paint, alpha)
                    }

                    gameState!!.playerHints.forEach { hint ->
                        if (index == hint.index) {
                            if (hint.direction == KakuroGameplayDefinition.Direction.DOWN) {
                                drawDownHint(hint.total.toString(), currX, currY, canvas, paint, alpha)
                            } else if (hint.direction == KakuroGameplayDefinition.Direction.ACROSS) {
                                drawAcrossHint(hint.total.toString(), currX, currY, canvas, paint, alpha)
                            }
                        }
                    }
                    index++
                } else {
                    drawBlankSquare(currX, currY, canvas, paint, alpha)
                }

                drawSquareBorder(currX, currY, canvas, paint, alpha)

                currX += squareWidth
            }
            index = (row - 1) * gameState!!.puzzleWidth

            currX = xStart
            currY += squareWidth
        }

        // Drawing the selected square border.
        if (navigatedByDpad && selectedX != null && selectedY != null) {
            drawSelectionSquare(selectedX, selectedY, canvas, selectedByNavPaint)
        }

        if (showHelp) {
            val fullFontSize = squareWidth * 0.45f

            val selDownHelpSets = downHelpSets.indexLookup[selectedIndex]
            if (selDownHelpSets != null) {
                val helpText = helpersToString(selDownHelpSets)
                paint.textSize = fullFontSize

                paint.color = Color.BLACK

                var textLineBoundary = floor(2.1f * (currDisplayRows + 1)).toInt()

                if (helpText.length < textLineBoundary) {

                    var downPos = squareWidth * 1.15f
                    for (currChar in helpText) {
                        if (currChar.toString() == " ") {
                            downPos += (squareWidth * 0.22f)
                        } else {
                            canvas.drawText(currChar.toString(), squareWidth * 0.38f, downPos, paint)
                            downPos += (squareWidth * 0.48f)
                        }
                    }
                } else {
                    val split = splitHelperGroupString(helpText)
                    val line1Text = split[0]
                    val line2Text = split[1]

                    textLineBoundary = floor(4.4f * (currDisplayRows + 1)).toInt()
                    var verticalSpacer = 0f
                    if (line1Text.length < textLineBoundary) {
                        paint.textSize = fullFontSize * 0.60f
                        verticalSpacer = fullFontSize * 0.46f
                    } else {
                        paint.textSize = fullFontSize * 0.46f
                        verticalSpacer = fullFontSize * 0.38f
                    }

                    var downPos = squareWidth * 1.05f
                    for (currChar in line1Text) {
                        if (currChar.toString() == " ") {
                            downPos += (verticalSpacer * 0.25f)
                        } else {
                            canvas.drawText(currChar.toString(), squareWidth * 0.32f, downPos, paint)
                            downPos += (verticalSpacer)
                        }
                    }

                    downPos = squareWidth * 1.05f
                    for (currChar in line2Text) {
                        if (currChar.toString() == " ") {
                            downPos += (verticalSpacer * 0.25f)
                        } else {
                            canvas.drawText(currChar.toString(), squareWidth * 0.58f, downPos, paint)
                            downPos += (verticalSpacer)
                        }
                    }
                }
            }

            val selAcrossHelpSets = acrossHelpSets.indexLookup[selectedIndex]
            if (selAcrossHelpSets != null) {
                var helpText = helpersToString(selAcrossHelpSets)

                paint.color = Color.BLACK
                paint.textSize = fullFontSize

                val textLineBoundary = floor(2.1f * (currDisplayRows + 1)).toInt()
                if (helpText.length > textLineBoundary) {
                    val helpGroups = helpText.split(" ")
                    val groupsHalfCount = ceil(0.5f * helpGroups.size).toInt()
                    val line1Text = StringBuilder()
                    val line2Text = StringBuilder()
                    helpGroups.forEachIndexed { index, groupText ->
                        if (index < groupsHalfCount) {
                            if (line1Text.isNotEmpty()) { line1Text.append(", ") }
                            line1Text.append(groupText)
                        } else {
                            if (line2Text.isNotEmpty()) { line2Text.append(", ") }
                            line2Text.append(groupText)
                        }
                    }

                    val shrinkFactor = 0.50f
                    if (line1Text.length < textLineBoundary / shrinkFactor) {
                        paint.textSize = fullFontSize * 0.48f
                        canvas.drawText(line1Text.toString(), squareWidth * 1.02f, squareWidth * 0.60f, paint)
                        canvas.drawText(line2Text.toString(), squareWidth * 1.02f, squareWidth * 0.90f, paint)
                    } else {
                        paint.textSize = fullFontSize * 0.40f
                        canvas.drawText(line1Text.toString(), squareWidth * 1.02f, squareWidth * 0.66f, paint)
                        canvas.drawText(line2Text.toString(), squareWidth * 1.02f, squareWidth * 0.90f, paint)
                    }
                } else {
                    helpText = helpText.replace(" ", ", ")
                    canvas.drawText(helpText, squareWidth * 1.05f, squareWidth * 0.85f, paint)
                }
            }
        }
    }

    private fun helpersToString(helpSets: List<List<Int>>): String {
        val builder = StringBuilder()
        for (digitList in helpSets) {
            if (builder.isNotEmpty()) {
                builder.append(" ")
            }
            for (digit in digitList) {
                builder.append(digit.toString())
            }
        }
        return builder.toString()
    }

    private fun splitHelperGroupString(helpText: String): List<String> {
        val list = mutableListOf<String>()
        val helpGroups = helpText.split(" ")
        val groupsHalfCount = ceil(0.5f * helpGroups.size).toInt()
        val line1Text = StringBuilder()
        val line2Text = StringBuilder()
        helpGroups.forEachIndexed { index, groupText ->
            if (index < groupsHalfCount) {
                if (line1Text.isNotEmpty()) { line1Text.append(" ") }
                line1Text.append(groupText)
            } else {
                if (line2Text.isNotEmpty()) { line2Text.append(" ") }
                line2Text.append(groupText)
            }
        }

        list.add(line1Text.toString())
        list.add(line2Text.toString())

        return list
    }

    private fun drawSelectionSquare(x: Float, y: Float, canvas: Canvas, paint: Paint) {
        canvas.drawRect(x, y, x + squareWidth, y + squareWidth, paint)
    }

    private fun drawGuessSquare(index : Int, content: String, possiblesString: String?, selected: Boolean,
                                visible: Boolean, error: Boolean, flash: Boolean, solved: Boolean,
                                addTouchAreas: Boolean, x: Float, y: Float, canvas: Canvas, paint: Paint,
                                alpha: Int
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
        paint.alpha = alpha

        if (flash) {
            paint.color = Color.CYAN
        } else {
            if (solved) {
                paint.color = Color.GRAY
                paint.alpha = alpha
            } else {
                if (selected) {
                    paint.color = Color.WHITE
                    paint.alpha = alpha
                }
            }
        }
        canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )

        if (content != "0") {
            // Display the player's guess.
            paint.color = Color.BLUE
            paint.alpha = alpha
            if (error) {
                paint.color = Color.RED
                paint.alpha = alpha
            }
            if (flash) {
                paint.textSize = squareTextSize * flashIndexRatio
            } else {
                paint.textSize = squareTextSize
            }
            canvas.drawText(content, x + squareWidth * 0.31f, y + squareWidth * 0.75f, paint)
        }

        if (possiblesString != null) {
            paint.textSize = possiblesTextSize
            paint.color = Color.BLACK
            paint.alpha = alpha

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
        paint.alpha = 255
    }

    private fun drawSquareBorder(x: Float, y: Float, canvas: Canvas, paint: Paint, alpha: Int) {
        paint.color = colourSquareBorder
        paint.alpha = alpha
        paint.strokeWidth = borderThickness
        canvas.drawLine(x, y, x + squareWidth, y, paint )
        canvas.drawLine(x, y, x, y + squareWidth, paint )
        canvas.drawLine(x, y + squareWidth, x + squareWidth, y + squareWidth, paint )
        canvas.drawLine(x + squareWidth, y, x + squareWidth, y + squareWidth, paint )
        paint.alpha = 255
    }

    private fun drawBlankSquare(x: Float, y: Float, canvas: Canvas, paint: Paint, alpha: Int) {
        paint.color = colourNonPlaySquareInside
        paint.alpha = alpha

        canvas.drawRect(x, y,x + squareWidth, y + squareWidth, paint )
        paint.alpha = 255
    }

    private fun drawDownHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint, alpha: Int) {
        paint.color = Color.LTGRAY
        paint.alpha = alpha
        paint.strokeWidth = squareWidth * 0.02f

        canvas.drawLine(x + slashMargin, y - squareWidth + slashMargin, x + squareWidth - slashMargin, y - slashMargin, paint )

        paint.textSize = squareTextSize * 0.45f
        canvas.drawText(hintString, x + squareWidth * 0.18f, y + squareWidth * 0.85f - squareWidth, paint)
        paint.alpha = 255
    }

    private fun drawAcrossHint(hintString: String, x: Float, y: Float, canvas: Canvas, paint: Paint, alpha: Int) {
        paint.color = Color.LTGRAY
        paint.alpha = alpha
        paint.strokeWidth = squareWidth * 0.02f

        canvas.drawLine(x + slashMargin - squareWidth, y + slashMargin, x - slashMargin, y - slashMargin + squareWidth, paint )

        paint.textSize = squareTextSize * 0.45f
        canvas.drawText(hintString, x + squareWidth * 0.56f  - squareWidth, y + squareWidth * 0.45f, paint)
        paint.alpha = 255
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

        const val GRID_TOP_MARGIN_HELP = 2.0f
        const val GRID_BOTTOM_MARGIN = 0.8f
        const val GRID_LEFT_MARGIN_HELP = 2.0f
        const val GRID_RIGHT_MARGIN = 0.8f
        const val MAX_DISPLAY_ROWS = 14
        const val MIN_DISPLAY_ROWS = 5
    }
}