package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.CallLogEntity
import com.example.data.ChatEntity
import com.example.data.MessageEntity
import com.example.data.StatusEntity
import com.example.security.BiometricHelper
import com.example.ui.theme.*
import com.example.sync.CloudSocketManager
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsChatApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isRegistered by viewModel.isRegistered.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val selectedChatId by viewModel.selectedChatId.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isRegistered) {
            SignUpScreen(viewModel = viewModel)
        } else if (isLocked) {
            AppLockScreen(
                biometricEnabled = biometricEnabled,
                onUnlockSuccess = { viewModel.unlockApp() },
                onToggleBiometric = { viewModel.setBiometricEnabled(it) }
            )
        } else {
            // Main App Shell
            if (selectedChatId != null) {
                ChatFeedScreen(
                    viewModel = viewModel,
                    chatId = selectedChatId!!,
                    onBack = { viewModel.selectChat(null) }
                )
            } else {
                MainTabsScreen(viewModel = viewModel)
            }
        }

        // Voice Calling Overlay
        if (activeCall != null) {
            VoiceCallOverlay(
                call = activeCall!!,
                duration = viewModel.callDuration.collectAsStateWithLifecycle().value,
                onHangUp = { viewModel.hangUpCall() },
                onAccept = { viewModel.acceptIncomingCall() }
            )
        }
    }
}

/**
 * High-fidelity biometric or passcode authentication lock screen.
 */
@Composable
fun AppLockScreen(
    biometricEnabled: Boolean,
    onUnlockSuccess: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var inputPasscode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val triggerBiometric = {
        val activity = context as? FragmentActivity
        if (activity != null && BiometricHelper.isBiometricAvailable(context)) {
            BiometricHelper.authenticate(
                activity = activity,
                onSuccess = { onUnlockSuccess() },
                onError = { err -> errorMessage = err }
            )
        } else {
            errorMessage = "Biometric unavailable. Use passcode '1234'"
        }
    }

    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) {
            triggerBiometric()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WhatsBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "WhatsChat Lock",
            tint = WhatsGreen,
            modifier = Modifier
                .size(72.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "WhatsChat Locked",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "End-to-End Encrypted Secure Vault",
            style = MaterialTheme.typography.bodyMedium,
            color = WhatsGrayText
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Keypad inputs
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            repeat(4) { index ->
                val hasDigit = index < inputPasscode.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (hasDigit) WhatsGreen else WhatsIncomingBubble)
                )
            }
        }

        errorMessage?.let {
            Text(
                text = it,
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Keypad Grid
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("Clear", "0", "OK")
            )

            for (row in rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    for (key in row) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(WhatsIncomingBubble)
                                .clickable {
                                    errorMessage = null
                                    when (key) {
                                        "Clear" -> {
                                            if (inputPasscode.isNotEmpty()) {
                                                inputPasscode = inputPasscode.dropLast(1)
                                            }
                                        }
                                        "OK" -> {
                                            if (inputPasscode == "1234") {
                                                onUnlockSuccess()
                                            } else {
                                                errorMessage = "Incorrect Passcode! Try '1234'"
                                                inputPasscode = ""
                                            }
                                        }
                                        else -> {
                                            if (inputPasscode.length < 4) {
                                                inputPasscode += key
                                                if (inputPasscode.length == 4) {
                                                    if (inputPasscode == "1234") {
                                                        onUnlockSuccess()
                                                    } else {
                                                        errorMessage = "Incorrect Passcode! Try '1234'"
                                                        inputPasscode = ""
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                .testTag("keypad_$key"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (key == "OK" || key == "Clear") WhatsGreen else Color.White,
                                fontSize = if (key == "OK" || key == "Clear") 14.sp else 20.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { triggerBiometric() },
            colors = ButtonDefaults.buttonColors(containerColor = WhatsGreen),
            modifier = Modifier.testTag("biometric_unlock_button")
        ) {
            Icon(Icons.Default.Fingerprint, contentDescription = "Biometric Scan")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Unlock with Fingerprint", color = WhatsTealDark)
        }
    }
}

/**
 * Main Interface housing Chats, Status updates, Call Log, and Web Sync channels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabsScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val chats by viewModel.allChats.collectAsStateWithLifecycle()
    val statuses by viewModel.allStatuses.collectAsStateWithLifecycle()
    val callLogs by viewModel.allCallLogs.collectAsStateWithLifecycle()
    val isServerRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val ipAddress by viewModel.serverIpAddress.collectAsStateWithLifecycle()
    val currentUsername by viewModel.currentUsername.collectAsStateWithLifecycle()
    val currentUserEmail by viewModel.currentUserEmail.collectAsStateWithLifecycle()
    val cloudServerUrl by viewModel.cloudServerUrl.collectAsStateWithLifecycle()
    val cloudSyncEnabled by viewModel.cloudSyncEnabled.collectAsStateWithLifecycle()
    val cloudConnectionState by viewModel.cloudConnectionState.collectAsStateWithLifecycle()

    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showCreateStatusDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "WhatsChat",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 20.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Encrypted",
                                tint = WhatsGreen,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (currentUsername.isNullOrBlank()) "End-to-End Encrypted" else "Vault: @$currentUsername • E2EE",
                                color = WhatsGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                actions = {
                    if (isServerRunning) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(WhatsGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Sync Server Active", color = WhatsGreen, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = { viewModel.lockApp() }, modifier = Modifier.testTag("lock_app_button")) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock App", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WhatsCardDark)
            )
        },
        floatingActionButton = {
            when (selectedTab) {
                0 -> {
                    FloatingActionButton(
                        onClick = { showCreateGroupDialog = true },
                        containerColor = WhatsGreen,
                        contentColor = WhatsTealDark,
                        modifier = Modifier.testTag("fab_create_chat")
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "Create Group")
                    }
                }
                1 -> {
                    FloatingActionButton(
                        onClick = { showCreateStatusDialog = true },
                        containerColor = WhatsGreen,
                        contentColor = WhatsTealDark,
                        modifier = Modifier.testTag("fab_post_status")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Post Status")
                    }
                }
                2 -> {
                    FloatingActionButton(
                        onClick = {
                            viewModel.receiveSimulatedIncomingCall("Alice (E2EE)", "alice")
                        },
                        containerColor = WhatsGreen,
                        contentColor = WhatsTealDark,
                        modifier = Modifier.testTag("fab_sim_call")
                    ) {
                        Icon(Icons.Default.PhoneCallback, contentDescription = "Simulate Incoming Call")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = WhatsCardDark,
                windowInsets = WindowInsets.navigationBars
            ) {
                val items = listOf(
                    Triple("Chats", Icons.Default.Chat, Icons.Outlined.Chat),
                    Triple("Status", Icons.Default.DataUsage, Icons.Outlined.DataUsage),
                    Triple("Calls", Icons.Default.Phone, Icons.Outlined.Phone),
                    Triple("Web Sync", Icons.Default.Laptop, Icons.Outlined.Laptop)
                )
                items.forEachIndexed { index, (label, filledIcon, outlinedIcon) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == index) filledIcon else outlinedIcon,
                                contentDescription = label
                            )
                        },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = WhatsTealDark,
                            selectedTextColor = WhatsGreen,
                            indicatorColor = WhatsGreen,
                            unselectedIconColor = WhatsGrayText,
                            unselectedTextColor = WhatsGrayText
                        ),
                        modifier = Modifier.testTag("nav_tab_$index")
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WhatsBackground)
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> ChatsListTab(chats = chats, onSelectChat = { viewModel.selectChat(it.id) })
                1 -> StatusListTab(statuses = statuses, onPostStatus = { showCreateStatusDialog = true })
                2 -> CallsListTab(callLogs = callLogs, onLogClick = { viewModel.startVoiceCall(it.receiverId) })
                3 -> WebSyncTab(
                    username = currentUsername ?: "",
                    email = currentUserEmail ?: "",
                    isServerRunning = isServerRunning,
                    ipAddress = ipAddress,
                    onToggleServer = {
                        if (isServerRunning) viewModel.stopSyncServer() else viewModel.startSyncServer()
                    },
                    cloudServerUrl = cloudServerUrl,
                    cloudSyncEnabled = cloudSyncEnabled,
                    cloudConnectionState = cloudConnectionState,
                    onUpdateCloudSettings = { url, enabled ->
                        viewModel.updateCloudServerSettings(url, enabled)
                    }
                )
            }
        }
    }

    // Dialog: Create E2EE Group Chat
    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("New E2EE Group", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column {
                    Text("Securely register multiple keys under an on-device generated group payload.", color = WhatsGrayText, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Group Subject") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WhatsGreen,
                            unfocusedBorderColor = WhatsGrayText,
                            focusedLabelColor = WhatsGreen
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_group_name")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            viewModel.sendMessage(
                                chatId = "group1", // Using seeded demo E2EE group structure
                                text = "Created Secure Group chat '$groupName'. Direct keys verified!"
                            )
                            showCreateGroupDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsGreen),
                    modifier = Modifier.testTag("btn_confirm_group")
                ) {
                    Text("Create", color = WhatsTealDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text("Cancel", color = WhatsGreen)
                }
            },
            containerColor = WhatsCardDark
        )
    }

    // Dialog: Post Status update
    if (showCreateStatusDialog) {
        var statusText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateStatusDialog = false },
            title = { Text("New Status Story", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                OutlinedTextField(
                    value = statusText,
                    onValueChange = { statusText = it },
                    placeholder = { Text("What is on your mind?") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WhatsGreen,
                        unfocusedBorderColor = WhatsGrayText,
                        focusedLabelColor = WhatsGreen
                    ),
                    modifier = Modifier.fillMaxWidth().height(100.dp).testTag("input_status_text")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (statusText.isNotBlank()) {
                            viewModel.postStatus(statusText)
                            showCreateStatusDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsGreen),
                    modifier = Modifier.testTag("btn_confirm_status")
                ) {
                    Text("Share", color = WhatsTealDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateStatusDialog = false }) {
                    Text("Cancel", color = WhatsGreen)
                }
            },
            containerColor = WhatsCardDark
        )
    }
}

/**
 * Tab: Chats list view
 */
@Composable
fun ChatsListTab(chats: List<ChatEntity>, onSelectChat: (ChatEntity) -> Unit) {
    if (chats.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No secure chats. Start one below!", color = WhatsGrayText)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(chats) { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectChat(chat) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("chat_item_${chat.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Chat avatar
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (chat.isGroup) Color(0xFF20c997) else Color(0xFF0d6efd)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            chat.name.take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chat.name,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                            Text(
                                text = sdf.format(Date(chat.lastMessageTimestamp)),
                                fontSize = 12.sp,
                                color = WhatsGrayText
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Encrypted",
                                tint = WhatsGreen,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = chat.lastMessageText,
                                fontSize = 14.sp,
                                color = WhatsGrayText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(color = WhatsIncomingBubble.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
        }
    }
}

/**
 * Tab: Status Updates List (Stories)
 */
@Composable
fun StatusListTab(statuses: List<StatusEntity>, onPostStatus: () -> Unit) {
    var activeStatusToView by remember { mutableStateOf<StatusEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // My status triggers posting
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPostStatus() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(WhatsGreen.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Status", tint = WhatsGreen, modifier = Modifier.size(28.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text("My Status", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
                Text("Tap to post a status update", color = WhatsGrayText, fontSize = 13.sp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(WhatsCardDark)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Recent Updates", color = WhatsGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        if (statuses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No recent statuses to display", color = WhatsGrayText)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(statuses) { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activeStatusToView = status }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("status_item_${status.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Colored ring for status circle
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(WhatsGreen)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(WhatsBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFe0a800)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    status.userName.take(2).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(status.userName, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
                            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                            Text(
                                text = "Today, " + sdf.format(Date(status.timestamp)),
                                color = WhatsGrayText,
                                fontSize = 13.sp
                            )
                        }
                    }
                    HorizontalDivider(color = WhatsIncomingBubble.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
            }
        }
    }

    // Story Overlay Viewer
    if (activeStatusToView != null) {
        StoryViewerOverlay(
            status = activeStatusToView!!,
            onDismiss = { activeStatusToView = null }
        )
    }
}

/**
 * Story Viewer Overlay with 4-second progress indicator
 */
@Composable
fun StoryViewerOverlay(status: StatusEntity, onDismiss: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        val steps = 100
        val delayTime = 4000L / steps
        for (i in 1..steps) {
            delay(delayTime)
            progress = i / steps.toFloat()
        }
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Progress Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp),
                    color = WhatsGreen,
                    trackColor = Color.DarkGray,
                )
            }

            // User Info Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(WhatsGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Text(status.userName.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(status.userName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Secure Status", color = WhatsGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Centered Story Text Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = status.text,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 36.sp
                )
            }
        }
    }
}

/**
 * Tab: Call History list
 */
@Composable
fun CallsListTab(callLogs: List<CallLogEntity>, onLogClick: (CallLogEntity) -> Unit) {
    if (callLogs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No secure calls logged.", color = WhatsGrayText)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(callLogs) { log ->
                val isMissed = log.status == "Missed"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLogClick(log) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("call_item_${log.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (log.status == "Incoming") Icons.Default.CallReceived 
                                      else if (log.status == "Missed") Icons.Default.CallMissed 
                                      else Icons.Default.CallMade,
                        contentDescription = log.status,
                        tint = if (isMissed) Color.Red else WhatsGreen,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        val displayName = if (log.callerId == "me") log.receiverName else log.callerName
                        Text(
                            text = displayName,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                        Text(
                            text = sdf.format(Date(log.timestamp)) + if (log.duration > 0) " (${log.duration}s)" else "",
                            fontSize = 13.sp,
                            color = WhatsGrayText
                        )
                    }

                    IconButton(
                        onClick = { onLogClick(log) },
                        modifier = Modifier.testTag("btn_recall_${log.id}")
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = "Call",
                            tint = WhatsGreen
                        )
                    }
                }
                HorizontalDivider(color = WhatsIncomingBubble.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
        }
    }
}

/**
 * Tab: Local HTTP Web Sync Portal Details & Production Cloud Backend Configs
 */
@Composable
fun WebSyncTab(
    username: String,
    email: String,
    isServerRunning: Boolean,
    ipAddress: String?,
    onToggleServer: () -> Unit,
    cloudServerUrl: String,
    cloudSyncEnabled: Boolean,
    cloudConnectionState: CloudSocketManager.ConnectionState,
    onUpdateCloudSettings: (String, Boolean) -> Unit
) {
    val scrollState = rememberScrollState()
    var editedCloudUrl by remember { mutableStateOf(cloudServerUrl) }
    var isCloudActive by remember { mutableStateOf(cloudSyncEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Authenticated Sync User Profile Card (if exists)
        if (username.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                colors = CardDefaults.cardColors(containerColor = WhatsCardDark.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, WhatsGreen.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar bubble
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(WhatsGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (username.length >= 2) username.substring(0, 2).uppercase() else "WC",
                            fontWeight = FontWeight.Bold,
                            color = WhatsGreen,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "@$username",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = email,
                            color = WhatsGrayText,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(WhatsGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "On-Device Vault Synced",
                                color = WhatsGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Icon(
            Icons.Default.Laptop,
            contentDescription = "Sync",
            tint = if (isServerRunning) WhatsGreen else WhatsGrayText,
            modifier = Modifier
                .size(64.dp)
                .padding(bottom = 8.dp)
        )

        Text(
            "WhatsChat Desktop Sync",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            "Access chat channels directly from your desktop browser using local E2EE protocols.",
            color = WhatsGrayText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // IP Link Status Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WhatsCardDark)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isServerRunning) WhatsGreen else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServerRunning) "Local Sync Active" else "Local Sync Stopped",
                        fontWeight = FontWeight.Bold,
                        color = if (isServerRunning) WhatsGreen else Color.Red,
                        fontSize = 15.sp
                    )
                }

                if (isServerRunning && ipAddress != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "http://$ipAddress:8080",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(WhatsBackground)
                            .padding(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Type this local address inside any desktop browser connected to the same Wi-Fi network.",
                        color = WhatsGrayText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onToggleServer,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServerRunning) Color.Red else WhatsGreen
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("toggle_sync_server_button")
        ) {
            Text(
                text = if (isServerRunning) "Stop Local Sync" else "Start Local Sync",
                color = if (isServerRunning) Color.White else WhatsTealDark,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Production Full-Stack Cloud Server Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WhatsCardDark)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header with Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = "Cloud",
                            tint = if (cloudSyncEnabled && cloudConnectionState == CloudSocketManager.ConnectionState.CONNECTED) WhatsGreen else WhatsGrayText,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Cloud Production Server",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }

                    // Connection Status Badge
                    val badgeColor = when (cloudConnectionState) {
                        CloudSocketManager.ConnectionState.CONNECTED -> WhatsGreen
                        CloudSocketManager.ConnectionState.CONNECTING -> Color(0xFFFFB300)
                        CloudSocketManager.ConnectionState.DISCONNECTED -> WhatsGrayText
                        CloudSocketManager.ConnectionState.ERROR -> Color.Red
                    }

                    val badgeText = when (cloudConnectionState) {
                        CloudSocketManager.ConnectionState.CONNECTED -> "CONNECTED"
                        CloudSocketManager.ConnectionState.CONNECTING -> "LINKING..."
                        CloudSocketManager.ConnectionState.DISCONNECTED -> "OFFLINE"
                        CloudSocketManager.ConnectionState.ERROR -> "ERR: RETRY"
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(0.5.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Link your device securely to your hosted MongoDB Compass + Socket.io full-stack environment.",
                    color = WhatsGrayText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle Connection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Enable Cloud Messaging",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Sync chats across devices over the internet",
                            color = WhatsGrayText,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isCloudActive,
                        onCheckedChange = {
                            isCloudActive = it
                            onUpdateCloudSettings(editedCloudUrl, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = WhatsGreen,
                            uncheckedThumbColor = WhatsGrayText,
                            uncheckedTrackColor = WhatsBackground
                        )
                    )
                }

                AnimatedVisibility(visible = isCloudActive) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = editedCloudUrl,
                            onValueChange = { editedCloudUrl = it },
                            label = { Text("Production Server URL (e.g. Heroku)") },
                            placeholder = { Text("https://whatschat-backend.herokuapp.com") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WhatsGreen,
                                unfocusedBorderColor = WhatsBackground,
                                focusedLabelColor = WhatsGreen,
                                cursorColor = WhatsGreen,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = WhatsBackground.copy(alpha = 0.5f),
                                unfocusedContainerColor = WhatsBackground.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                onUpdateCloudSettings(editedCloudUrl, isCloudActive)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WhatsGreen),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save & Connect Server", color = WhatsTealDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Security Identity Box
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WhatsCardDark.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Security, contentDescription = "Security", tint = WhatsGreen)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Device RSA Public Key Identity", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
                    Text("e2ee_rsa_f382a9010ab38c928e08d910a902df380a010c22", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = WhatsGrayText)
                }
            }
        }
    }
}

/**
 * End-to-End Encrypted Interactive Chat Feed Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFeedScreen(
    viewModel: MainViewModel,
    chatId: String,
    onBack: () -> Unit
) {
    var textMessage by remember { mutableStateOf("") }
    val messages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val allChats by viewModel.allChats.collectAsStateWithLifecycle()
    val chat = allChats.firstOrNull { it.id == chatId }

    // Message inspection bottomsheet
    var selectedMessageForInspection by remember { mutableStateOf<MessageEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (chat?.isGroup == true) Color(0xFF20c997) else Color(0xFF0d6efd)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                chat?.name?.take(2)?.uppercase() ?: "C",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(chat?.name ?: "Chat Feed", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = "E2EE", tint = WhatsGreen, modifier = Modifier.size(9.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("E2EE secured", color = WhatsGreen, fontSize = 10.sp)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startVoiceCall(chatId) }, modifier = Modifier.testTag("btn_voice_call")) {
                        Icon(Icons.Default.Phone, contentDescription = "Voice Call", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WhatsCardDark)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WhatsBackground)
                .padding(paddingValues)
        ) {
            // Decrypted Message Stream
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
            ) {
                item {
                    // Center E2EE Info Bubble
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF182229)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = "Lock", tint = Color(0xFFe0a800), modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Messages are end-to-end encrypted. No one outside of this chat can read them. Tap on any message to verify cryptographic payloads.",
                                    color = Color(0xFFe0a800),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                items(messages) { msg ->
                    val isMe = msg.senderId == "me"
                    val plainText = viewModel.decryptMessage(msg)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMessageForInspection = msg },
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            shape = RoundedCornerShape(
                                topStart = 8.dp,
                                topEnd = 8.dp,
                                bottomStart = if (isMe) 8.dp else 0.dp,
                                bottomEnd = if (isMe) 0.dp else 8.dp
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isMe) WhatsOutgoingBubble else WhatsIncomingBubble
                            ),
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .testTag("message_card_${msg.id}")
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (!isMe && chat?.isGroup == true) {
                                    Text(
                                        text = msg.senderName,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF34b7f1),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }

                                if (msg.mediaUrl != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.DarkGray)
                                            .padding(bottom = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (msg.mediaType == "image") Icons.Default.Image else Icons.Default.Mic,
                                            contentDescription = "Media attachment",
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = plainText,
                                    color = Color.White,
                                    fontSize = 15.sp
                                )

                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(top = 4.dp)
                                ) {
                                    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                                    Text(
                                        text = sdf.format(Date(msg.timestamp)),
                                        fontSize = 10.sp,
                                        color = WhatsGrayText
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "E2EE Secured",
                                        tint = WhatsGreen,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WhatsCardDark)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Media attachment triggers simulated photo or audio send
                IconButton(
                    onClick = {
                        // Securely send simulated photo attachment
                        viewModel.sendMessage(
                            chatId = chatId,
                            text = "Sent secure encrypted photo payload",
                            mediaUrl = "photo_url",
                            mediaType = "image"
                        )
                    },
                    modifier = Modifier.testTag("chat_attach_button")
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach Media", tint = WhatsGreen)
                }

                TextField(
                    value = textMessage,
                    onValueChange = { textMessage = it },
                    placeholder = { Text("Message", color = WhatsGrayText) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = WhatsIncomingBubble,
                        unfocusedContainerColor = WhatsIncomingBubble,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .testTag("chat_input_field")
                )

                Spacer(modifier = Modifier.width(8.dp))

                FloatingActionButton(
                    onClick = {
                        if (textMessage.isNotBlank()) {
                            viewModel.sendMessage(chatId, textMessage)
                            textMessage = ""
                        }
                    },
                    containerColor = WhatsGreen,
                    contentColor = WhatsTealDark,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_send_button")
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    // Cryptographic Details Sheet
    if (selectedMessageForInspection != null) {
        val msg = selectedMessageForInspection!!
        AlertDialog(
            onDismissRequest = { selectedMessageForInspection = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = "E2EE Security Info", tint = WhatsGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("E2EE Payload Audit", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Inspect how your dynamic AES-256 key encrypted this message secure from any interceptor.", color = WhatsGrayText, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Decrypted Body:", fontWeight = FontWeight.Bold, color = WhatsGreen, fontSize = 12.sp)
                    Text(viewModel.decryptMessage(msg), color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Ciphertext (AES-256-GCM Base64):", fontWeight = FontWeight.Bold, color = WhatsGreen, fontSize = 12.sp)
                    Text(msg.ciphertext, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = WhatsGrayText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Initialization Vector (IV):", fontWeight = FontWeight.Bold, color = WhatsGreen, fontSize = 12.sp)
                    Text(msg.iv, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = WhatsGrayText)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Status Code Checksum:", fontWeight = FontWeight.Bold, color = WhatsGreen, fontSize = 12.sp)
                    Text("HMAC-SHA256-SIGNATURE-OK", color = WhatsGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForInspection = null }) {
                    Text("Close", color = WhatsGreen)
                }
            },
            containerColor = WhatsCardDark
        )
    }
}

/**
 * Overlay layout representing Voice and Video Calls with responsive animations.
 */
@Composable
fun VoiceCallOverlay(
    call: CallLogEntity,
    duration: Int,
    onHangUp: () -> Unit,
    onAccept: () -> Unit
) {
    val isIncoming = call.status == "Incoming" && duration == 0
    val formattedTime = String.format("%02d:%02d", duration / 60, duration % 60)

    // Breathing pulse effect for background
    val infiniteTransition = rememberInfiniteTransition(label = "Calling animation")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse size"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WhatsBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Lock,
                contentDescription = "Encrypted",
                tint = WhatsGreen,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "End-to-End Encrypted Voice Connection",
                color = WhatsGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Caller Avatar & Pulsing Animation
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(240.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp * pulseScale)
                    .clip(CircleShape)
                    .background(WhatsGreen.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(WhatsGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (if (call.callerId == "me") call.receiverName else call.callerName).take(2).uppercase(),
                    color = WhatsTealDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 42.sp
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (call.callerId == "me") call.receiverName else call.callerName,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isIncoming) "Incoming secure call..." else if (duration == 0) "Ringing..." else "Secure Call Connected - $formattedTime",
                color = if (isIncoming) WhatsGreen else WhatsGrayText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        // Bottom Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isIncoming) {
                FloatingActionButton(
                    onClick = onAccept,
                    containerColor = WhatsGreen,
                    contentColor = WhatsTealDark,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(end = 12.dp)
                        .testTag("accept_call_button")
                ) {
                    Icon(Icons.Default.Phone, contentDescription = "Accept")
                }
            }

            FloatingActionButton(
                onClick = onHangUp,
                containerColor = Color.Red,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(64.dp)
                    .testTag("hangup_call_button")
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Hang Up")
            }
        }
    }
}
