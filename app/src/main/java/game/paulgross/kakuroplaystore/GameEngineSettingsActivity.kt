package game.paulgross.kakuroplaystore

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class GameEngineSettingsActivity : AppCompatActivity() {

    // TODO - make a list view of all the settings.
    // https://abhiandroid.com/ui/listview
    // Each item in the list is a different activity to modify those settings.

    var listOfSettings: ListView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate ....")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        listOfSettings = findViewById(R.id.settingsListView)
    }



    override fun onBackPressed() {
        val intent: Intent = Intent(this, KakuroGameplayActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    companion object {
        private val TAG = GameEngineSettingsActivity::class.java.simpleName
    }
}