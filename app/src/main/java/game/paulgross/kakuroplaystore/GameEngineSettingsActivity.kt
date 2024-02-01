package game.paulgross.kakuroplaystore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity


class GameEngineSettingsActivity : AppCompatActivity() {

//    private val settingsListNames: List<String> = listOf<String>("LOCAL", "CLIENT", "SERVER")
    private val settingsListNames: List<String> = listOf<String>("PRIVACY POLICY")
    private val settingsListIndexViewIds: List<Int> = listOf(R.layout.activity_settings_listitem, R.layout.activity_settings_listitem, R.layout.activity_settings_listitem)

    // TODO - add client and local layouts too ...
    private val settingsTargetViewIds: Map<String, Int> = mapOf(
        "PRIVACY POLICY" to R.layout.activity_settings_privacypolicy,
//        "LOCAL" to R.layout.activity_settings_server,
//        "CLIENT" to R.layout.activity_settings_server,
//        "SERVER" to R.layout.activity_settings_server
    )

    private var listOfSettings: ListView? = null

    private var selectedSetting: String = ""

    private var engine: GameEngine? = null

    private var gameMode: GameEngine.GameMode = GameEngine.GameMode.LOCAL
    private var remotePlayerCount = 0
    private val allIpAddresses: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate ....")
        selectedSetting = intent.extras?.getString("SelectedSetting" , "").toString()
        Log.d(TAG, "Activity Started with [$selectedSetting]")

        // For the moment just display the provacy policy untilwe know why the app keeps failing Google TV approval.
//        showLayout()
        setContentView(R.layout.activity_settings_privacypolicy)

        enableQueuedMessages()
        // TODO - handle lack of engine activation... which is a null return here.
        engine = GameEngine.get()
        engine?.queueMessageFromActivity(GameEngine.Message("RequestEngineStateChanges"), ::queueMessage)
    }

    override fun onPause() {
        super.onPause()
        engine?.queueMessageFromActivity(GameEngine.Message("RequestStopEngineStateChanges"), ::queueMessage)
    }

    override fun onResume() {
        super.onResume()
        engine = GameEngine.get()
        engine?.queueMessageFromActivity(GameEngine.Message("RequestEngineStateChanges"), ::queueMessage)
    }

    private fun showLayout() {
        // Use selectedSetting to determine layout ...
        var lookupViewId: Int? = settingsTargetViewIds[selectedSetting]
        if (lookupViewId == null) {
            // Reset the selectedSetting if required
            if (selectedSetting != "") {
                Log.d(TAG, "Invalid setting [$selectedSetting]. Switching to default Layout.")
                selectedSetting = ""
            }
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

    // TODO: Move this out of the engine to the Kakuro-specific code.
    val privacyPolicyLink = "https://docs.google.com/document/d/e/2PACX-1vS6enuOilpSfUxDtBnDxLg_AQhUB3iAlPsS3-VFZOH_jt798KfHb3Qd6259oAZ6I9YUUQ9C2K223st4/pub"

    fun onClickPrivacyPolicy(view: View) {
        val myIntent = Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyLink))
        startActivity(myIntent)
    }

    fun showServerSettings(settingName: String) {
        selectedSetting = settingName
        showLayout()
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
        // Force end task while we try to get Google TV approval.
        finishAndRemoveTask()  // This should automatically return to the calling activity.

/*
        if (selectedSetting == "") {
            Log.d(TAG, "onBackPressed - TOP LEVEL")
            finishAndRemoveTask()  // This should automatically return to the calling activity.
            return
        }

        showServerSettings(getSettingParent(selectedSetting))
*/
    }

    /**
     * Receive messages from the GameEngine.
     */
    private val activityMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val messageString = intent.getStringExtra("Message") ?: return

            val message = GameEngine.Message.decodeMessage(messageString)
            // TODO - handle message
            Log.d(TAG, "Got a message: ${message.type}")
            if (message.type == "EngineState") {
                Log.d(TAG, "TODO - handle GameMode message ...")
                val mode = message.getString("GameMode")
                val ipString = message.getString("IPAddress")
                val clientCount = message.getString("Clients")

                if (mode != null) {
                    Log.d(TAG, "mode = $mode")
                    gameMode = GameEngine.GameMode.valueOf(mode)
                }
                if (ipString != null) {
                    Log.d(TAG, "ipString = $ipString")
                    allIpAddresses.clear()
                    val newList = engine?.decodeIpAddresses(ipString)
                    if (newList != null) {
                        allIpAddresses.addAll(newList)
                    }
                }
                if (clientCount != null) {
                    Log.d(TAG, "clientCount = $clientCount")
                    remotePlayerCount = clientCount.toInt()
                }
            }
        }
    }

    private var queuedMessageAction: String = "$TAG.activity.MESSAGE"

    private fun enableQueuedMessages() {
        val intentFilter = IntentFilter()
//        TODO - set intentFilter RECEIVER_NOT_EXPORTED flag - BUT HOW???
        intentFilter.addAction(queuedMessageAction)
        registerReceiver(activityMessageReceiver, intentFilter)
        Log.d(TAG, "Enabled message receiver for [${queuedMessageAction}]")
    }

    /**
     * This is the CALLBACK function to be used when a message needs to be queued for this Activity.
     */
    private fun queueMessage(message: GameEngine.Message) {
        // The UI thread will call activityMessageReceiver() to handle the message.
        val intent = Intent()
        intent.action = queuedMessageAction
        intent.putExtra("Message", message.asString())
        sendBroadcast(intent)
    }

    companion object {
        private val TAG = GameEngineSettingsActivity::class.java.simpleName
   }
}