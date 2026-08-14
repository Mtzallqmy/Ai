package com.mtzallqmy.aiagent.ui

import androidx.compose.runtime.Composable
import com.mtzallqmy.aiagent.designsystem.AegisTheme as DesignSystemTheme

@Composable
fun AegisTheme(
    content: @Composable () -> Unit,
) {
    DesignSystemTheme(content = content)
}
