package com.golfcart.gcd.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.MeshtasticConstants
import com.golfcart.gcd.data.bluetooth.MeshtasticMessageHandler
import com.golfcart.gcd.domain.audio.AudioManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Meshtastic messaging UI.
 *
 * Manages received message display, preformatted message selection, custom message
 * composition, and sends the AWAKE notification on connection establishment.
 *
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.8
 */
@HiltViewModel
class MessagingViewModel @Inject constructor(
    private val meshtasticConnection: MeshtasticConnection,
    private val messageHandler: MeshtasticMessageHandler,
    private val audioManager: AudioManager
) : ViewModel() {

    companion object {
        private const val TAG = "MessagingViewModel"

        /** AWAKE notification message sent on connection establishment. */
        const val AWAKE_MESSAGE = "~#01#GC#AWAKE#"

        /** Channel 0 for AWAKE notification. */
        const val AWAKE_CHANNEL = 0

        /** Maximum number of messages to retain in the display list. */
        const val MAX_MESSAGE_HISTORY = 100

        /** Default preformatted messages. */
        val DEFAULT_PREFORMATTED_MESSAGES = listOf(
            "On my way",
            "Be there in 5 minutes",
            "At the clubhouse",
            "Heading home",
            "Running late",
            "Need assistance",
            "All clear",
            "Meet at the usual spot"
        )
    }

    /** List of received messages for display. */
    private val _messages = MutableStateFlow<List<DisplayMessage>>(emptyList())
    val messages: StateFlow<List<DisplayMessage>> = _messages.asStateFlow()

    /** Meshtastic connection state. */
    val meshtasticConnectionState: StateFlow<ConnectionState> = meshtasticConnection.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConnectionState.DISCONNECTED
        )

    /** Configurable list of preformatted messages. */
    private val _preformattedMessages = MutableStateFlow(DEFAULT_PREFORMATTED_MESSAGES)
    val preformattedMessages: StateFlow<List<String>> = _preformattedMessages.asStateFlow()

    /** Tracks whether AWAKE has been sent for the current connection session. */
    private var awakeSentForSession = false

    /** Time formatter for message timestamps. */
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.US)

    init {
        observeIncomingMessages()
        observeConnectionForAwake()
    }

    /**
     * Observes incoming text messages from the Meshtastic message handler
     * and adds them to the display list.
     *
     * Requirement 2.1: Display received messages with sender node ID, channel, timestamp.
     * Requirement 2.2: Support broadcast and direct message display.
     */
    private fun observeIncomingMessages() {
        viewModelScope.launch {
            messageHandler.textMessages.collect { incomingMessage: MeshtasticMessageHandler.IncomingTextMessage ->
                val displayMessage = mapToDisplayMessage(incomingMessage)
                addMessage(displayMessage)
                // Play notification tone for new message (Requirement 14.2)
                audioManager.playMessageNotification()
                Log.d(TAG, "Received message from ${displayMessage.senderNodeId}: " +
                        "'${displayMessage.text.take(30)}'")
            }
        }
    }

    /**
     * Observes the Meshtastic connection state and sends the AWAKE notification
     * when the connection transitions to READY.
     *
     * Requirement 2.8: Send AWAKE notification (`~#01#GC#AWAKE#`) on connection establishment.
     */
    private fun observeConnectionForAwake() {
        viewModelScope.launch {
            meshtasticConnection.connectionState.collect { state ->
                when (state) {
                    ConnectionState.READY -> {
                        if (!awakeSentForSession) {
                            sendAwakeNotification()
                            awakeSentForSession = true
                        }
                    }
                    ConnectionState.DISCONNECTED -> {
                        // Reset so AWAKE is sent again on next connection
                        awakeSentForSession = false
                    }
                    else -> { /* no action for intermediate states */ }
                }
            }
        }
    }

    /**
     * Sends the AWAKE notification message to broadcast on channel 0.
     *
     * Requirement 2.8: `~#01#GC#AWAKE#` sent on channel 0 to broadcast on connection.
     */
    private fun sendAwakeNotification() {
        viewModelScope.launch {
            try {
                meshtasticConnection.sendTextMessage(
                    text = AWAKE_MESSAGE,
                    destination = MeshtasticConstants.BROADCAST_ADDRESS,
                    channel = AWAKE_CHANNEL
                )
                Log.i(TAG, "Sent AWAKE notification on channel $AWAKE_CHANNEL")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send AWAKE notification", e)
            }
        }
    }

    /**
     * Sends a broadcast text message on channel 0.
     *
     * Requirement 2.3: Support sending text messages to broadcast address on any configured channel.
     */
    fun sendBroadcastMessage(text: String) {
        viewModelScope.launch {
            try {
                meshtasticConnection.sendTextMessage(
                    text = text,
                    destination = MeshtasticConstants.BROADCAST_ADDRESS,
                    channel = 0
                )
                audioManager.playConfirmation()
                Log.i(TAG, "Sent broadcast message: '${text.take(30)}'")
            } catch (e: MeshtasticMessageHandler.PayloadTooLargeException) {
                audioManager.playError()
                Log.e(TAG, "Message too large to send", e)
            } catch (e: Exception) {
                audioManager.playError()
                Log.e(TAG, "Failed to send broadcast message", e)
            }
        }
    }

    /**
     * Sends a direct text message to a specific node number on channel 0.
     *
     * Requirement 2.4: Support sending direct messages to specific node numbers.
     */
    fun sendDirectMessage(text: String, destination: Long) {
        viewModelScope.launch {
            try {
                meshtasticConnection.sendTextMessage(
                    text = text,
                    destination = destination,
                    channel = 0
                )
                audioManager.playConfirmation()
                Log.i(TAG, "Sent direct message to ${destination.toString(16)}: '${text.take(30)}'")
            } catch (e: MeshtasticMessageHandler.PayloadTooLargeException) {
                audioManager.playError()
                Log.e(TAG, "Message too large to send", e)
            } catch (e: Exception) {
                audioManager.playError()
                Log.e(TAG, "Failed to send direct message", e)
            }
        }
    }

    /**
     * Updates the list of preformatted messages.
     *
     * Requirement 2.5: Preformatted messages selectable from a configurable list.
     */
    fun updatePreformattedMessages(messages: List<String>) {
        _preformattedMessages.value = messages
    }

    /**
     * Maps an incoming text message to a display-friendly format.
     *
     * Converts the sender node number to hex format (!a1b2c3d4) and determines
     * whether the message is broadcast or direct.
     */
    private fun mapToDisplayMessage(incoming: MeshtasticMessageHandler.IncomingTextMessage): DisplayMessage {
        val senderHex = formatNodeId(incoming.from)
        val isBroadcast = incoming.to == MeshtasticConstants.BROADCAST_ADDRESS
        val timestamp = timeFormatter.format(Date())

        return DisplayMessage(
            senderNodeId = senderHex,
            channel = incoming.channel,
            timestamp = timestamp,
            text = incoming.text,
            isBroadcast = isBroadcast
        )
    }

    /**
     * Formats a node number as a hex string in Meshtastic format (e.g., "!a1b2c3d4").
     */
    private fun formatNodeId(nodeNum: Long): String {
        return "!${nodeNum.toString(16).padStart(8, '0')}"
    }

    /**
     * Adds a message to the display list, trimming old messages if the list exceeds
     * [MAX_MESSAGE_HISTORY].
     */
    private fun addMessage(message: DisplayMessage) {
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(message)
        if (currentMessages.size > MAX_MESSAGE_HISTORY) {
            currentMessages.removeAt(0)
        }
        _messages.value = currentMessages
    }
}
