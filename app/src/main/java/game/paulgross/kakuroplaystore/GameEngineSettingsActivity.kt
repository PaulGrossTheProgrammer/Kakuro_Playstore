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
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
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

    private var selectedSetting: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate ....")
        selectedSetting = intent.extras?.getString("SelectedSetting" , "").toString()
        Log.d(TAG, "Activity Started with [$selectedSetting]")

        controlLayout()
    }

    private fun controlLayout() {
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

    fun onClickToggleServer(view: View) {
        Log.d(TAG, "TODO: Toggle server state.")
        if (view is Switch) {
            Log.d(TAG, "We have a Switch!")
            val state = view.isChecked
            if (state) {
                Log.d(TAG, "State is TRUE")

            } else {
                Log.d(TAG, "State is FALSE")

            }
        }
    }

    fun showServerSettings(settingName: String) {
        selectedSetting = settingName
        controlLayout()
    }

    /**
     * Removes the child setting name from the end of the dot-separated setting name.
     * "Settings.Name1.Name2"
     *  becomes
     * "Settings.Name1"
     *
     * If there is no dot, the parent is "".
     */
    private fun getSettingParent(settingName: String): String {
        if (!settingName.contains('.')) {
            return ""
        }

        val parent = settingName.substring(0, settingName.lastIndexOf('.'))
        Log.d(TAG, "Returning Parent of [$settingName] as [$parent]")
        return parent
    }

    override fun onBackPressed() {
        if (selectedSetting == "") {
            Log.d(TAG, "onBackPressed - TOP LEVEL")
            finishAndRemoveTask()  // This should automatically return to the calling activity.
            return
        }

        showServerSettings(getSettingParent(selectedSetting))
    }

    companion object {
        private val TAG = GameEngineSettingsActivity::class.java.simpleName
   }
}