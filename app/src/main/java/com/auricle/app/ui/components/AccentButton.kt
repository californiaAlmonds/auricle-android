package com.auricle.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun AccentPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (isPrimary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AuricleTheme.colors.accent,
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = AuricleTheme.colors.surface.copy(alpha = 0.6f),
                contentColor = AuricleTheme.colors.textPrimary
            ),
            border = BorderStroke(1.dp, AuricleTheme.colors.border),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content
        )
    }
}
