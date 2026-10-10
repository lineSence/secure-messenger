package dev.sm.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.sm.messenger.R

/** Значок приложения (ADR-77, вариант 01): фон и знак адаптивного значка, обрезка как у лаунчера. */
@Composable
fun AppLogo(size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Color(0xFF2563EB)),
        contentAlignment = Alignment.Center,
    ) {
        // Видимая часть адаптивного значка — 72 из 108 dp, поэтому знак рисуем в 1,5 раза крупнее.
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize((size * 3 / 2).dp))
    }
}
