package game.paulgross.kakuroplaystore

import android.graphics.Canvas

interface Sprite {
    fun isDone(): Boolean
    fun isDrawRequired(): Boolean  // Maybe change this to isRedrawRequired()
    fun setDrawRequired()
    fun unsetDrawRequired()
    fun startAnimation(gameEngine: GameEngine)
    fun stopAnimation(gameEngine: GameEngine)
    fun resumeAnimation(gameEngine: GameEngine)
    fun animateCallback(message: GameEngine.Message)  // Do I need this in the interface???
    fun doDraw(canvas: Canvas)
}

// TODO - maybe make an abstract class BaseSprite that force-implements the requirement
// that redrawRequired is initialised to true, and and set to false after every drawCallback.

abstract class BaseSprite: Sprite {
    private var requireDraw = true  // The initial state of the sprite needs to be drawn.

    final override fun setDrawRequired() {
        requireDraw = true
    }

    final override fun unsetDrawRequired() {
        requireDraw = false
    }

    final override fun isDrawRequired(): Boolean {
        return requireDraw
    }

    final fun drawCallback(canvas: Canvas) {
        doDraw(canvas)
        requireDraw = false
    }
}

// TODO - implement an abstract class for SimpleSprite that only has drawCallback()
// This is useful for things like background graphics that never change.

class SpriteDisplay(private val engine: GameEngine, private val drawCallback: (spriteList: List<Sprite>) -> Unit) {

    // TODO - need to determine if I need a setRedrawCallback() func or if it's OK in the constructor.
    // - because the View instance changes when the screen is rotated, and then there is the odd behaviour when the app is backgrounded...

    // TODO: Move start and stop animation loop here.
    fun startAnimationLoop() {
        engine.requestPeriodicEvent(::animateCallback, "AnimationLoop", 50)
    }

    fun stopAnimationLoop() {
        engine.cancelEventsByType("AnimationLoop")
    }
    // provide accessor methods for timers, so that this class can mediate the animation updates.

    val allSprites = mutableListOf<Sprite>()

    fun addSprite(sprite: Sprite, groupName: String, start: Boolean = false) {
        allSprites.add(sprite)
        if (start) {
            sprite.startAnimation(engine)
        }
        // TODO: create internal lists of sprites attached to groupNames.

        // If the groupname doesn't have a defined order, it gets drawn last.
    }

    fun startAnimation(groupName: String) {
        // TODO: Only animate sprites in groupName.
        for (sprite in allSprites) {
            sprite.startAnimation(engine)
        }
    }

    // TODO: for a static animation use stopAnimation() and hideGroup() then start/resumeAnimation() unhideGroup() to toggle visibility.
    // That way there are no redundant animation callbacks for temporarily unwanted animations.
    // And then we won;t need to keep creating Sprites, we will just switch them on and off as needed.

    fun stopAnimation(groupName: String) {
        // TODO - maybe the timer stops and sends a special message that allows the animation to be resumed where it was left off?
        // The instance will have to save the message and use it for resumeAnimation()
        for (sprite in allSprites) {
            sprite.stopAnimation(engine)
        }
    }

    fun resumeAnimation(groupName: String) {
        for (sprite in allSprites) {
            sprite.resumeAnimation(engine)
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

    private fun animateCallback(message: GameEngine.Message) {
//            println("#### Animation loop running ...")
        val doneSprites = mutableListOf<Sprite>()
        for (sprite in allSprites) {
            if (sprite.isDone()) {
                doneSprites.add(sprite)
            }
        }
        allSprites.removeAll(doneSprites)

        // TODO - Go through all sprites and make a list of visible sprites in drawing order.
        var anyChangedSprites = false
        val drawList = mutableListOf<Sprite>()
        for (sprite in allSprites) {
            drawList.add(sprite)
            if (!anyChangedSprites && sprite.isDrawRequired()) {
                anyChangedSprites = true
            }
        }

        if (anyChangedSprites) {
            // This should send a message to the View with all visible sprites.
            // Note that any previous drawList should continue to be used to draw sprites until this callback is used.
            drawCallback.invoke(drawList)
        }
    }
}