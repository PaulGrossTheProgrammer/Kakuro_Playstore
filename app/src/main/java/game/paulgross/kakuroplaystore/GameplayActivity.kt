package game.paulgross.kakuroplaystore

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class GameplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)
    }

    companion object {
        private val TAG = GameplayActivity::class.java.simpleName
        val DISPLAY_MESSAGE_SUFFIX = ".$TAG.display.UPDATE"
    }
}