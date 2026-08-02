package app.mobile.presentation.feature.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkBlueStart = Color(0xFF0B101E)
private val PurpleEnd = Color(0xFF311545)

@Composable
fun StartupScreen(
    onSyncClick: () -> Unit,
    onThemeToggle: () -> Unit,
    isDark: Boolean
) {
    val headerFooterGradient = Brush.horizontalGradient(
        colors = listOf(DarkBlueStart, PurpleEnd)
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerFooterGradient)
                    .statusBarsPadding()
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .heightIn(min = 25.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isDark) "🌙" else "☀️",
                    fontSize = 24.sp,
                    modifier = Modifier.clickable { onThemeToggle() }
                )
                Text(
                    text = "⚙️",
                    fontSize = 24.sp
                )
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerFooterGradient)
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .heightIn(min = 10.dp)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(if (isDark) Color(0xFF121212) else Color(0xFFF5F5F5)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🔄",
                    fontSize = 80.sp,
                    modifier = Modifier.clickable { onSyncClick() }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Tap to Sync Manga Folder",
                    color = if (isDark) Color.White else Color.Black,
                    fontSize = 18.sp
                )
            }
        }
    }
}