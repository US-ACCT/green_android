package com.blockstream.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier


@Composable
expect fun VideoSurface(modifier: Modifier, videoUri: String)