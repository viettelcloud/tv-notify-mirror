package com.example

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.phone.PhoneSenderScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.tv.TvReceiverScreen

enum class AppMode {
    PHONE_SENDER,
    TV_RECEIVER
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTelevision = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        setContent {
            MyApplicationTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var currentMode by remember {
                    mutableStateOf(if (isTelevision) AppMode.TV_RECEIVER else AppMode.PHONE_SENDER)
                }

                BackHandler(enabled = currentMode == AppMode.TV_RECEIVER && !isTelevision) {
                    currentMode = AppMode.PHONE_SENDER
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A))
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        ModeSwitcherBottomBar(
                            currentMode = currentMode,
                            onModeSelected = { currentMode = it }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Crossfade(targetState = currentMode, label = "mode_crossfade") { mode ->
                            when (mode) {
                                AppMode.PHONE_SENDER -> {
                                    PhoneSenderScreen(
                                        snackbarHostState = snackbarHostState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                AppMode.TV_RECEIVER -> {
                                    TvReceiverScreen(
                                        snackbarHostState = snackbarHostState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeSwitcherBottomBar(
    currentMode: AppMode,
    onModeSelected: (AppMode) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mode_switcher_bar"),
        color = Color(0xFF1E293B),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeOptionItem(
                label = "Phone Sender",
                icon = Icons.Default.PhoneAndroid,
                isSelected = currentMode == AppMode.PHONE_SENDER,
                testTag = "switch_to_phone_mode",
                onClick = { onModeSelected(AppMode.PHONE_SENDER) }
            )

            ModeOptionItem(
                label = "Google TV Receiver",
                icon = Icons.Default.Tv,
                isSelected = currentMode == AppMode.TV_RECEIVER,
                testTag = "switch_to_tv_mode",
                onClick = { onModeSelected(AppMode.TV_RECEIVER) }
            )
        }
    }
}

@Composable
fun ModeOptionItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF0284C7) else Color.Transparent
    val contentColor = if (isSelected) Color.White else Color(0xFF94A3B8)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = contentColor,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    }
}
