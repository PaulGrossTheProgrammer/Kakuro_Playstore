package game.paulgross.kakuroplaystore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GameEngineSettingsActivity : AppCompatActivity() {

    // TODO - make a list view of all the settings.
    // https://abhiandroid.com/ui/listview
    // Each item in the list is a different activity to modify those settings.
    val settingsNames: List<String> = listOf<String>("LOCAL", "CLIENT", "SERVER")

    var listOfSettings: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate ....")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        listOfSettings = findViewById(R.id.settingsListView)
        if (listOfSettings != null) {
            // TODO: Why is the non-null assertion !! required here?
            listOfSettings!!.adapter = ListAdapter(this, settingsNames)
        }
    }

    class ListAdapter(private val context: Context, private val settingsList: List<String>) : BaseAdapter() {

        private var inflater: LayoutInflater? = null

        init {
            inflater = LayoutInflater.from(context)
        }

        override fun getCount(): Int {
            return settingsList.size
        }

        override fun getItem(position: Int): Any? {
            Log.d("$TAG.ListAdapter", "Calling getItem() with $position...  WHY???")
            return null
        }

        override fun getItemId(position: Int): Long {
            // TODO - determine why this is called twice for a single touch ....
            Log.d("$TAG.ListAdapter", "Calling getItemId() with $position")

            val settingName = settingsList[position]

            Log.d("$TAG.ListAdapter", "TODO: Handle select for $settingName")


            return position.toLong()  // What does this return value even mean? Why not just 0???
        }

        override fun getView(index: Int, view: View?, viewGroup: ViewGroup?): View? {
            val view = inflater?.inflate(R.layout.activity_settings_listitem, null)
            val itemName: TextView? = view?.findViewById(R.id.textViewItemName)
            itemName?.setText(settingsList[index])

            return view
        }
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