package game.paulgross.kakuroplaystore

import android.graphics.Canvas

interface Sprite {
    fun isDone(): Boolean
    fun setDone()

    fun isVisible(): Boolean
    fun setVisibilityState(visibility: Boolean)

    fun isDrawRequired(): Boolean  // Maybe change this to isRedrawRequired()

    fun setDrawRequired()
    fun unsetDrawRequired()

    // TODO - maybe have a pause animation and a cancel animation

    fun startAnimation(timingServer: GameEngine.TimingServer)
    fun stopAnimation(timingServer: GameEngine.TimingServer)
    fun resumeAnimation(timingServer: GameEngine.TimingServer)
    fun animateCallback(message: GameEngine.Message)

    /**
     * The animation Thread will call this to draw the sprite.
     */
    fun doDraw(canvas: Canvas)
    fun drawCallback(canvas: Canvas)
}

abstract class AnimatedSprite: Sprite {
    private var requireDraw = true  // The initial state of the sprite needs to be drawn.
    private var done = false
    private var visible = true

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
            doDraw(canvas)
            requireDraw = false
        }
    }

    // Default empty function implementations, which can optionally be overridden:
    override fun startAnimation(timingServer: GameEngine.TimingServer){}
    override fun stopAnimation(timingServer: GameEngine.TimingServer){}
    override fun resumeAnimation(timingServer: GameEngine.TimingServer){}
}

abstract class StaticSprite: Sprite {
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

    // Implement all animation functions as empty.
    final override fun startAnimation(timingServer: GameEngine.TimingServer) {}
    final override fun stopAnimation(timingServer: GameEngine.TimingServer) {}
    final override fun resumeAnimation(timingServer: GameEngine.TimingServer) {}
    final override fun animateCallback(message: GameEngine.Message) {}

    final override fun drawCallback(canvas: Canvas) {
        doDraw(canvas)
        requireDraw = false
    }
}
// TODO - implement an abstract class for SimpleSprite that only has drawCallback()
// This is useful for things like background graphics that never change.

class SpriteDisplay(private val timingServer: GameEngine.TimingServer, val period: Int, private val drawCallback: (spriteList: Array<Sprite>) -> Unit) {

    // TODO - need to determine if I need a setRedrawCallback() func or if it's OK in the constructor.
    // - because the View instance changes when the screen is rotated, and then there is the odd behaviour when the app is backgrounded...

    // TODO: Move start and stop animation loop here.
    fun startAnimationLoop() {
        timingServer.addPeriodicEvent(::animateCallback, "AnimationLoop", period)
    }

    fun stopAnimationLoop() {
        timingServer.cancelEventsByType("AnimationLoop")
    }
    // provide accessor methods for timers, so that this class can mediate the animation updates.

    private val allSprites = mutableListOf<Sprite>()

    fun addSprite(sprite: Sprite, groupName: String, start: Boolean = false) {
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

    private fun animateCallback(message: GameEngine.Message) {
//        println("#### SpriteDisplay - animateCallback() running...")
//        println("#### SpriteDisplay - checking ${allSprites.size} sprites...")
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

        // TODO - Go through all sprites and make a list of visible sprites in drawing order.
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
            drawCallback.invoke(drawList.toTypedArray())
        }
    }
}