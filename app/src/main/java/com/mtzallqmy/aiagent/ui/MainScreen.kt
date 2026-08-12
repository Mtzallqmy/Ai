package com.mtzallqmy.aiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.agent.AgentCore
import com.mtzallqmy.aiagent.agent.AgentState
import com.mtzallqmy.aiagent.agent.Message
import com.mtzallqmy.aiagent.tools.ToolRegistry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val agentCore = remember { AgentCore() }
    val scope = rememberCoroutineScope()
    
    val state by agentCore.state.collectAsState()
    val messages by agentCore.messages.collectAsState()
    
    var inputPrompt by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Chat, 1: Capabilities, 2: Settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aegis AI Agent OS") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Chat, contentDescription = "المحادثة والوكيل") },
                    label = { Text("الوكيل") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = "الأدوات والقدرات") },
                    label = { Text("الأدوات") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "الإعدادات") },
                    label = { Text("الإعدادات") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("حالة الوكيل: $state", style = MaterialTheme.typography.bodyMedium)
                            if (state != AgentState.IDLE && state != AgentState.COMPLETED && state != AgentState.FAILED) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages) { msg ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (msg.role == "user") 
                                            MaterialTheme.colorScheme.secondaryContainer 
                                        else 
                                            MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = if (msg.role == "user") "المستخدم" else "الوكيل الذكي",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = msg.content, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputPrompt,
                                onValueChange = { inputPrompt = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("اكتب طلبك للوكيل الذكي...") },
                                maxLines = 3
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (inputPrompt.isNotBlank()) {
                                        val prompt = inputPrompt
                                        inputPrompt = ""
                                        scope.launch {
                                            agentCore.runTask(prompt, apiKey, "gpt-4o")
                                        }
                                    }
                                },
                                enabled = state == AgentState.IDLE || state == AgentState.COMPLETED || state == AgentState.FAILED
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "إرسال")
                            }
                        }
                    }
                }
                1 -> {
                    val capabilities = ToolRegistry.getCapabilities()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text("قدرات وأدوات النظام المتاحة", style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        items(capabilities) { cap ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(cap.name, style = MaterialTheme.typography.titleMedium)
                                        Switch(checked = cap.isAvailable, onCheckedChange = {})
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(cap.description, style = MaterialTheme.typography.bodySmall)
                                    Text("معرف القدرة: ${cap.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("إعدادات النماذج والاتصال", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("مفتاح API الخاص بالنموذج (OpenAI / OpenRouter / Ollama)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            "التطبيق يدعم نماذج متعددة ونظام وكلاء ذكي متكامل مع واجهة Material 3 وامتثال كامل لأعلى معايير الأمان والتوافق مع أندرويد 8 فما فوق.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
