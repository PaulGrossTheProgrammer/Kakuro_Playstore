package game.paulgross.kakuroplaystore

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class SocketServer(private val gameRequestQ: BlockingQueue<GameServer.ClientRequest>): Thread() {

    private lateinit var serverSocket: ServerSocket
    private val working = AtomicBoolean(true)
    private val clientHandlers: MutableList<SocketClientHandler> = arrayListOf()

    override fun run() {
        var clientSocket: Socket? = null
        try {
            serverSocket = ServerSocket(PORT)
            Log.d(TAG, "Created ServerSocket for ${serverSocket.localSocketAddress}")

            while (working.get()) {
                Log.i(TAG, "Waiting for new client sockets...")
                clientSocket = serverSocket.accept()
                Log.i(TAG, "New client: $clientSocket")

                // Create a new Thread for each client.
                val t = SocketClientHandler(clientSocket,this)
                t.start()

                // This list allows us to remove each Handler if the client disconnects
                // by calling removeClientHandler().
                clientHandlers.add(t)
            }
        } catch (e: IOException) {
            if (!working.get()){
                Log.d(TAG, "The Socket Server has been forced to stop listening for connections.")
            } else {
                e.printStackTrace()
            }
            try {
                clientSocket?.close()
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
        }
        Log.d(TAG, "The Socket Server has shut down.")
    }

    fun countOfClients(): Int {
        return clientHandlers.size
    }

    fun pushMessageToClients(message: String) {
        Log.d(TAG, "The Socket Server is pushing a message to all clients:  [${message}]")
        clientHandlers.forEach {handler ->
            handler.queueMessage(message)
        }
    }

    fun shutdownRequest() {
        Log.d(TAG, "The Socket Server is shutting down ...")
        working.set(false)
        serverSocket.close()

        Log.d(TAG, "The Socket Server has ${clientHandlers.size} open Client Handlers.")
        clientHandlers.forEach {handler ->
            handler.shutdownRequest()
        }
    }

    fun removeClientHandler(socketClientHandler: SocketClientHandler) {
        clientHandlers.remove(socketClientHandler)
    }

    companion object {
        private val TAG = SocketServer::class.java.simpleName
        const val PORT = 6868
    }
}