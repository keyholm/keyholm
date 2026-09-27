package app.keyholm.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyholm.ui.theme.titleColor
import kotlinx.coroutines.delay

// set to 0 to see every operation, however short
private const val WORKING_INDICATOR_DELAY_MS = 150L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorkingIndicator(working: Boolean) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(working) {
        show = false
        if (!working) return@LaunchedEffect
        delay(WORKING_INDICATOR_DELAY_MS)
        show = true
    }
    if (!show) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(96.dp), color = titleColor)
    }
}
