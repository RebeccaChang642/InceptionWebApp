package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickAddActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Get database access
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.luodiDao()

        setContent {
            val colorScheme = darkColorScheme(
                primary = Color(0xFF80CBB5),       // Sage Mint Accent
                onPrimary = Color(0xFF1B3D34),
                secondary = Color(0xFF2E7D32),
                background = Color(0xFF0F1216),    // Dark Grounding Slate
                surface = Color(0xFF1B202A),       // Inner cards
                onBackground = Color(0xFFE2E8F0),
                onSurface = Color(0xFFE2E8F0)
            )

            MaterialTheme(colorScheme = colorScheme) {
                var thoughtText by remember { mutableStateOf("") }
                var selectedType by remember { mutableStateOf("REVIEW") } // "REVIEW" or "FOCUS"
                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current

                // Request focus immediately to pop up keyboard
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }

                // Full screen overlay with dialog in center
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .imePadding()
                        .navigationBarsPadding()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // Finish if clicked outside the dialog card
                            finish()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Dialog Card container
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // Block clicks from falling through
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "快速新增當下念頭 🧠",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Type Selection (Segmented-like style)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F1216), RoundedCornerShape(8.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = { selectedType = "REVIEW" },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selectedType == "REVIEW") Color(0xFF2D333F) else Color.Transparent,
                                        contentColor = if (selectedType == "REVIEW") Color(0xFF80CBB5) else Color(0xFF94A3B8)
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text("零碎念頭", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { selectedType = "FOCUS" },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selectedType == "FOCUS") Color(0xFF2D333F) else Color.Transparent,
                                        contentColor = if (selectedType == "FOCUS") Color(0xFF80CBB5) else Color(0xFF94A3B8)
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text("專注念頭", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Text Input Field
                            OutlinedTextField(
                                value = thoughtText,
                                onValueChange = { thoughtText = it },
                                placeholder = { Text("寫下當下的念頭...", color = Color(0xFF64748B)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2D333F),
                                    focusedContainerColor = Color(0xFF0F1216),
                                    unfocusedContainerColor = Color(0xFF0F1216),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )

                            // Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { finish() }
                                ) {
                                    Text("取消", color = Color(0xFF94A3B8))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (thoughtText.trim().isNotEmpty()) {
                                            val textToAdd = thoughtText.trim()
                                            val typeToAdd = selectedType
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val newThought = Thought(
                                                    title = textToAdd,
                                                    type = typeToAdd,
                                                    isDeadline = false,
                                                    dueDate = null,
                                                    status = "PENDING"
                                                )
                                                dao.insertThought(newThought)
                                                
                                                // Update widgets in parallel
                                                LuodiAppWidgetProvider.updateWidgets(applicationContext)
                                                
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "已新增念頭：$textToAdd",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    finish()
                                                }
                                            }
                                        } else {
                                            Toast.makeText(applicationContext, "念頭內容不能為空", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("新增", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
