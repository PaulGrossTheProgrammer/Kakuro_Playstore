package game.paulgross.kakuroplaystore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.lang.reflect.Method
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

data class Dimensions(val height: Int, val width: Int)
data class Position(val xPos: Float, val yPos: Float)

interface DoesDraw {
    fun drawCallback(canvas: Canvas)
}

interface Sprite: DoesDraw {

    fun setContainerDimensionsCallback(dimensions: Dimensions)

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

interface SpriteBitmap {
    fun getDimensions(): Dimensions
    fun isCenter(): Boolean
    fun nextFrame()
    fun prevFrame()
    fun draw(canvas: Canvas, position: Position, paint: Paint)
}

class SpriteSingleBitmap(private val bitmap: Bitmap, private val center: Boolean = true): SpriteBitmap {
    private val dimensions: Dimensions = Dimensions(bitmap.width, bitmap.height)
    private val drawOffsetX: Float = when(center) { false -> 0f else -> -0.5f * bitmap.width }
    private val drawOffsetY: Float = when(center) { false -> 0f else -> -0.5f * bitmap.height }

    override fun getDimensions(): Dimensions {
        return dimensions
    }

    override fun isCenter(): Boolean {
        return center
    }

    override fun nextFrame() {}
    override fun prevFrame() {}

    override fun draw(canvas: Canvas, position: Position, paint: Paint) {
        canvas.drawBitmap(bitmap, position.xPos - drawOffsetX, position.yPos - drawOffsetY, paint)
    }
}

/**
 * Creates a sequence of bitmap frames from the original bitmap.
 * By default the frames are numbered, starting from 0, from left-to-right, then top-to-bottom.
 * To renumber the frames from top-to-bottom, then left-to-right, call setHorizontalSequence().
 * To use a custom renumbering sequence, call setIndexSequence() with an list using the default index, in the desired new sequence.
 */
class SpriteSheetBitmap(private val bitmap: Bitmap, private val cols: Int, private val rows: Int, private val center: Boolean = true): SpriteBitmap {
    private var dimensions: Dimensions = Dimensions(bitmap.width.div(cols), bitmap.height.div(rows))
    private var frameArray = loadFrames(bitmap, cols, rows)
    private var originalFrameArray = frameArray
    private val drawOffsetX: Float = when(center) { false -> 0f else -> 0.5f * dimensions.width }
    private val drawOffsetY: Float = when(center) { false -> 0f else -> 0.5f * dimensions.height }
    private var currIndex = 0

    /*
        Creates an array of Bitmaps by extracting the frames from the sheet.
        Frames are extracted from left-to-right, top-to-bottom.
     */
    private fun loadFrames(bitmap: Bitmap, cols: Int, rows: Int): Array<Bitmap> {
        val frames = mutableListOf<Bitmap>()

        var index = 0
        var yCurr = 0
        var currRow = 0
        while (currRow < rows) {
            var xCurr = 0
            var currCol = 0
            while (currCol < cols) {
                val resizedBmp = Bitmap.createBitmap(bitmap, xCurr, yCurr, dimensions.width, dimensions.height)
                frames.add(resizedBmp)

                xCurr += dimensions.width
                currCol++
                index++
            }
            yCurr += dimensions.height
            currRow++
        }

        return frames.toTypedArray()
    }

    override fun getDimensions(): Dimensions {
        return dimensions
    }

    override fun isCenter(): Boolean {
        return center
    }

    /**
     * Sets the custom sequence for the frames of the original sheet.
     */
    fun setIndexSequence(indexArray: Array<Int>) {
        currIndex = 0
        val newFrameOrder = mutableListOf<Bitmap>()
        for (index in indexArray) {
            newFrameOrder.add(originalFrameArray[index])
        }

        frameArray = newFrameOrder.toTypedArray()
    }

    /**
     * The frames in the original sheet are re-indexed from top-to-bottom, then left-to-right.
     */
    fun setHorizontalSequence() {
        currIndex = 0
        // TODO ...
    }

    /**
     * Changes the current frame to the next frame in the sequence, resetting to the first frame at the end.
     */
    override fun nextFrame() {
        if (currIndex < frameArray.size - 1) {
            currIndex++
        } else {
            currIndex = 0
        }
    }

    override fun prevFrame() {
        // TODO ...
    }

    /**
     * Draws the current frame at the given position on the canvas.
     */
    override fun draw(canvas: Canvas, position: Position, paint: Paint) {
        canvas.drawBitmap(frameArray[currIndex], position.xPos - drawOffsetX, position.yPos - drawOffsetY, paint)
    }
}

// TODO - try a BaseSprite with
//  - takes SpriteBitmap as an arg
//  - managed the requireDraw flag internally
//  - uses PluginFunctions to extend it

// TODO - check that we can pass a Canvas to the Plugin function ...
data class PluginFunction(val instance: Any, val method: Method)

// TODO - this is intended to simplify and replace Sprite
// TODO - Sprites need to handle movement (position and rotation, scaling) as well as visibility.
// TODO - ant any of these changes need to be triggered externally
// TODO - either by animator timing events, or by external events such as updateScore() or bump().
// Which means that the animator needs to wrap the spite (extend it).
// So - how do external non-animator events effect the sprite?
// If we extend it, we need to build the animator and the external events into the same extend instance.
// The extended instance calls underlying sprite functions to effect the timing and other effects.
// In the case that the extended sprite has many facets, like direction, death etc,
// we can embed many Sprites into the extended object.
// We would need a setFrame() to match the appearance when we switch sprites.
// This is in addition to the nextFrame() command.
// To do this, we could call getFrame() on the old spriteView, then setFrame() on the new.
// We can support a SpriteViewer factory that rotates and scales all the frames in an existing sprite.
// TODO - does the DrawnImage track its own position in the container, or does the wrapper do that?
// And do we track all positions as container relative (0 - 100%) of width and height???
interface DrawnImage: DoesDraw {

    fun setContainerDimensionsCallback(dimensions: Dimensions)

    fun getImageDimensions()

    fun isDrawRequired(): Boolean  // Maybe change this to isRedrawRequired()

    fun setDrawRequired()
    fun unsetDrawRequired()

    fun getAngle(): Int
    fun setAngle(degrees: Int)

    fun getScale(): Int
    fun setScale(degrees: Int)

    /**
     * The animation Thread needs to call this function to draw the sprite.
     */
    override fun drawCallback(canvas: Canvas)

    fun drawAt(canvas: Canvas, xPos: Int, yPos: Int)
}

class SingleDrawnImage(bitmap: Bitmap) {

}

// TODO - implement DrawnImage
class FramesetDrawnImage(framesetBitmap: Bitmap, ) {

    var frameIndex = 0

    fun setCurrFrameIndex(newIndex: Int) {
        frameIndex = newIndex
    }

    fun getCurrFrameIndex(): Int {
        return frameIndex
    }

    fun drawAt(canvas: Canvas, xPos: Int, yPos: Int) {
        // TODO
    }
}

data class RelativePosition(val xRelative: Float, val yRelative: Float)

// The intent is that animated images can contain one or more DrawnImages.
// The animated images are drawn at a position selected by implementations of this interface.
class AnimatedImage: DoesDraw {

    /**
     * Changes to the container will be notified here,
     * and should cause any DrawnImage objects to be repositioned and rescaled to suit the new dimensions.
     */
    fun setContainerDimensionsCallback(dimensions: Dimensions) {}

    private var relativePosition = RelativePosition(0f, 0f)

    // This is a RELATIVE position inside the container dimensions.
    fun getRelativePosition(): RelativePosition {
        return relativePosition
    }
    fun setRelativePosition(newRelativePosition: RelativePosition) {
        relativePosition = newRelativePosition
    }

    // Turtle-style commands:

    fun setDirectionDegrees(direction: Float) {
        // TODO
    }

    fun setDirectionRadians(direction: Float) {
        // TODO
    }

    fun moveForward(relativeDistance: Float) {
        // TODO - moves in the current direction.
    }

    // TODO - speed and velocity need the Timer...
    fun setRelativeSpeed(speed: Float) {
        // TODO
    }

    fun setRelativeVelocityDegrees(speed: Float, direction: Float) {
        // TODO
    }

    fun isDone(): Boolean {
        return false  // TODO
    }
    fun setDone() {}

    fun isVisible(): Boolean {
        return true // TODO
    }
    fun setVisibilityState(visibility: Boolean) {}

    fun setDrawRequired() {}
    fun unsetDrawRequired() {}
    fun isDrawRequired(): Boolean {
        return true // TODO
    }  // Maybe change this to isRedrawRequired()

    val imageList = mutableListOf<DrawnImage>()

    fun addImage() {
        // TODO - do I need this? If I add images to a list, then I need to index them or name them.
        // Maybe use an optional name, but allow indexed images in added order.
        // The
    }

    private fun drawCentered(canvas: Canvas, image: DrawnImage, xPos: Float, yPos: Float) {
        // TODO - Convenience method. Use the dimensions of the image to center it at xPOs, yPos.
        // Move to companion class to make it static.
    }

    private fun drawAllCentered(canvas: Canvas, image: DrawnImage, xPos: Float, yPos: Float) {
        // Convenience method - calls drawCentered for all added images in order.
    }

    /**
     * The animation Thread needs to call this function to draw the sprite.
     *
     * Generally, any contained DrawnImages will be selected in drawing order and drawn at getPosition() in the canvas.
     */
    override fun drawCallback(canvas: Canvas) {}
}






// TODO - we also need a BaseSprite for when we don't use a SpriteBitmap.

/**
 * To make an actual sprite from this BaseSprite, create a BaseSprite() instance,
 * then call extend(this) to plugin the startAnimation() and doDraw() functions.
 * There are also optional plugins like the one triggered by setContainerDimensionsCallback().
 * Then add the sprite to the SpriteDisplay.
 *
 * TODO - annotations for marking plugins
 */
class BitmapSprite(private val spriteBitmap: SpriteBitmap): DrawnImage {

    private var containerDimensions: Dimensions? = null
    private var done = false
    private var visible = true
    private var drawRequired = true

    private var position: Position = Position(0f, 0f)

    fun extend(instance: Any) {
        // TODO - search the provided instance with reflection to get Annotated functions and plug them into BaseSprite().
    }

    override fun setContainerDimensionsCallback(dimensions: Dimensions) {
        containerDimensions = dimensions
        // TODO - extend here - with a function that takes old new Dimensions.
    }

    override fun getImageDimensions() {
        TODO("Not yet implemented")
    }

    fun isDone(): Boolean {
        return done
    }

    fun setDone() {
        done = true
    }

    fun isVisible(): Boolean {
        return visible
    }

    fun setVisibilityState(visibility: Boolean) {
        visible = visibility
    }

    override fun isDrawRequired(): Boolean {
        return drawRequired
    }

    override fun setDrawRequired() {
        drawRequired = true
    }

    override fun unsetDrawRequired() {
        drawRequired = false
    }

    fun getPosition(): Position {
        return position
    }

    fun setPosition(newPos: Position) {
        position = newPos
        // TODO - allow plugin here...
    }

    override fun getAngle(): Int {
        TODO("Not yet implemented")
    }

    override fun setAngle(degrees: Int) {
        TODO("Not yet implemented")
    }

    override fun getScale(): Int {
        TODO("Not yet implemented")
    }

    override fun setScale(degrees: Int) {
        TODO("Not yet implemented")
    }

    override fun drawCallback(canvas: Canvas) {
        // THIS IS USED BY THE SpriteDisplay
        // Plugin a function here ...
        // TODO - call a plugin.
        drawRequired = false
    }

    override fun drawAt(canvas: Canvas, xPos: Int, yPos: Int) {
        TODO("Not yet implemented")
    }
}

// FIXME - Which is better FramesetSprite. or below  AnimatedFramesSprite
class FramesetSprite(val name: String, private val spriteBitmap: SpriteBitmap, private val period: Int, private val duration: Int = -1): Sprite {
    private var containerDimensions: Dimensions = Dimensions(0, 0)
    private var position: Position = Position(0f, 0f)
    private var requireDraw = true  // The initial state of the sprite needs to be drawn.
    private var done = false
    private var visible = true
    private val paint = Paint()
    private var timingServer: GameEngine.TimingServer? = null

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
        requireDraw = true
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

    override fun setContainerDimensionsCallback(newDimensions: Dimensions) {
        if (containerDimensions.width != 0 && containerDimensions.height != 0) {
            // Move the sprite the same relative position inside the frame container.
            val xGrowthRatio: Float = (newDimensions.width / containerDimensions.width).toFloat()
            val yGrowthRatio: Float = (newDimensions.height / containerDimensions.height).toFloat()

            val newPos = Position(xGrowthRatio * position.xPos, yGrowthRatio * position.yPos)
            setPosition(newPos)
        }
        containerDimensions = newDimensions
        setDrawRequired()
    }

    override fun startAnimation(timingServer: GameEngine.TimingServer) {
        val type = "$name-NextFrame"
        timingServer.addPeriodicEvent(::animateCallback, "$name-NextFrame", period)

        if (duration != -1) {
            this.timingServer = timingServer
            timingServer.addDelayedEvent(::doneCallback, "$name-Done", duration)
        }
    }

    private fun doneCallback(message: GameEngine.Message) {
        timingServer?.cancelEventsByType("$name-NextFrame")
        setDone()
    }

    override fun stopAnimation(timingServer: GameEngine.TimingServer){}
    override fun resumeAnimation(timingServer: GameEngine.TimingServer){}

    /**
     * Callback needed by the TimingServer Thread for every frame of animation.
     */
    override fun animateCallback(message: GameEngine.Message) {
        if (message.hasString("done")) {
            setDone()
        } else {
            spriteBitmap.nextFrame()
        }
        setDrawRequired()
    }

    /**
     * Callback needed by the Android UI Thread
     */
    override fun spriteDrawCallback(canvas: Canvas) {
        spriteBitmap.draw(canvas, position, paint)
    }

    /**
     * The animation thread calls this function if it needs to draw this sprite on the canvas via onDraw().
     * After onDraw(), isDrawRequired() returns False again.
     */
    override fun drawCallback(canvas: Canvas) {
        if (!done && visible) {
            spriteDrawCallback(canvas)
            requireDraw = false
        }
    }
}

open class AnimatedFramesSprite(bitmap: Bitmap, cols: Int, rows: Int, indexList: List<Int>? = null): AnimatedSprite() {

    val paint = Paint()
    var frameArray: Array<Bitmap> = arrayOf()
    var currFrame = 0

    init {
        frameArray = loadFrames(bitmap, cols, rows, indexList)
    }

    override fun setContainerDimensionsCallback(dimensions: Dimensions) {
        val newPos = Position(0.5f * dimensions.width - 0.5f * frameArray[0].width, 0.5f * dimensions.height - 0.5f * frameArray[0].height)
        setPosition(newPos)
    }

    override fun startAnimation(timingServer: GameEngine.TimingServer) {
        val type = "Sprite"
        val period = 50 // TODO - decide how to set this...
        timingServer.addPeriodicEvent(::animateCallback, type, period)
    }

    override fun stopAnimation(timingServer: GameEngine.TimingServer){}
    override fun resumeAnimation(timingServer: GameEngine.TimingServer){}

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
        canvas.drawBitmap(frameArray[currFrame], getPosition().xPos, getPosition().yPos, paint)
    }
}

class SpriteDisplay(private var containerDimensions: Dimensions, private val timingServer: GameEngine.TimingServer, private val period: Int, private val drawCallback: KFunction1<Array<DoesDraw>, Unit>) {

    fun getPeriod(): Int {
        return period
    }

    fun getContainerDimensions(): Dimensions {
        return containerDimensions
    }

    // TODO - do we need an update call for width and height?
    fun setContainerDimensions(containerDimensions: Dimensions) {
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