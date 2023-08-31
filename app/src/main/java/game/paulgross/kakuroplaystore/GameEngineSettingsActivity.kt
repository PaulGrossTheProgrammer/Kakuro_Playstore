package game.paulgross.kakuroplaystore

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class GameEngineSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
    }

    // TODO - make a list view of all the settings.

    // https://abhiandroid.com/ui/listview
    // Each item in the list is a different activity to modify those settings.

}