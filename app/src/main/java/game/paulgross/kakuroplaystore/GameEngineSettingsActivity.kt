package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
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
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity

class GameEngineSettingsActivity : AppCompatActivity() {

    private val settingsListNames: List<String> = listOf<String>("LOCAL", "CLIENT", "SERVER")

    private val settingsListIndexViewIds: List<Int> = listOf(R.layout.activity_settings_listitem, R.layout.activity_settings_listitem, R.layout.activity_settings_listitem)

    // TODO - add client and local layouts too ...
    private val settingsTargetViewIds: Map<String, Int> = mapOf(
        "LOCAL" to R.layout.activity_settings_server,
        "CLIENT" to R.layout.activity_settings_server,
        "SERVER" to R.layout.activity_settings_server
    )

    private var listOfSettings: ListView? = null

    var selectedSetting: String? = null
    // TODO - replace topLevelMenu with selectedSetting. Null means top level.
//    private var topLevelMenu = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate ....")
        selectedSetting = intent.extras?.getString("SelectedSetting")
        Log.d(TAG, "Activity Started with [$selectedSetting]")
//        if (selectedSetting != null) {
//            topLevelMenu = false
//        }

        // Use selectedSetting to determine layout ...
        var lookupViewId: Int? = settingsTargetViewIds[selectedSetting]
        if (lookupViewId == null) {
            // Default layout - no setting was selected to run this Activity.
            lookupViewId = R.layout.activity_settings
        }

        setContentView(lookupViewId)

        listOfSettings = findViewById(R.id.settingsListView)
        if (listOfSettings != null) {
            // TODO: Why is the non-null assertion !! required here?
            listOfSettings!!.adapter = ListAdapter(this, settingsListNames, settingsListIndexViewIds)
        }
    }

    class ListAdapter(private val context: GameEngineSettingsActivity,
                      private val settingsListNames: List<String>,
                      private val settingsListIndexViewIds: List<Int>) : BaseAdapter() {

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

        private val cachedIndexViews: MutableMap<Int, View> = mutableMapOf()

        @SuppressLint("ViewHolder")
        override fun getView(index: Int, view: View?, viewGroup: ViewGroup?): View? {
            // lookup the index in the View cache ...
            val cachedView = cachedIndexViews[index]
            if (cachedView != null) {
                return cachedView
            }

            // Lookup and inflate the new View ...
            val viewId = settingsListIndexViewIds[index]

            val newView: View? = inflater?.inflate(viewId, null)

            val itemName: TextView? = newView?.findViewById(R.id.textViewItemName)
            val settingName = settingsListNames[index]
            itemName?.setText(settingName)
            newView?.setOnClickListener { context.showServerSettings(settingName) }

            // Put the new View in the cache
            if (newView is View) {
                cachedIndexViews[index] = newView
            }
            return newView
        }
    }

    // TODO - make this generic.
    // TODO - move this function to the Activity and call that function from here.
    fun showServerSettings(settingName: String) {
        val intent = Intent(this, GameEngineSettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("SelectedSetting", settingName)
        startActivity(intent)
    }

    override fun onBackPressed() {
//        if (topLevelMenu) {
        if (selectedSetting == null) {
            Log.d(TAG, "onBackPressed - TOP LEVEL")
//            startActivity(returnIntent)
//            finishActivity(0)
            finishAndRemoveTask()  // This should automatically return to the calling activity.
            return
        }

        // FIXME - don't permit the back button for anything except the top level.
        //  Use a custom back button instead.
        // Maybe sut of the head of the dot-separated SelectedSetting, so name1.name2 -> name1
        // and name1 -> null

        Log.d(TAG, "onBackPressed - NOT top level")
        val intent = Intent(this, GameEngineSettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    companion object {
        private val TAG = GameEngineSettingsActivity::class.java.simpleName
   }
}