package com.golfcart.gcd.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golfcart.gcd.data.bluetooth.ConnectionState

/**
 * Data class representing a received Meshtastic text message for display.
 *
 * @param senderNodeId The sender's node ID in hex format (e.g., "!a1b2c3d4").
 * @param channel The channel number the message was received on.
 * @param timestamp The time the message was received (formatted string).
 * @param text The message text content.
 * @param isBroadcast True if the message was sent to the broadcast address.
 */
data class DisplayMessage(
    val senderNodeId: String,
    val channel: Int,
    val timestamp: String,
    val text: String,
    val isBroadcast: Boolean
)

/**
 * Meshtastic messaging UI screen.
 *
 * Displays received messages with sender node ID, channel number, and timestamp.
 * Supports broadcast and direct message display. Provides preformatted message
 * selection from a configurable list and custom message composition via on-screen keyboard.
 * Sends AWAKE notification (`~#01#GC#AWAKE#`) on connection establishment.
 *
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.8
 */
@Composable
fun MessagingScreen(
    viewModel: MessagingViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectionState by viewModel.meshtasticConnectionState.collectAsStateWithLifecycle()
    val preformattedMessages by viewModel.preformattedMessages.collectAsStateWithLifecycle()

    MessagingScreenContent(
        messages = messages,
        connectionState = connectionState,
        preformattedMessages = preformattedMessages,
        onSendMessage = { text -> viewModel.sendBroadcastMessage(text) },
        onSendDirectMessage = { text, destination -> viewModel.sendDirectMessage(text, destination) }
    )
}

/**
 * Stateless content composable for the messaging screen.
 * Separated from [MessagingScreen] for testability and preview support.
 */
@Composable
fun MessagingScreenContent(
    messages: List<DisplayMessage>,
    connectionState: ConnectionState,
    preformattedMessages: List<String>,
    onSendMessage: (String) -> Unit,
    onSendDirectMessage: (String, Long) -> Unit
) {
    var customMessageText by remember { mutableStateOf("") }
    var showPreformattedMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header with connection status
            MessagingHeader(connectionState = connectionState)

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages) { message ->
                    MessageCard(message = message)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            // Preformatted message selection
            PreformattedMessageSelector(
                preformattedMessages = preformattedMessages,
                expanded = showPreformattedMenu,
                onExpandedChange = { showPreformattedMenu = it },
                onMessageSelected = { selectedMessage ->
                    onSendMessage(selectedMessage)
                    showPreformattedMenu = false
                },
                enabled = connectionState == ConnectionState.READY
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Custom message composition
            CustomMessageComposer(
                text = customMessageText,
                onTextChange = { customMessageText = it },
                onSend = {
                    if (customMessageText.isNotBlank()) {
                        onSendMessage(customMessageText.trim())
                        customMessageText = ""
                        keyboardController?.hide()
                    }
                },
                enabled = connectionState == ConnectionState.READY
            )
        }
    }
}

/**
 * Header row showing "Messages" title and Meshtastic connection status.
 */
@Composable
private fun MessagingHeader(connectionState: ConnectionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Messages",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(messagingConnectionColor(connectionState))
            )
            Text(
                text = connectionStateLabel(connectionState),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Card displaying a single received message with sender, channel, timestamp, and text.
 *
 * Requirement 2.1: Display message with sender node ID, channel number, and timestamp.
 * Requirement 2.2: Support broadcast and direct message display.
 */
@Composable
private fun MessageCard(message: DisplayMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isBroadcast) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Top row: sender node ID, channel, broadcast/direct indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Sender node ID in hex format
                    Text(
                        text = message.senderNodeId,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (message.isBroadcast) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )

                    // Channel number
                    Text(
                        text = "CH ${message.channel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isBroadcast) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        }
                    )

                    // Broadcast/Direct indicator
                    Text(
                        text = if (message.isBroadcast) "Broadcast" else "Direct",
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = if (message.isBroadcast) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        }
                    )
                }

                // Timestamp
                Text(
                    text = message.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isBroadcast) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Message text content
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isBroadcast) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
        }
    }
}

/**
 * Preformatted message selector with dropdown menu.
 *
 * Requirement 2.5: Support sending preformatted messages selectable from a configurable list.
 */
@Composable
private fun PreformattedMessageSelector(
    preformattedMessages: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMessageSelected: (String) -> Unit,
    enabled: Boolean
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled && preformattedMessages.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Send Preformatted Message",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            preformattedMessages.forEach { message ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = message,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onMessageSelected(message) }
                )
            }
        }
    }
}

/**
 * Custom message composition field with send button.
 *
 * Requirement 2.6: Support composing custom text messages via on-screen keyboard.
 * Requirement 2.3: Support sending text messages to broadcast address.
 */
@Composable
private fun CustomMessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "Type a message...",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            enabled = enabled,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            shape = RoundedCornerShape(12.dp)
        )

        Button(
            onClick = onSend,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.height(56.dp)
        ) {
            Text(text = "Send")
        }
    }
}

/**
 * Maps a [ConnectionState] to a color for the messaging screen indicator.
 */
private fun messagingConnectionColor(state: ConnectionState): Color {
    return when (state) {
        ConnectionState.READY -> Color(0xFF4CAF50)          // Green
        ConnectionState.CONNECTED,
        ConnectionState.HANDSHAKING -> Color(0xFFFFC107)    // Amber
        ConnectionState.SCANNING,
        ConnectionState.CONNECTING -> Color(0xFF2196F3)     // Blue
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)   // Red
    }
}

/**
 * Maps a [ConnectionState] to a human-readable label.
 */
private fun connectionStateLabel(state: ConnectionState): String {
    return when (state) {
        ConnectionState.READY -> "Connected"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.HANDSHAKING -> "Handshaking"
        ConnectionState.SCANNING -> "Scanning"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }
}
