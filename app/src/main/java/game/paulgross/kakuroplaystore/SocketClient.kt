package game.paulgross.kakuroplaystore

import android.util.Log
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class SocketClient(private val server: String, private val port: Int): Thread() {

    // FIXME - doesn't handle when the remote server isn't running...
    // Use a "connecting" flag in a delay loop with a limit to the retries
    // Ask user how long to wait for remote server to accept connection
    // Permit user to abort connections that take too long.

    private val clientSocket: Socket = Socket(server, port)

    private val fromGameEngineQ: BlockingQueue<String> = LinkedBlockingQueue()

    private val listeningToGameEngine = AtomicBoolean(true)
    private val listeningToSocket = AtomicBoolean(true)

    override fun run() {
        Log.i(TAG, "Client connected...")
        val output = PrintWriter(clientSocket.getOutputStream());

        output.println("Initialise")
        output.flush()

        SocketReaderThread(clientSocket, fromGameEngineQ, listeningToSocket).start()

        try {
            while (listeningToGameEngine.get()) {
                val gameMessage = fromGameEngineQ.take()  // Blocked until we get data.
                Log.d(TAG, "From LOCAL Game Engine: [$gameMessage]")

                if (gameMessage == "Abandoned") {
                    Log.d(TAG, "Remote socket abandoned. Shutting down.")
                    shutdown()
                } else {
                    if (gameMessage == "Shutdown") {
                        shutdown()
                    }

                    // All other messages are passed to the network.
                    output.println(gameMessage)
                    output.flush()
                }
            }
        } catch (e: SocketException) {
            if (listeningToGameEngine.get()) {
                Log.d(TAG, "ERROR: Writing to Remote Socket caused unexpected error - abandoning socket.")
                e.printStackTrace()
            }
        } catch (e: IOException) {
            if (listeningToGameEngine.get()) {
                Log.d(TAG, "ERROR: Writing to Remote Socket caused unexpected error - abandoning socket.")
                e.printStackTrace()
            }
        }

        output.close()
        shutdown()
        Log.i(TAG, "The Writer has shut down.")
    }

/*    fun getServer(): String {
        return server
    }

    fun messageFromGameServer(message: String) {
        fromGameServerQ.add(message)
    }*/

    private fun shutdown() {
        listeningToSocket.set(false)
        listeningToGameEngine.set(false)
        clientSocket.close()
    }

    fun shutdownRequest() {
        fromGameEngineQ.add("Shutdown")
    }

    private class SocketReaderThread(private val socket: Socket, private val fromGameServerQ: BlockingQueue<String>,
                                     private var listeningToSocket: AtomicBoolean
    ): Thread() {

        override fun run() {
            val input = BufferedReader(InputStreamReader(DataInputStream(socket.getInputStream())))
            try {
                while (listeningToSocket.get()) {
                    // TODO - design for long responses that are split across multiple lines by the server.
                    val data = input.readLine()  // Blocked until we get a line of data.
                    if (data == null) {
                        Log.d(TAG, "ERROR: Remote data from Socket was unexpected NULL - abandoning socket Listener.")
                        listeningToSocket.set(false)
                        GameEngine.queueClientMessage(GameEngine.Message("Abandoned"), ::queueMessage)
                    }

                    if (data != null) {
                        Log.d(TAG, "From REMOTE game server: [$data]")
                        GameEngine.queueClientMessage(GameEngine.Message.decodeMessage(data), ::queueMessage)
                    }
                }
            } catch (e: SocketException) {
                if (listeningToSocket.get()) {
                    listeningToSocket.set(false)  // Unexpected Exception while listening
                    e.printStackTrace()
                }
            } catch (e: IOException) {
                if (listeningToSocket.get()) {
                    listeningToSocket.set(false)  // Unexpected Exception while listening
                    e.printStackTrace()
                }
            }

            input.close()
            Log.d(TAG, "The Listener has shut down.")
        }

        /**
         * This is the callback function to be used when a message needs to be queued for this Socket Client.
         */
        fun queueMessage(message: String) {
            Log.d(TAG, "Pushing message to Socket Client queue: [$message]")
            fromGameServerQ.add(message)
        }
    }

    companion object {
        private val TAG = SocketClient::class.java.simpleName
    }
}