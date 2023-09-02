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
    private val settingsListNames: List<String> = listOf<String>("LOCAL", "CLIENT", "SERVER")
    private val settingsListIndexViews: List<Int> = listOf(R.id.textViewItemName, R.id.textViewItemName, R.id.textViewItemName)
    private val settingsTargetViews: List<Int> = listOf(R.id.settingsListView)

    private var listOfSettings: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate ....")
        val selectedSetting = intent.extras?.getString("SelectedSetting")
        Log.d(TAG, "Activity Started with [$selectedSetting]")

        // Use selectedSetting to determine layout ...

        // Default layout - no setting was selected to run this Activity.
        setContentView(R.layout.activity_settings)

        listOfSettings = findViewById(R.id.settingsListView)
        if (listOfSettings != null) {
            // TODO: Why is the non-null assertion !! required here?
            listOfSettings!!.adapter = ListAdapter(this, settingsListNames, settingsListIndexViews)
        }
    }

    class ListAdapter(private val context: Context,
                      private val settingsListNames: List<String>,
                      private val settingsListIndexViews: List<Int>) : BaseAdapter() {

        private var inflater: LayoutInflater? = null

        init {
            inflater = LayoutInflater.from(context)
        }

        override fun getCount(): Int {
            return settingsListNames.size
        }

        override fun getItem(position: Int): Any? {
            Log.d("$TAG.ListAdapter", "Calling getItem() with $position...  WHY???")
            return null
        }

        override fun getItemId(position: Int): Long {
            return 0
        }

        override fun getView(index: Int, view: View?, viewGroup: ViewGroup?): View? {
            Log.d("$TAG.ListAdapter", "getView() for index $index")

            val view = inflater?.inflate(R.layout.activity_settings_listitem, null)

            // TODO - lookup the index view in settingsListIndexViews
//            settingsListIndexViews

            val itemName: TextView? = view?.findViewById(R.id.textViewItemName)
            val settingName = settingsListNames[index]
            itemName?.setText(settingName)
            view?.setOnClickListener { showServerSettings(settingName) }
            return view
        }

        private fun showServerSettings(settingName: String) {
            val intent: Intent = Intent(context, GameEngineSettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("SelectedSetting", settingName)
            context.startActivity(intent)
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