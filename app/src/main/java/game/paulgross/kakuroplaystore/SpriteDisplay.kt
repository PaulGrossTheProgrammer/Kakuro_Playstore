package game.paulgross.kakuroplaystore

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.reflect.KFunction1


fun loadFrames(bitmap: Bitmap, cols: Int, rows: Int, indexList: List<Int>?): Array<Bitmap> {
    val sheetWidth = bitmap.width
    val sheetHeight = bitmap.height
    val frameWidth = sheetWidth.div(cols)
    val frameHeight = sheetHeight.div(rows)

    val frames = mutableListOf<Bitmap>()

    var index = 0
    var yCurr = 0
    var currRow = 0
    while (currRow < rows) {
        var xCurr = 0
        var currCol = 0
        while (currCol < cols) {
            if (indexList == null || indexList.contains(index)) {
                val resizedBmp = Bitmap.createBitmap(bitmap, xCurr, yCurr, frameWidth, frameHeight)
                frames.add(resizedBmp)
            }
            xCurr += frameWidth
            currCol++
            index++
        }
        yCurr += frameHeight
        currRow++
    }

    return frames.toTypedArray()
}

data class ContainerDimensions(val height: Int, val width: Int)
data class Position(val xPos: Float, val yPos: Float)

interface DoesDraw {
    fun drawCallback(canvas: Canvas)
}

interface Sprite: DoesDraw {

    fun setContainerDimensionsCallback(dimensions: ContainerDimensions)

    fun isDone(): Boolean
    fun setDone()

    fun isVisible(): Boolean
    fun setVisibilityState(visibility: Boolean)

    fun isDrawRequired(): Boolean  // Maybe change this to isRedrawRequired()

    fun setDrawRequired()
    fun unsetDrawRequired()

    fun getPosition(): Position
    fun setPosition(newPos: Position)

    // TODO - maybe have a pause animation and a cancel animation

    fun startAnimation(timingServer: GameEngine.TimingServer)
    fun stopAnimation(timingServer: GameEngine.TimingServer)
    fun resumeAnimation(timingServer: GameEngine.TimingServer)
    fun animateCallback(message: GameEngine.Message)

    fun spriteDrawCallback(canvas: Canvas)

    /**
     * The animation Thread needs to call this function to draw the sprite.
     */
    override fun drawCallback(canvas: Canvas)
}

abstract class AnimatedSprite: Sprite {
    private var position: Position = Position(0f, 0f)

    private var requireDraw = true  // The initial state of the sprite needs to be drawn.
    private var done = false
    private var visible = true

    override fun setPosition(newPos: Position) {
        position = newPos
    }

    override fun getPosition(): Position {
        return position
    }

    final override fun isDone(): Boolean {
        return done
    }

    final override fun setDone() {
        done = true
    }

    final override fun isVisible(): Boolean {
        return visible
    }

    final override fun setVisibilityState(state: Boolean) {
        visible = state
    }

    /**
     * Call this function whenever the sprite has changed appearance in any way.
     */
    final override fun setDrawRequired() {
        requireDraw = true
    }

    final override fun unsetDrawRequired() {
        requireDraw = false
    }

    /**
     * If this returns True, then the animation thread knows that it can call doDraw() via drawCallback() on it's next cycle.
     * After the animation thread calls drawCallback(), isDrawRequired() returns False again.
     *
     * Note that the animator still might not call doDraw() on it's next cycle, for example if the sprite is hidden.
     */
    final override fun isDrawRequired(): Boolean {
        return requireDraw
    }

    /**
     * The animation thread calls this function if it needs to draw this sprite on the canvas via onDraw().
     * After onDraw(), isDrawRequired() returns False again.
     */
    final override fun drawCallback(canvas: Canvas) {
        if (!done && visible) {
            spriteDrawCallback(canvas)
            requireDraw = false
        }
    }


}

// TODO - create a SpriteBitmap class that has spritesheet capabilities, and can optionally cache rotated bitmaps.
// Include rotation functions.
// Include fade functions???
// Pass this SpriteBitmap as an arg to create Sprites.
// The SpriteBitmap is shared by all the sprites that reference it. So be careful if caching Bitmaps.

open class AnimatedFramesSprite(bitmap: Bitmap, cols: Int, rows: Int, indexList: List<Int>? = null): AnimatedSprite() {

    val paint = Paint()
    var frameArray: Array<Bitmap> = arrayOf()
    var currFrame = 0

    init {
        frameArray = loadFrames(bitmap, cols, rows, indexList)
    }

    override fun setContainerDimensionsCallback(dimensions: ContainerDimensions) {
        val newPos = Position(0.5f * dimensions.width - 0.5f * frameArray[0].width, 0.5f * dimensions.height - 0.5f * frameArray[0].height)
        setPosition(newPos)
    }

    override fun startAnimation(timingServer: GameEngine.TimingServer) {
        val type = "Sprite"
        val period = 50
//        val repeats = 100
//        timingServer.addFinitePeriodicEvent(::animateCallback, type, period, repeats)
        timingServer.addPeriodicEvent(::animateCallback, type, period)
    }

    override fun stopAnimation(timingServer: GameEngine.TimingServer){}
    override fun resumeAnimation(timingServer: GameEngine.TimingServer){}

    /**
     * Callback needed by the TimingServer Thread for every frame of animation.
     */
    override fun animateCallback(message: GameEngine.Message) {
        if (message.getString("done") == "true") {
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
        canvas.drawBitmap(frameArray[currFrame], getPosition().xPos, getPosition().yPos, paint)
    }
}

class SpriteDisplay(private var containerDimensions: ContainerDimensions, private val timingServer: GameEngine.TimingServer, private val period: Int, private val drawCallback: KFunction1<Array<DoesDraw>, Unit>) {

    // TODO - need to determine if I need a setDrawCallback() func, or if it's still OK in the constructor.
    // - because the View instance changes when the screen is rotated, and then there is the odd behaviour when the app is backgrounded...

    // TODO - do we need an update call for width and height?
    fun setContainerDimensions(containerDimensions: ContainerDimensions) {
        this.containerDimensions = containerDimensions

        // TODO - iterate allSprites and update with setWidthAndHeightCallback()
        // allSprites
        for (sprite in allSprites) {
            sprite.setContainerDimensionsCallback(this.containerDimensions)
        }
    }


    // TODO: Move start and stop animation loop here.
    fun startSpriteDisplayLoop() {
        timingServer.addPeriodicEvent(::periodicCheckCallback, "SpriteDisplayLoop", period)
    }

    fun stopSpriteDisplayLoop() {
        timingServer.cancelEventsByType("SpriteDisplayLoop")
    }
    // provide accessor methods for timers, so that this class can mediate the animation updates.

    private val allSprites = mutableListOf<Sprite>()

    /*
    ** Added Sprites have their setContainerDimensions() function called,
    ** and if start == true, then startAnimationCallback() is then called.
     */
    fun addSprite(sprite: Sprite, groupName: String, start: Boolean = false) {
        sprite.setContainerDimensionsCallback(containerDimensions)
        allSprites.add(sprite)
        if (start) {
            sprite.startAnimation(timingServer)
        }
        // TODO: create internal lists of sprites attached to groupNames.

        // If the groupname doesn't have a defined order, it gets drawn last.
    }

    fun startAnimation(groupName: String) {
        // TODO: Only animate sprites in groupName.
        for (sprite in allSprites) {
            sprite.startAnimation(timingServer)
        }
    }

    // TODO: for a static animation use stopAnimation() and hideGroup() then start/resumeAnimation() unhideGroup() to toggle visibility.
    // That way there are no redundant animation callbacks for temporarily unwanted animations.
    // And then we won;t need to keep creating Sprites, we will just switch them on and off as needed.

    fun stopAnimation(groupName: String) {
        // TODO - maybe the timer stops and sends a special message that allows the animation to be resumed where it was left off?
        // The instance will have to save the message and use it for resumeAnimation()
        for (sprite in allSprites) {
            sprite.stopAnimation(timingServer)
        }
    }

    fun resumeAnimation(groupName: String) {
        for (sprite in allSprites) {
            sprite.resumeAnimation(timingServer)
        }
    }

    fun hideGroup(groupName: String) {
        // TODO
    }

    fun unhideGroup() {
        // TODO
    }

    fun defineGroupPriority(orderedList: List<String>) {
        // Sets the drawing order of sprites.
    }

    private fun periodicCheckCallback(message: GameEngine.Message) {
        var anyChangedSprites = false
        val doneSprites = mutableListOf<Sprite>()
        for (sprite in allSprites) {
            if (sprite.isDone()) {
                doneSprites.add(sprite)
                if (sprite.isDrawRequired()) {
                    anyChangedSprites = true
                }
            }
        }
        allSprites.removeAll(doneSprites)

        // Go through all sprites and make a list of visible sprites in drawing order.
        // Also see if anything has changed.
        val drawList = mutableListOf<Sprite>()
        for (sprite in allSprites) {
            if (sprite.isVisible()) {
                drawList.add(sprite) // Would it be faster to use an Array??? Just shrink the Array later, when invoking the callback below???
                if (!anyChangedSprites && sprite.isDrawRequired()) {
                    anyChangedSprites = true
                }
            }
        }

        if (anyChangedSprites) {
            // Send a message to the View with all visible sprites.
            // Note that any previous drawList message should continue to be used to draw sprites until this callback is used.
            drawCallback.invoke(drawList.toTypedArray())
        }
    }
}