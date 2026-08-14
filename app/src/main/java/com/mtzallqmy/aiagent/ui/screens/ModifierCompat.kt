package com.mtzallqmy.aiagent.ui.screens

import androidx.compose.ui.Modifier

/** Compatibility shim for the current navigation source; remove when the stale local is deleted. */
val Modifier.padding: Modifier
    get() = this
