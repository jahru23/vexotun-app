package com.vexotun.app.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.vexotun.app.model.SshTransport
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VxtLockInfo
import java.util.Locale

@Composable
internal fun CreateConfigTypeDialog(
    onDismiss: () -> Unit,
    onSelectMode: (TunnelMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buat Profil Baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pilih jenis engine yang ingin dikonfigurasi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    onClick = { onSelectMode(TunnelMode.XRAY) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Xray", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            ModeBadge(TunnelMode.XRAY)
                        }
                        Spacer(Modifier.heightSpacer4())
                        Text(
                            "VMess, VLESS (TLS/Reality), Trojan, Shadowsocks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    onClick = { onSelectMode(TunnelMode.SSH) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SSH", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            ModeBadge(TunnelMode.SSH)
                        }
                        Spacer(Modifier.heightSpacer4())
                        Text(
                            "Direct, HTTP Proxy, SSL/TLS, WS/WSS, SlowDNS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun Modifier.heightSpacer4() = this.padding(top = 4.dp)

@Composable
internal fun ImportLinkDialog(
    onDismiss: () -> Unit,
    onPickVxtFile: () -> Unit,
    onImport: (String) -> Result<Int>
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            input = if (input.isBlank()) contents else "$input\n$contents"
            errorMsg = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tempel link (vmess/vless/trojan/ss), scan QR, atau pilih file .vxt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorMsg = null
                    },
                    placeholder = { Text("vmess://...\nvless://...\ntrojan://...") },
                    minLines = 3,
                    maxLines = 6,
                    isError = errorMsg != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(
                        errorMsg.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clip.isNotBlank()) {
                                input = clip
                                errorMsg = null
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clipboard", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Arahkan kamera ke QR Config Vexotun")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                            qrLauncher.launch(options)
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan QR", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onPickVxtFile,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("File .vxt", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val res = onImport(input)
                    res.onFailure { errorMsg = it.message ?: "Link tidak valid" }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
internal fun ShareLinkDialog(
    link: String,
    onShowQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bagikan Link Config") },
        text = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Bagikan Config"))
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bagikan")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShowQr) {
                    Text("QR Code")
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("vexotun_link", link))
                    Toast.makeText(context, "Link disalin", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) {
                    Text("Salin")
                }
                TextButton(onClick = onDismiss) {
                    Text("Tutup")
                }
            }
        }
    )
}

@Composable
internal fun ExportVxtDialog(
    profile: TunnelProfile,
    onDismiss: () -> Unit,
    onExportFile: (VxtLockInfo, String) -> Unit,
    onExportQr: (VxtLockInfo, String) -> Unit
) {
    val hasPayloadOption = profile.mode == TunnelMode.SSH || profile.xray.useCustomPayload
    var lockConfig by remember { mutableStateOf(true) }
    var hideHost by remember { mutableStateOf(true) }
    var hideSni by remember { mutableStateOf(true) }
    var hideAuth by remember { mutableStateOf(true) }
    var hidePayload by remember { mutableStateOf(hasPayloadOption) }
    var hideDns by remember { mutableStateOf(profile.mode == TunnelMode.SSH && profile.ssh.transport == SshTransport.SLOWDNS) }
    var pin by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expiryDays by remember { mutableStateOf(0) }

    fun buildLockInfo(): VxtLockInfo {
        val expiresAt = if (expiryDays > 0) {
            System.currentTimeMillis() + expiryDays * 24L * 60L * 60L * 1000L
        } else 0L
        return VxtLockInfo(
            locked = lockConfig,
            hideHost = lockConfig && hideHost,
            hideSni = lockConfig && hideSni,
            hideAuth = lockConfig && hideAuth,
            hidePayload = lockConfig && hidePayload,
            hideDns = lockConfig && hideDns,
            author = author.trim(),
            note = note.trim(),
            expiresAtMs = expiresAt
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Config .vxt") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Profil: ${profile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kunci Config (Anti-Edit/Intip)", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = lockConfig,
                        onCheckedChange = {
                            lockConfig = it
                            if (!it) {
                                hideHost = false
                                hideSni = false
                                hideAuth = false
                                hidePayload = false
                                hideDns = false
                            }
                        }
                    )
                }
                if (lockConfig) {
                    VxtCheckRow("Sembunyikan Host / IP", hideHost) { hideHost = it }
                    VxtCheckRow("Sembunyikan Bug / SNI", hideSni) { hideSni = it }
                    VxtCheckRow("Sembunyikan Akun (UUID/Pass)", hideAuth) { hideAuth = it }
                    if (hasPayloadOption) {
                        VxtCheckRow("Sembunyikan Payload / Proxy", hidePayload) { hidePayload = it }
                    }
                    if (profile.mode == TunnelMode.SSH && profile.ssh.transport == SshTransport.SLOWDNS) {
                        VxtCheckRow("Sembunyikan DNS / NS / PubKey", hideDns) { hideDns = it }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.replace("\n", "") },
                    label = { Text("PIN / Password (Opsional)") },
                    placeholder = { Text("Kosongkan jika tanpa PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.replace("\n", "") },
                    label = { Text("Pembuat (Opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan (Opsional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                CompactDropdown(
                    label = "Masa Aktif Config",
                    selectedLabel = when (expiryDays) {
                        3 -> "3 Hari"
                        7 -> "7 Hari"
                        30 -> "30 Hari"
                        else -> "Tanpa Batas"
                    },
                    options = listOf(0, 3, 7, 30),
                    optionLabel = { d -> if (d == 0) "Tanpa Batas" else "$d Hari" },
                    onSelect = { expiryDays = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onExportFile(buildLockInfo(), pin.trim()) },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Simpan .vxt")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onExportQr(buildLockInfo(), pin.trim()) }) {
                    Text("Buat QR")
                }
                TextButton(onClick = onDismiss) {
                    Text("Batal")
                }
            }
        }
    )
}

@Composable
internal fun VxtPinPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Masukkan PIN Config .vxt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "File .vxt ini dilindungi PIN oleh pembuatnya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.replace("\n", "") },
                    label = { Text("PIN / Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin.trim()) },
                enabled = pin.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Buka")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
internal fun QrCodeDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrBitmap = remember(content) { generateQrBitmap(content, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(240.dp)
                                .padding(12.dp)
                        )
                    }
                    Text(
                        "Pindai QR ini dari menu Import di Vexotun lain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text("Gagal membuat QR Code.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (qrBitmap != null) {
                Button(
                    onClick = {
                        val saved = saveBitmapToGallery(context, qrBitmap, "vexotun_qr_${System.currentTimeMillis()}.png")
                        Toast.makeText(
                            context,
                            if (saved) "QR disimpan ke Galeri (Pictures/Vexotun)" else "Gagal menyimpan QR",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Simpan ke Galeri")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

internal fun generateQrBitmap(text: String, sizePx: Int): Bitmap? {
    return runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}

internal fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vexotun")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)
}

@Composable
internal fun VxtCheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

internal fun formatSpeed(bps: Long): String {
    if (bps <= 0L) return "0 B/s"
    return "${formatBytes(bps)}/s"
}
