package com.dama.tahany.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.permissions.PermissionItem

@Composable
internal fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun RoleScreen(onSelect: (DeviceRole) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DeviceIllustration(
                    Modifier.fillMaxWidth(),
                    title = "Tahany",
                    subtitle = "Your phone and tablet, together.",
                )
                Text("Set up this device", style = MaterialTheme.typography.headlineSmall)
                RoleCard(
                    icon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                    title = "Phone",
                    body = "Share your screen, calls and notifications with the tablet.",
                    onClick = { onSelect(DeviceRole.PHONE) },
                )
                RoleCard(
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    title = "Tablet",
                    body = "View the phone screen, see alerts and reply.",
                    onClick = { onSelect(DeviceRole.TABLET) },
                )
                Text(
                    "Both devices can send files, clipboard text and screenshots.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DeviceIllustration(
    modifier: Modifier,
    title: String,
    subtitle: String,
    compact: Boolean = false,
) {
    val accent = Color(0xFFC1F283)
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = Color(0xFF172F28), contentColor = Color.White) {
        Column(
            Modifier.padding(if (compact) 18.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            Canvas(Modifier.fillMaxWidth().height(if (compact) 56.dp else 90.dp)) {
                val scale = (size.width / 225f).coerceAtMost(size.height / 100f)
                val origin = Offset(size.width / 2f - 10f * scale, size.height / 2f)
                fun point(x: Float, y: Float) = origin + Offset(x * scale, y * scale)
                fun device(x: Float, y: Float, w: Float, h: Float) {
                    val dimensions = androidx.compose.ui.geometry.Size(w * scale, h * scale)
                    val corner = androidx.compose.ui.geometry.CornerRadius(9f * scale)
                    drawRoundRect(Color(0xFF28473C), point(x, y), dimensions, corner)
                    drawRoundRect(accent, point(x, y), dimensions, corner,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2f * scale))
                }
                device(-98f, -39f, 48f, 78f)
                device(10f, -45f, 108f, 90f)
                for (x in -41..1 step 7) drawCircle(accent, 1.5f * scale, point(x.toFloat(), 0f))
                drawLine(accent, point(-82f, -28f), point(-66f, -28f), 2f * scale, StrokeCap.Round)
                drawLine(accent, point(48f, 34f), point(80f, 34f), 2f * scale, StrokeCap.Round)
            }
            Text(
                title,
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC9DED1))
        }
    }
}

@Composable
private fun RoleCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides
                        MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    icon()
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PermissionsScreen(
    missing: List<PermissionItem>,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Allow nearby connections",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Android needs access to find and connect to your other device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        missing.forEach { permission ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        permission.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        permission.why,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onGrant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant permissions")
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue for now")
                }
            }
        }
    }
}
