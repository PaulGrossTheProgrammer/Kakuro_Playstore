package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.shape.MaterialShapeDrawable
import kotlin.random.Random
import kotlin.random.nextInt

class KakuroGameplayActivity : AppCompatActivity() {

    var engine: GameEngine = GameEngine.get()
    var versionName = "Working..."

    @SuppressLint("ClickableViewAccessibility")  // Why do I need this??? Something to do with setOnTouchListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        versionName = "V" + packageManager.getPackageInfo(packageName, 0).versionName

        val packageInfo = applicationContext.packageManager.getPackageInfo(packageName, 0)

        val isTv = applicationContext.packageManager.hasSystemFeature("android.software.leanback_only")

        if (isTv) {
            Log.d(TAG, "TV DETECTED.")
            // According to Google policy, a TV App is only allowed to use landscape orientation.
            // (Yes, Google are very stupid. I walk past portrait-oriented TV screens almost every day. Outdoor advertisers use portrait mode all the time.)
            setContentView(R.layout.activity_kakurogameplay_landscape)
        } else {
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                setContentView(R.layout.activity_kakurogameplay)
            } else {
                setContentView(R.layout.activity_kakurogameplay_landscape)
            }
        }
        setupTvNav()

        findViewById<TextView>(R.id.textViewVersion).text = versionName
    }

    var spriteDisplay: SpriteDisplay? = null

    override fun onResume() {
        super.onResume()
        println("#### Activity onResume()")
        engine = GameEngine.activate(KakuroGameplayDefinition, this)

        enableCallbackMessages()

        engine.queueMessageFromActivity(GameEngine.Message("RequestStateChanges"), ::queueCallbackMessage)

        engine.resumeTimingServer()
        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.setSizeChangedCallback(::spriteDisplaySizeChangedCallback)

        // From a full start, playGridView.height and playGridView.width are both zero, otherwise they have coherent values.
        if (playGridView.width != 0 && playGridView.height != 0) {
            println("#### onResume() - starting a new spritedisplay.")
            startSpriteDisplay(playGridView.width, playGridView.height)
        }

    }

    private fun spriteDisplaySizeChangedCallback(width: Int, height: Int) {
        startSpriteDisplay(width, height)
    }

    private fun startSpriteDisplay(width: Int, height: Int) {
        val timingServer = engine.getTimingServer()
        if (timingServer != null) {
            spriteDisplay = SpriteDisplay(Dimensions(width, height), timingServer,50, ::sendDrawCallbacksToGrid)
            spriteDisplay?.startSpriteDisplayLoop()
        }

/*        // FIXME: TEST ONLY
//        val sprite = AnimatedSparkle(applicationContext.resources)
        val sparkleSpriteBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.sparkle)
        val sprite1 = AnimatedFramesSprite(sparkleSpriteBitmap, 8, 4, listOf(0,1,2,3, 8,9,10,11, 16,17,18,19, 24,25,26,27))
        val sprite2 = AnimatedFramesSprite(sparkleSpriteBitmap, 8, 4, listOf(4,5,6,7, 12,13,14,15, 20,21,22,23, 28,29,30,31))

        val explosionSpriteSheetBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.explosion1)
        val sprite3 = AnimatedFramesSprite(explosionSpriteSheetBitmap, 4, 4)


        val redStarSpriteSheetBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.staroutareaelipsered)
        val sprite4 = RotatingSprite(redStarSpriteSheetBitmap, 5, 8)

        spriteDisplay?.addSprite(sprite3, "TEST", start = true)*/
        val explosionSpriteSheetBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.explosion1)
        val spriteBitmap = SpriteSheetBitmap(explosionSpriteSheetBitmap, 4, 4)

        if (spriteDisplay != null) {
            val sprite5 = FramesetSprite("Explosion", spriteBitmap, spriteDisplay!!.getPeriod(), duration = 5000)
            sprite5.setPosition(Position(0.5f * spriteDisplay!!.getContainerDimensions().width, 0.5f * spriteDisplay!!.getContainerDimensions().height))
            spriteDisplay!!.addSprite(sprite5, "TEST", start = true)
        }
    }

    class RotatingSprite(var bitmap: Bitmap, var cols: Int, var rows: Int): AnimatedFramesSprite(bitmap, cols, rows) {
        val rotateMatrix = Matrix()
        var rotateAngle = 0f
        val rotateStep = 3f
        var rotatedBitmap: Bitmap? = null

        override fun animateCallback(message: GameEngine.Message) {
            super.animateCallback(message)

            val currFrame = frameArray[currFrame]
            rotateAngle += rotateStep
            rotateMatrix.setRotate(rotateAngle,0.5f * currFrame.width,0.5f * currFrame.height)

            // Cache the rotated matrix for draw
            rotatedBitmap = Bitmap.createBitmap(currFrame, 0, 0, currFrame.width, currFrame.height, rotateMatrix, true)
        }

        override fun spriteDrawCallback(canvas: Canvas) {
            if (rotatedBitmap != null) {
                canvas.drawBitmap(rotatedBitmap!!, getPosition().xPos, getPosition().yPos, paint)
            }
        }
    }

    // FIXME: Backgrounding the app leaves the animation stars frozen, then adds more moving ones.
    // FIXME: There seems to be a subtle bug where backgronding the app doesn't work properly.
    // The symptom is the next time the back button is pressed, the app stops, but then restarts. Then a second press stops it properly.
    // https://www.geeksforgeeks.org/activity-lifecycle-in-android-with-demo-app/
    override fun onPause() {
        super.onPause()
        println("#### Activity onPause()")
        engine.queueMessageFromActivity(GameEngine.Message("RequestStopStateChanges"), ::queueCallbackMessage)
        engine.pauseTimingServer()

        if (spriteDisplay != null) {
            spriteDisplay?.stopSpriteDisplayLoop()
            spriteDisplay = null
        }

        stopGameServer()
    }

    fun onClickSettings(view: View) {
        engine.gotoSettingsActivity(this)
    }

    private var gameState: KakuroGameplayDefinition.StateVariables? = null

    private var displayingSolvedAnimation = false;


    /**
     * Update the custom playGridView with the new state and request a redraw.
     */
    private fun displayGrid(newestGameState: KakuroGameplayDefinition.StateVariables) {
        val prevKey = gameState?.puzzleKey
        gameState = newestGameState
        val newKey = gameState?.puzzleKey

        if (prevKey != newKey) {
            // Whenever the puzzle changes, send an additional request for the helper combinations sets.
            engine.queueMessageFromActivity(GameEngine.Message("RequestHelperSets"), ::queueCallbackMessage)
        }

        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        if (gameState!!.solved) {
            playGridView.clearSelectedIndex()
        }

        // Manage animation to celebrate the user finding a solution.
        if (gameState!!.solved) {
            if (!displayingSolvedAnimation) {
                engine.requestPeriodicEvent(::createDelayedRandomStar, "SolvedAnimationStar", 300)
                displayingSolvedAnimation = true
            }
        } else {
            if (displayingSolvedAnimation) {
                displayingSolvedAnimation = false
                engine.cancelEventsByType("SolvedAnimationStar")
            }
        }

        if (checkForSolved == true) {
            if (gameState!!.solved) {
                val sprite = AnimatedMessage("WINNER!",60f, 1.02f,)
                spriteDisplay?.addSprite(sprite, "Messages", start = true)
            }
            checkForSolved = false
        }

        playGridView.setGameState(newestGameState)
    }

    fun updateGridHelpSets(downHelpSets: HelpSets, acrossHelpSets: HelpSets) {
        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.setHelpSets(downHelpSets, acrossHelpSets)
    }

    /*
        Animation classes and functions:
     */

    /**
     * This callback function is needed by the SpriteDisplay to notify the GridView of any visible changes to the sprites.
     */
    private fun sendDrawCallbacksToGrid(callbackArray: Array<DoesDraw>) {
        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.updateDrawingCallbacks(callbackArray)
        playGridView.invalidate()
    }

    private fun createDelayedRandomStar(message: GameEngine.Message) {
        val randomDelay = Random.nextInt(200 .. 600)
        engine.requestDelayedEvent(::createRandomStar, "SolvedAnimationStar", randomDelay)
    }

    private fun createRandomStar(message: GameEngine.Message) {
        spriteDisplay?.addSprite(AnimatedStar(), "SolvedAnimationStar", start = true)
    }

    class AnimatedStar(): AnimatedSprite() {

        private val startPositionStarMatrix = Matrix()
        private val transformStarMatrix = Matrix()

        private val starPath: Path = Path()

        /**
         * This is the copy of starPath needed by the thread that calls onDraw().
         * For Thread safety, changes starPath have to be isolated from the Thread calling onDraw().
         */
        private var starPathForOnDraw: Path = Path()

        private val starPaint = Paint()
        private var starScale = 10f

        init{
            starPaint.color = Color.WHITE

            // TODO: Move this to setContainerDimensionsCallback
            starPath.moveTo(-starScale, -starScale)
            starPath.lineTo(0f, starScale)
            starPath.lineTo(starScale, -starScale)
            starPath.lineTo(0f, 0f)
            starPath.lineTo(-starScale, -starScale)
            starPath.lineTo(0f, 0f)
            starPath.lineTo(starScale, starScale)
            starPath.lineTo(0f, -starScale)
            starPath.lineTo(-starScale, starScale)
            starPath.lineTo(0f, 0f)
        }

        private var containerDimensions: Dimensions? = null

        override fun setContainerDimensionsCallback(dimensions: Dimensions) {
            containerDimensions = dimensions
            starScale = containerDimensions!!.width * 0.01f

            // TODO - build startPositionStarMatrix and transformStarMatrix here.
            // And Need to adjust if the screen changes size after sprite is moving too.
        }

        override fun startAnimation(timingServer: GameEngine.TimingServer) {
            if (containerDimensions == null) {
                return
            }

            // Set the initial position.
            startPositionStarMatrix.setTranslate(containerDimensions!!.width * (0.7f * Random.nextFloat()) + 0.15f, containerDimensions!!.height * (0.7f * Random.nextFloat()) + 0.15f)
            starPath.transform(startPositionStarMatrix)
            starPathForOnDraw = Path(starPath)  // So onDraw() can see the change.

            // Set the speed
            transformStarMatrix.setTranslate(containerDimensions!!.width * 0.015f * (Random.nextFloat() -0.5f), containerDimensions!!.width * 0.015f  * (Random.nextFloat() -0.5f))
            // TODO - Figure out how to rotate the star around its own axis. Likely use .preRotate() instead.
            val rotation = listOf(-3f, 0f, 3f).random()
            transformStarMatrix.postRotate(rotation, containerDimensions!!.width/2f, containerDimensions!!.height/2f)

            timingServer.addFinitePeriodicEvent(::animateCallback, "RandomStarAnimate", 50, 50)
        }

        override fun stopAnimation(timingServer: GameEngine.TimingServer) {
            // TODO - modify the TimerEvent class to stop() an animation and return a status message,
            //  which can be used to later resume the animation where it left off, instead of calling cancel here.
            timingServer.cancelEventsByType("RandomStarAnimate")
        }

        override fun resumeAnimation(timingServer: GameEngine.TimingServer) {
            // TODO - when stopAnimation returns an in-progress message,
            //  pass the message to the Timer system's resume() function, instead of just calling start here.
            startAnimation(timingServer)
        }

        override fun animateCallback(message: GameEngine.Message) {
            if (message.hasString("done")) {
                setDone()
            } else {
                starPath.transform(transformStarMatrix)

                // For thread safety, copy the Path so that doDraw() can use it.
                starPathForOnDraw = Path(starPath)
            }
            setDrawRequired()
        }

        override fun spriteDrawCallback(canvas: Canvas) {
            // For thread safety, only draw with the latest copy of starPath in starPathForOnDraw.
            canvas.drawPath(starPathForOnDraw, starPaint)
        }

    }

    class AnimatedSparkle(resources: Resources): AnimatedSprite() {
        // https://opengameart.org/content/sparkles

        private val paint = Paint()
        private var xPos = 0f
        private var yPos = 0f
        private var frameArray: Array<Bitmap> = arrayOf()
        private var currFrame = 0

        private var sparkleSpriteBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.sparkle)

        init {
            frameArray = loadFrames(sparkleSpriteBitmap, 8, 4, listOf(0,1,2,3, 8,9,10,11, 16,17,18,19, 24,25,26,27))
        }

        override fun setContainerDimensionsCallback(dimensions: Dimensions) {
            xPos = 0.5f * dimensions.width - 0.5f * frameArray[0].width
            yPos = 0.5f * dimensions.height - 0.5f * frameArray[0].height
        }

        override fun startAnimation(timingServer: GameEngine.TimingServer) {
            timingServer.addFinitePeriodicEvent(::animateCallback, "AnimatedMessage", 50, 100)
        }

        override fun stopAnimation(timingServer: GameEngine.TimingServer) {
        }

        override fun resumeAnimation(timingServer: GameEngine.TimingServer) {
        }

        /**
         * Callback needed by the TimingServer Thread for every frame of animation.
         */
        override fun animateCallback(message: GameEngine.Message) {
            if (message.hasString("done")) {
                setDone()
            } else {
                currFrame++
                if (currFrame >= frameArray.size) {
                    currFrame = 0
                }
            }
            setDrawRequired()
        }

        /**
         * Callback needed by the Android UI Thread
         */
        override fun spriteDrawCallback(canvas: Canvas) {
            canvas.drawBitmap(frameArray[currFrame], xPos, yPos, paint)
        }
    }

    // TODO - add args for screen dimensions, duration. Also relative x, y position.
    class AnimatedMessage(private val message: String, private val size: Float, private val growthRate: Float): AnimatedSprite() {

        private val textPaint = Paint()
        private var xPos = 0f
        private var yPos = 0f

        init {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.WHITE
            textPaint.textSize = size
        }

        private var containerDimensions: Dimensions? = null

        override fun setContainerDimensionsCallback(dimensions: Dimensions) {
            containerDimensions = dimensions
            xPos = 0.5f * containerDimensions!!.width
            yPos = 0.5f * containerDimensions!!.height - 0.5f * (textPaint.descent() + textPaint.ascent())
        }

        override fun startAnimation(timingServer: GameEngine.TimingServer) {
            if (containerDimensions != null) {
                // TODO - base the period and repeats on a new duration arg.
                timingServer.addFinitePeriodicEvent(::animateCallback, "AnimatedMessage", 50, 50)
            }
        }

        override fun stopAnimation(timingServer: GameEngine.TimingServer) {
        }

        override fun resumeAnimation(timingServer: GameEngine.TimingServer) {
        }

        override fun animateCallback(message: GameEngine.Message) {
            if (message.hasString("done")) {
                setDone()
            } else {
                textPaint.textSize *= growthRate
                // TODO - Fade text away. Use "repeat" count in message to trigger
            }
            setDrawRequired()
        }

        override fun spriteDrawCallback(canvas: Canvas) {
            canvas.drawText(message, xPos, yPos, textPaint)
        }
    }


    //
    // Controller handling: D-pad used by Google TV
    //
    //  https://developer.android.com/training/tv/start/navigation
    //  https://developer.android.com/training/game-controllers/controller-input
    //  https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input

    // The current control selected by the D-pad
    private var currSelectedView: View? = null

    // Backgrounds used to show where the D-pad cursor is located.
    private var textViewBackgroundSelected: MaterialShapeDrawable? = null
    private var generalBackgroundSelected: Drawable? = null

    private var gridView: PlayingGridView? = null

    private var digit1View: View? = null
    private var digit2View: View? = null
    private var digit3View: View? = null
    private var digit4View: View? = null
    private var digit5View: View? = null
    private var digit6View: View? = null
    private var digit7View: View? = null
    private var digit8View: View? = null
    private var digit9View: View? = null

    private var possible1View: View? = null
    private var possible2View: View? = null
    private var possible3View: View? = null
    private var possible4View: View? = null
    private var possible5View: View? = null
    private var possible6View: View? = null
    private var possible7View: View? = null
    private var possible8View: View? = null
    private var possible9View: View? = null

    private var digitClearView: View? = null

    private var zoomInView: View? = null
    private var zoomOutView: View? = null

    private var undoView: View? = null
    private var redoView: View? = null

    private var toggleHelpView: View? = null

    private var settingsView: View? = null
    private var resetView: View? = null
    private var prevPuzzleView: View? = null
    private var nextPuzzleView: View? = null

    private data class NavCmd(
        val view: View,
        val direction: NavDirection
    )

    enum class NavDirection{
        CURSOR_UP, CURSOR_DOWN, CURSOR_LEFT, CURSOR_RIGHT
    }

    private val dpadNavLookup: MutableMap<NavCmd, View> = mutableMapOf()

    private fun setupTvNav() {
        // TODO: make strokeWidth relative. How to get the whole screen width???
        // Maybe I wait for the next full execution of the playing grid's setScreenSizes()

        val strokeWidth = 4.0f
        textViewBackgroundSelected = MaterialShapeDrawable()
        (textViewBackgroundSelected as MaterialShapeDrawable).fillColor = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
        (textViewBackgroundSelected as MaterialShapeDrawable).setStroke(strokeWidth, ContextCompat.getColor(this, R.color.white))

        generalBackgroundSelected = MaterialShapeDrawable()
        (generalBackgroundSelected as MaterialShapeDrawable).setStroke(strokeWidth, ContextCompat.getColor(this, R.color.white))

        gridView = findViewById(R.id.viewPlayGrid)

        if (gridView!!.getSelectedIndex() == -1) {
            gridView!!.setIndexToDefault()
        }
        currSelectedView = gridView
        navOnto(gridView)

        digit1View = findViewById(R.id.textViewDigit1)
        digit2View = findViewById(R.id.textViewDigit2)
        digit3View = findViewById(R.id.textViewDigit3)
        digit4View = findViewById(R.id.textViewDigit4)
        digit5View = findViewById(R.id.textViewDigit5)
        digit6View = findViewById(R.id.textViewDigit6)
        digit7View = findViewById(R.id.textViewDigit7)
        digit8View = findViewById(R.id.textViewDigit8)
        digit9View = findViewById(R.id.textViewDigit9)

        possible1View = findViewById(R.id.textViewPossible1)
        possible2View = findViewById(R.id.textViewPossible2)
        possible3View = findViewById(R.id.textViewPossible3)
        possible4View = findViewById(R.id.textViewPossible4)
        possible5View = findViewById(R.id.textViewPossible5)
        possible6View = findViewById(R.id.textViewPossible6)
        possible7View = findViewById(R.id.textViewPossible7)
        possible8View = findViewById(R.id.textViewPossible8)
        possible9View = findViewById(R.id.textViewPossible9)

        digitClearView = findViewById(R.id.textViewDigitClear)

        zoomInView = findViewById(R.id.imageButtonZoomIn)
        zoomOutView = findViewById(R.id.imageButtonZoomOut)

        undoView = findViewById(R.id.imageButtonUndo)
        redoView = findViewById(R.id.imageButtonRedo)

        toggleHelpView = findViewById(R.id.imageButtonToggleHelp)

        settingsView = findViewById(R.id.imageButtonSettings)
        resetView = findViewById(R.id.textViewReset)
        nextPuzzleView = findViewById(R.id.textViewNextPuzzle)
        prevPuzzleView = findViewById(R.id.textViewPrevPuzzle)

        // Row 1 navigation - digits for guesses and possibles.

        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_RIGHT)] = digit2View!!
        dpadNavLookup[NavCmd(digit1View!!, NavDirection.CURSOR_DOWN)] = digit4View!!

        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_LEFT)] = digit1View!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_RIGHT)] = digit3View!!
        dpadNavLookup[NavCmd(digit2View!!, NavDirection.CURSOR_DOWN)] = digit5View!!

        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_LEFT)] = digit2View!!
        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_RIGHT)] = possible1View!!
        dpadNavLookup[NavCmd(digit3View!!, NavDirection.CURSOR_DOWN)] = digit6View!!

        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_UP)] = digit1View!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_RIGHT)] = digit5View!!
        dpadNavLookup[NavCmd(digit4View!!, NavDirection.CURSOR_DOWN)] = digit7View!!

        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_UP)] = digit2View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_LEFT)] = digit4View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_RIGHT)] = digit6View!!
        dpadNavLookup[NavCmd(digit5View!!, NavDirection.CURSOR_DOWN)] = digit8View!!

        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_UP)] = digit3View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_LEFT)] = digit5View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_RIGHT)] = possible4View!!
        dpadNavLookup[NavCmd(digit6View!!, NavDirection.CURSOR_DOWN)] = digit9View!!

        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_UP)] = digit4View!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_RIGHT)] = digit8View!!
        dpadNavLookup[NavCmd(digit7View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_UP)] = digit5View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_LEFT)] = digit7View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_RIGHT)] = digit9View!!
        dpadNavLookup[NavCmd(digit8View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_UP)] = digit6View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_LEFT)] = digit8View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_RIGHT)] = possible7View!!
        dpadNavLookup[NavCmd(digit9View!!, NavDirection.CURSOR_DOWN)] = digitClearView!!

        dpadNavLookup[NavCmd(possible1View!!, NavDirection.CURSOR_LEFT)] = digit3View!!
        dpadNavLookup[NavCmd(possible1View!!, NavDirection.CURSOR_RIGHT)] = possible2View!!
        dpadNavLookup[NavCmd(possible1View!!, NavDirection.CURSOR_DOWN)] = possible4View!!

        dpadNavLookup[NavCmd(possible2View!!, NavDirection.CURSOR_LEFT)] = possible1View!!
        dpadNavLookup[NavCmd(possible2View!!, NavDirection.CURSOR_RIGHT)] = possible3View!!
        dpadNavLookup[NavCmd(possible2View!!, NavDirection.CURSOR_DOWN)] = possible5View!!

        dpadNavLookup[NavCmd(possible3View!!, NavDirection.CURSOR_LEFT)] = possible2View!!
        dpadNavLookup[NavCmd(possible3View!!, NavDirection.CURSOR_DOWN)] = possible6View!!

        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_UP)] = possible1View!!
        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_LEFT)] = digit6View!!
        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_RIGHT)] = possible5View!!
        dpadNavLookup[NavCmd(possible4View!!, NavDirection.CURSOR_DOWN)] = possible7View!!

        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_UP)] = possible2View!!
        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_LEFT)] = possible4View!!
        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_RIGHT)] = possible6View!!
        dpadNavLookup[NavCmd(possible5View!!, NavDirection.CURSOR_DOWN)] = possible8View!!

        dpadNavLookup[NavCmd(possible6View!!, NavDirection.CURSOR_LEFT)] = possible5View!!
        dpadNavLookup[NavCmd(possible6View!!, NavDirection.CURSOR_UP)] = possible3View!!
        dpadNavLookup[NavCmd(possible6View!!, NavDirection.CURSOR_DOWN)] = possible9View!!

        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_UP)] = possible4View!!
        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_LEFT)] = digit9View!!
        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_RIGHT)] = possible8View!!
        dpadNavLookup[NavCmd(possible7View!!, NavDirection.CURSOR_DOWN)] = undoView!!

        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_UP)] = possible5View!!
        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_LEFT)] = possible7View!!
        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_RIGHT)] = possible9View!!
        dpadNavLookup[NavCmd(possible8View!!, NavDirection.CURSOR_DOWN)] = undoView!!

        dpadNavLookup[NavCmd(possible9View!!, NavDirection.CURSOR_UP)] = possible6View!!
        dpadNavLookup[NavCmd(possible9View!!, NavDirection.CURSOR_LEFT)] = possible8View!!
        dpadNavLookup[NavCmd(possible9View!!, NavDirection.CURSOR_DOWN)] = redoView!!

        // Row 2 navigation - digit clear.

        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_UP)] = digit8View!!
        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_RIGHT)] = possible7View!!
        dpadNavLookup[NavCmd(digitClearView!!, NavDirection.CURSOR_DOWN)] = zoomInView!!

        // Row 3 navigation - zoom in/out and undo.

        dpadNavLookup[NavCmd(zoomInView!!, NavDirection.CURSOR_UP)] = digitClearView!!
        dpadNavLookup[NavCmd(zoomInView!!, NavDirection.CURSOR_RIGHT)] = zoomOutView!!
        dpadNavLookup[NavCmd(zoomInView!!, NavDirection.CURSOR_DOWN)] = resetView!!

        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_UP)] = digitClearView!!
        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_LEFT)] = zoomInView!!
        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_RIGHT)] = undoView!!
        dpadNavLookup[NavCmd(zoomOutView!!, NavDirection.CURSOR_DOWN)] = prevPuzzleView!!

        dpadNavLookup[NavCmd(undoView!!, NavDirection.CURSOR_UP)] = possible8View!!
        dpadNavLookup[NavCmd(undoView!!, NavDirection.CURSOR_LEFT)] = zoomOutView!!
        dpadNavLookup[NavCmd(undoView!!, NavDirection.CURSOR_RIGHT)] = redoView!!
        dpadNavLookup[NavCmd(undoView!!, NavDirection.CURSOR_DOWN)] = toggleHelpView!!

        dpadNavLookup[NavCmd(redoView!!, NavDirection.CURSOR_UP)] = possible9View!!
        dpadNavLookup[NavCmd(redoView!!, NavDirection.CURSOR_LEFT)] = undoView!!
        dpadNavLookup[NavCmd(redoView!!, NavDirection.CURSOR_DOWN)] = settingsView!!

        // Row 4 navigation - reset, puzzle control, help and settings.

        dpadNavLookup[NavCmd(resetView!!, NavDirection.CURSOR_UP)] = zoomInView!!
        dpadNavLookup[NavCmd(resetView!!, NavDirection.CURSOR_RIGHT)] = prevPuzzleView!!

        dpadNavLookup[NavCmd(prevPuzzleView!!, NavDirection.CURSOR_UP)] = zoomOutView!!
        dpadNavLookup[NavCmd(prevPuzzleView!!, NavDirection.CURSOR_LEFT)] = resetView!!
        dpadNavLookup[NavCmd(prevPuzzleView!!, NavDirection.CURSOR_RIGHT)] = nextPuzzleView!!

        dpadNavLookup[NavCmd(nextPuzzleView!!, NavDirection.CURSOR_UP)] = zoomOutView!!
        dpadNavLookup[NavCmd(nextPuzzleView!!, NavDirection.CURSOR_LEFT)] = prevPuzzleView!!
        dpadNavLookup[NavCmd(nextPuzzleView!!, NavDirection.CURSOR_RIGHT)] = toggleHelpView!!

        dpadNavLookup[NavCmd(toggleHelpView!!, NavDirection.CURSOR_UP)] = undoView!!
        dpadNavLookup[NavCmd(toggleHelpView!!, NavDirection.CURSOR_LEFT)] = nextPuzzleView!!
        dpadNavLookup[NavCmd(toggleHelpView!!, NavDirection.CURSOR_RIGHT)] = settingsView!!

        dpadNavLookup[NavCmd(settingsView!!, NavDirection.CURSOR_UP)] = redoView!!
        dpadNavLookup[NavCmd(settingsView!!, NavDirection.CURSOR_LEFT)] = toggleHelpView!!
    }

    /**
     * This turns D-pad commands into navigation direction commands.'
     *
     * https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input
     */
    private fun getNavDirection(event: KeyEvent): NavDirection? {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return NavDirection.CURSOR_UP
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return NavDirection.CURSOR_DOWN
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return NavDirection.CURSOR_LEFT
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return NavDirection.CURSOR_RIGHT
            }
        }
        return null
    }

    /**
     * This handles the TV navigation controller's commands.
     *
     * The standard remote control buttons, as well as a game-pad, are supported here.
     *
     * https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        var backPressed = false
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            backPressed = true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A && event.action == KeyEvent.ACTION_DOWN) {
            backPressed = true
        }

        if (backPressed) {
            // NOTE: TV apps are not permitted to "gate" the back button from the main screen.
            // The back button must always return the TV "Live" page, so exitApp() is called.
            if (currSelectedView == gridView) {
                exitApp()
            } else {
                navAwayFrom(currSelectedView)
                currSelectedView = gridView
                navOnto(gridView)
            }
            return true
        }

        if (currSelectedView == null) {
            currSelectedView = gridView
            navOnto(currSelectedView)
        }

        var selectPressed = false
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
            selectPressed = true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_X && event.action == KeyEvent.ACTION_DOWN) {
            selectPressed = true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL && event.action == KeyEvent.ACTION_DOWN) {
            selectPressed = true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR && event.action == KeyEvent.ACTION_DOWN) {
            selectPressed = true
        }

        if (selectPressed) {
            if (currSelectedView == gridView) {
                navAwayFrom(gridView)
                currSelectedView = digit5View
                navOnto(currSelectedView)
            } else {
                val index = gridView!!.getSelectedIndex()
                if (index == -1) {
                    gridView!!.setIndexToDefault()
                }
                if (currSelectedView != null) {
                    currSelectedView!!.callOnClick()

                    // A puzzle reset will automatically switch back to the grid.
                    if (currSelectedView == findViewById(R.id.textViewReset)) {
                        navAwayFrom(currSelectedView)
                        currSelectedView = gridView
                        navOnto(gridView)
                    }

                    // A guess digit will automatically switch back to the grid.
                    if (currSelectedView!!.tag != null) {
                        val tag = currSelectedView!!.tag.toString()
                        if (tag.startsWith("Guess") && tag != "Guess0") {
                            navAwayFrom(currSelectedView)
                            currSelectedView = gridView
                            navOnto(gridView)
                        }
                    }
                }
            }
        }

        val cursorDir = getNavDirection(event)
        if (cursorDir != null) {
            if (currSelectedView != gridView) {
                val targetView = dpadNavLookup[NavCmd(currSelectedView!!, cursorDir)]

                if (targetView != null) {
                    navAwayFrom(currSelectedView)
                    currSelectedView = targetView
                    navOnto(currSelectedView)
                }
            } else {
                gridView!!.navigateGrid(cursorDir)
            }
        }

        return true
    }

    /**
     * Cache for navigation highlight. The cache stores the original background
     * for when the View is no longer the navigation focus.
     */
    private val cachedBackgroundLookup: MutableMap<View, Drawable> = mutableMapOf()

    private fun navOnto(view: View?) {
        if (view == null) return

        if (view == gridView) {
            gridView!!.setDpadNavSelected()
            return
        }

        gridView!!.unsetDpadNavSelected()

        // Cache the original background so that we can set it back with navAwayFrom().
        val cachedBackground: Drawable? = cachedBackgroundLookup[view]
        if (cachedBackground == null) {
            cachedBackgroundLookup[view] = view.background
        }

        if (view is TextView) {
            view.background = textViewBackgroundSelected
        } else {
            view.background = generalBackgroundSelected
        }
    }

    private fun navAwayFrom(view: View?) {
        if (view == null) return

        if (view == gridView) {
            gridView!!.unsetDpadNavSelected()
        }

        val cachedBackground: Drawable? = cachedBackgroundLookup[view]
        if (cachedBackground != null) {
            view.background = cachedBackground
        }
    }


    //
    // User touch controls
    //

    fun onClickZoomIn(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).zoomGrid(-1)
    }

    fun onClickZoomOut(view: View) {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).zoomGrid(1)
    }

    private var checkForSolved = false


    fun onClickDigit(view: View) {
        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        val selectedIndex = playGridView.getSelectedIndex()
        if (selectedIndex == -1) {
            return
        }

        checkForSolved = true  // This flag is used by the message receiver to react to the change if required

        val tag = view.tag.toString()
        val value = tag.substringAfter("Guess")
        val message = GameEngine.Message("Guess")
        message.setKeyString("Index", selectedIndex.toString())
        message.setKeyString("Value", value)
        engine.queueMessageFromActivity(message, ::queueCallbackMessage)
    }

    fun onClickPossibleDigit(view: View) {
        val tag = view.tag.toString()

        val selectedIndex = findViewById<PlayingGridView>(R.id.viewPlayGrid).getSelectedIndex()
        if (selectedIndex == -1) {
            return
        }

        val value = tag.substringAfter("Possible")

        val message = GameEngine.Message("Possible")
        message.setKeyString("Index", selectedIndex.toString())
        message.setKeyString("Value", value)
        engine.queueMessageFromActivity(message, ::queueCallbackMessage)
    }

    fun onClickReset(view: View) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Restart Puzzle")
        builder.setMessage("Are you sure you want to restart?")
        builder.setPositiveButton("Reset") { _, _ ->
            findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
            engine.queueMessageFromActivity(GameEngine.Message("RestartPuzzle"), ::queueCallbackMessage)
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    fun onClickUndo(view: View) {
        val message = GameEngine.Message("Undo")
        engine.queueMessageFromActivity(message, ::queueCallbackMessage)
    }

    fun onClickRedo(view: View) {
        val message = GameEngine.Message("Redo")
        engine.queueMessageFromActivity(message, ::queueCallbackMessage)
    }

    fun onClickPrevPuzzle(view: View) {
        prevPuzzle()
    }

    fun onClickNextPuzzle(view: View) {
        nextPuzzle()
    }

    private fun prevPuzzle() {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
        engine.queueMessageFromActivity(GameEngine.Message("PrevPuzzle"), ::queueCallbackMessage)
    }

    private fun nextPuzzle() {
        findViewById<PlayingGridView>(R.id.viewPlayGrid).resetOptions()
        engine.queueMessageFromActivity(GameEngine.Message("NextPuzzle"), ::queueCallbackMessage)
    }

    fun onClickToggleShowHelp(view: View) {
        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
        playGridView.toggleShowHelp()
    }

    private fun exitApp() {
        // FIXME - this needs to be called when the app is backgrounded too  ...
//        stopGameServer()

        finishAndRemoveTask()
    }

    private fun stopGameServer() {
        engine.queueMessageFromActivity(GameEngine.Message("StopGame"), ::queueCallbackMessage)
    }

    fun onClickGotoSettings(view: View) {
        engine.gotoSettingsActivity(this)
    }

    /**
     * This callback is called by the Engine's time server to animate the new digit.
     */
    private fun animateNewDigitCallback(message: GameEngine.Message) {
        val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)

        if (message.hasString("done")) {
            playGridView.flashIndex = -1
            playGridView.flashIndexRatio = 1.0f
        } else {
            // Reduce the digit flash size gradually...
            if (playGridView.flashIndexRatio > 1.0f) {
                playGridView.flashIndexRatio *= 0.96f
            } else {
                playGridView.flashIndexRatio = 1.0f
            }
        }
        playGridView.invalidate()
    }

    /**
     * Receive messages from the GameEngine.
     */
    private val activityMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val messageString = intent.getStringExtra("Message") ?: return

            val message = GameEngine.Message.decodeMessage(messageString)
            if (message.type == "State") {
                val newState = engine.decodeState(message)
                if (newState is KakuroGameplayDefinition.StateVariables) {

                    // Check for animation request for new guesses.
                    if (message.hasString("gni")) {
                        val changedIndex = message.getString("gni")?.toInt()
                        if (changedIndex != null) {
                            // Cancel previous animation if it's still running.
                            engine.cancelEventsByType("AnimateNewDigit")
                            val playGridView = findViewById<PlayingGridView>(R.id.viewPlayGrid)
                            playGridView.flashIndex = changedIndex
                            playGridView.flashIndexRatio = 1.4f
                            engine.requestFinitePeriodicEvent(::animateNewDigitCallback, "AnimateNewDigit", period = 60, repeats = 6)                    }
                        }

                    displayGrid(newState)
                }
            }
            if (message.type == "HelperSets") {
                val downHelpSetString = message.getString("down").toString()
                val acrossHelpSetString = message.getString("across").toString()
                if (downHelpSetString.isNotEmpty() && acrossHelpSetString.isNotEmpty()) {
                    val downHelpSets = decodeHelpSet(downHelpSetString)
                    val acrossHelpSets = decodeHelpSet(acrossHelpSetString)
                    updateGridHelpSets(downHelpSets, acrossHelpSets)
                } else {
                    println("Empty HelpSets - skipping update.")
                }
            }
        }
    }

    private var queuedMessageAction: String = "$TAG.activity.MESSAGE"

    private fun enableCallbackMessages() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(queuedMessageAction)
        registerReceiver(activityMessageReceiver, intentFilter)
    }

    /**
     * This is the CALLBACK function to be used when a message needs to be queued for this Activity.
     */
    private fun queueCallbackMessage(message: GameEngine.Message) {
        // For this CALLBACK to be used, enableQueuedMessages() must be executed at startup.
        // The UI thread will then call activityMessageReceiver() to handle each message.
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("Message", message.asString())
        sendBroadcast(intent)
    }

    companion object {
        private val TAG = KakuroGameplayActivity::class.java.simpleName
  }
}