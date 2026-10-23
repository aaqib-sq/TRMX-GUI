package dev.trmx.gui.ui

/*
 * SettingsScreen (Compact Redesign) — slim, focused settings surface:
 * 1. Connection status & loopback bridge details.
 * 2. Bridge daemon control (stop daemon).
 * 3. Setup reset & re-pair action.
 * 4. Minimalist about info.
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun SettingsScreen(
    connected: Boolean,
    onTestConnection: () -> Unit,
    onStopBridge: () -> Unit,
    onRerunSetup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmStopBridge by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TrmxTopBar(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 1. Connection Section -----------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Bridge Connection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (connected) Color(0xFF66BB6A) else P.Danger,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (connected) "Connected to localhost bridge" else "Bridge unreachable",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = P.TextPrimary,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = P.SurfaceHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Text(
                                "Endpoint:",
                                style = MaterialTheme.typography.bodySmall,
                                color = P.TextMuted,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "http://127.0.0.1:8080",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = P.TextSecondary,
                            )
                        }
                    }

                    TrmxButton(
                        label = "Test Connection",
                        leading = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = onTestConnection,
                        kind = TrmxButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- 2. Management & Setup -----------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Management",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                    )

                    Text(
                        "Reconfigure the Termux bridge pairing or cleanly shut down the daemon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextSecondary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TrmxButton(
                            label = "Re-pair App",
                            leading = { Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = { confirmReset = true },
                            kind = TrmxButtonKind.Secondary,
                            modifier = Modifier.weight(1f),
                        )

                        TrmxButton(
                            label = "Stop Bridge",
                            leading = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = { confirmStopBridge = true },
                            kind = TrmxButtonKind.Danger,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- 3. About Section ----------------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "TRMX Compact",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                    )
                    Text(
                        "Minimalist visual control plane for Termux.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextSecondary,
                    )
                    Text(
                        "Protocol: TRMX-P/1 · Localhost loopback security",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = P.TextMuted,
                    )
                }
            }
        }
    }

    if (confirmStopBridge) {
        AlertDialog(
            onDismissRequest = { confirmStopBridge = false },
            title = { Text("Stop Bridge Daemon?") },
            text = {
                Text("This sends POST /v1/system/bridge to cleanly terminate the Python bridge in Termux.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStopBridge = false
                    onStopBridge()
                }) {
                    Text("Stop Daemon", color = P.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopBridge = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Pairing?") },
            text = {
                Text("This clears your current pairing token and returns to the initial connection screen.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onRerunSetup()
                }) {
                    Text("Reset & Re-pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
