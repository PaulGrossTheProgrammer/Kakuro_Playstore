package game.paulgross.kakuroplaystore

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class SocketClientHandler(private val engine: GameEngine, private val socket: Socket, private val socketServer: SocketServer): Thread() {

    private val sendToThisHandlerQ: BlockingQueue<GameEngine.Message> = LinkedBlockingQueue()

    private val listeningToGameServer = AtomicBoolean(true)
    private val listeningToSocket = AtomicBoolean(true)

    override fun run() {
        val output = BufferedWriter(OutputStreamWriter(DataOutputStream(socket.getOutputStream())))

        SocketReaderThread(engine, socket, sendToThisHandlerQ, listeningToSocket).start()

        Log.d(TAG, "New client connection handler started ...")

        try {
            while (listeningToGameServer.get()) {
                // Wait here for any messages from the GameEngine.
                Log.d(TAG, "Waiting for GameEngine message...")
                val message = sendToThisHandlerQ.take()  // Blocked until we get data.
                Log.d(TAG, "Got GameEngine message.")

                if (message.type == "Abandoned") {
                    // Special case: GameEngine wants to shutdown this Handler.
                    Log.d(TAG, "Remote socket abandoned. Shutting down.")
                    shutdown()
                } else {
                    if (message.type == "Shutdown") {
                        // Special case: GameEngine wants to shutdown this Handler.
                        shutdown()
                    }

                    // Pass on to the remote SocketClient
                    Log.d(TAG, "Sending remote Client [$message]")
                    output.write(message.asString())
                    output.write("\n")  // TODO: Maybe use a PrintWriter??
                    output.flush()
                }
            }
        } catch (e: SocketException) {
            if (listeningToGameServer.get()) {
                Log.d(TAG, "ERROR: Writing to Remote Socket caused unexpected error - abandoning socket.")
                e.printStackTrace()
            }
        } catch (e: IOException) {
            if (listeningToGameServer.get()) {
                Log.d(TAG, "ERROR: Writing to Remote Socket caused unexpected error - abandoning socket.")
                e.printStackTrace()
            }
        }

        output.close()
        shutdown()

        Log.d(TAG, "The Writer has shut down.")
    }

    private fun shutdown() {
        socketServer.removeClientHandler(this)  // Can't the SocketServer do this by itself???

        listeningToSocket.set(false)
        listeningToGameServer.set(false)
        socket.close()
    }

    /**
    // This function is only called by the SocketServer Thread.
     */
    fun shutdownRequest() {
        sendToThisHandlerQ.add(GameEngine.Message("Shutdown"))
    }

    private class SocketReaderThread(private val engine: GameEngine, private val socket: Socket,
                                     private val sendToThisHandlerQ: BlockingQueue<GameEngine.Message>,
                                     private var listeningToSocket: AtomicBoolean
    ): Thread() {

        override fun run() {
            val input = BufferedReader(InputStreamReader(DataInputStream(socket.getInputStream())))
            try {
                while (listeningToSocket.get()) {
                    val data = input.readLine()  // Blocked until we get a line of data.
                    if (data == null) {
                        Log.d(TAG, "ERROR: Remote data from Socket was unexpected NULL - abandoning socket Listener.")
                        listeningToSocket.set(false)
                        engine.queueMessageFromClientHandler(GameEngine.Message("Abandoned"), ::queueMessage)
                    } else {
                        engine.queueMessageFromClientHandler(GameEngine.Message.decodeMessage(data), ::queueMessage)
                    }
                }
            } catch (e: SocketException) {
                if (listeningToSocket.get()) {
                    listeningToSocket.set(false)
                    Log.d(TAG, "ERROR: Reading from Remote Socket caused unexpected error - abandoning socket Listener.")
                    e.printStackTrace()
                }
            } catch (e: IOException) {
                if (listeningToSocket.get()) {
                    listeningToSocket.set(false)
                    Log.d(TAG, "ERROR: Reading from Remote Socket caused unexpected error - abandoning socket Listener.")
                    e.printStackTrace()
                }
            }

            input.close()
            Log.d(TAG, "The Listener has shut down.")
        }

        /**
         * This is the callback function to be used when a message needs to be queued for this Socket.
         */
        fun queueMessage(message: GameEngine.Message) {
            Log.d(TAG, "Pushing message to Socket client handler queue: [$message]")
            sendToThisHandlerQ.add(message)
        }
    }

    companion object {
        private val TAG = SocketClientHandler::class.java.simpleName
    }
}