package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    viewModel: MainViewModel
) {
    val focusManager = LocalFocusManager.current
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var useCloudServer by remember { mutableStateOf(false) }
    var cloudServerUrl by remember { mutableStateOf("https://whatschat-backend.herokuapp.com") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(WhatsBackground, WhatsCardDark)
                )
            )
            .windowInsetsPadding(WindowInsets.safeContent)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App branding icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(WhatsGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "WhatsChat Shield Logo",
                    tint = WhatsGreen,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "WhatsChat Secure",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "End-to-End Encrypted Identity",
                style = MaterialTheme.typography.bodyMedium,
                color = WhatsGreen,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Choose an email and a unique username to initialize your cryptographic vault keys. Your username is your sync identity.",
                style = MaterialTheme.typography.bodySmall,
                color = WhatsGrayText,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Email Input Card
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    errorMessage = null
                },
                label = { Text("Email Address") },
                placeholder = { Text("example@domain.com") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Email Icon",
                        tint = if (email.isNotEmpty()) WhatsGreen else WhatsGrayText
                    )
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WhatsGreen,
                    unfocusedBorderColor = WhatsCardDark,
                    focusedLabelColor = WhatsGreen,
                    cursorColor = WhatsGreen,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = WhatsCardDark.copy(alpha = 0.5f),
                    unfocusedContainerColor = WhatsCardDark.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signup_email_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Username Input Card
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it.filter { char -> !char.isWhitespace() }
                    errorMessage = null
                },
                label = { Text("Unique Username") },
                placeholder = { Text("username") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = "Username Icon",
                        tint = if (username.length >= 3) WhatsGreen else WhatsGrayText
                    )
                },
                trailingIcon = {
                    if (username.length >= 3 && errorMessage == null) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Username Valid",
                            tint = WhatsGreen
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WhatsGreen,
                    unfocusedBorderColor = WhatsCardDark,
                    focusedLabelColor = WhatsGreen,
                    cursorColor = WhatsGreen,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = WhatsCardDark.copy(alpha = 0.5f),
                    unfocusedContainerColor = WhatsCardDark.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signup_username_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Cloud Server Config Card
            Card(
                colors = CardDefaults.cardColors(containerColor = WhatsCardDark.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 0.5.dp,
                    color = if (useCloudServer) WhatsGreen.copy(alpha = 0.4f) else WhatsCardDark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = useCloudServer,
                            onCheckedChange = { useCloudServer = it },
                            colors = CheckboxDefaults.colors(checkedColor = WhatsGreen, checkmarkColor = Color.Black)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "Initialize Cloud Network",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Connect to a central MongoDB & Socket.io server",
                                color = WhatsGrayText,
                                fontSize = 11.sp
                            )
                        }
                    }

                    AnimatedVisibility(visible = useCloudServer) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = cloudServerUrl,
                                onValueChange = { cloudServerUrl = it },
                                label = { Text("Server Address") },
                                placeholder = { Text("https://your-heroku-app.herokuapp.com") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = WhatsGreen,
                                    unfocusedBorderColor = WhatsBackground,
                                    focusedLabelColor = WhatsGreen,
                                    cursorColor = WhatsGreen,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = WhatsCardDark.copy(alpha = 0.5f),
                                    unfocusedContainerColor = WhatsCardDark.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Supports localhost (e.g. http://10.0.2.2:5000 in emulator) or production Heroku URLs.",
                                color = WhatsGrayText,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Validation message transition
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                errorMessage?.let {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4A121A)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = it,
                            color = Color(0xFFFFB4AB),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Register Action Button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    isLoading = true
                    viewModel.signUpUser(email, username, useCloudServer, cloudServerUrl) { success, error ->
                        isLoading = false
                        if (success) {
                            isSuccess = true
                        } else {
                            errorMessage = error
                        }
                    }
                },
                enabled = !isLoading && email.isNotBlank() && username.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhatsGreen,
                    disabledContainerColor = WhatsGreen.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("signup_submit_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = WhatsTealDark,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = "Create Encrypted Account",
                        color = if (email.isNotBlank() && username.isNotBlank()) WhatsTealDark else Color.White.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Encrypted note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock",
                    tint = WhatsGrayText,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Cryptographic keys are computed and verified entirely on-device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WhatsGrayText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
