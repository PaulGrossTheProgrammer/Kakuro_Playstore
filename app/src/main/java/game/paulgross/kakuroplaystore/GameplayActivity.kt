package game.paulgross.kakuroplaystore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog

class GameplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gameplay)

        enableMessagesFromGameServer()
        GameServer.activate(applicationContext, getPreferences(MODE_PRIVATE))

        GameServer.queueActivityMessage("Status")
    }

    public override fun onBackPressed() {
        confirmExitApp()
    }

    private fun confirmExitApp() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Exit")
        builder.setMessage("Are you sure you want to exit?")
        builder.setPositiveButton("Exit") { _, _ ->
            exitApp()
        }
        builder.setNegativeButton("Back") { _, _ -> }
        builder.show()
    }

    private fun exitApp() {
        stopGameServer()
        finishAndRemoveTask()
    }
    private fun stopGameServer() {
        Log.d(TAG, "Stopping the game server ...")
        GameServer.queueActivityMessage("StopGame")
    }

    private fun enableMessagesFromGameServer() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(packageName + MESSAGE_SUFFIX)
        registerReceiver(gameMessageReceiver, intentFilter)
        Log.d(TAG, "Enabled message receiver for [${packageName + MESSAGE_SUFFIX}]")
    }

    private var previousStateString = ""

    /**
    Receive messages from the GameServer.
     */
    private val gameMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val stateString = intent.getStringExtra("State")

            if (stateString != null && previousStateString != stateString) {
                Log.d(TAG, "Got a new state string [$stateString]")
                previousStateString = stateString
                val newState = GameServer.decodeState(stateString)
                if (newState != null) {

                }
            }
        }
    }

    companion object {
        private val TAG = GameplayActivity::class.java.simpleName
        val MESSAGE_SUFFIX = ".$TAG.display.UPDATE"
    }
}