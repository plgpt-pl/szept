package pl.fourdt.szept

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ---------- czat (wspólny szkielet dla rozmowy 1:1 i grupy; docs/WERSJA-0.3.md §4) ----------

val QUICK_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(engine: Engine, nav: NavHostController, id: String) {
    val v by engine.db.version.collectAsState()
    val contact = remember(v) { engine.db.contact(id) } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val messages = remember(v) { engine.db.messages(id) }
    LaunchedEffect(v) { engine.markRead(contact) }
    ChatBody(engine, nav, title = contact.name, messages = messages, disappearAfter = contact.disappearAfter,
        onSetDisappear = { engine.setDisappear(it, contact) }, infoRoute = "contact/$id", infoLabel = "Informacje i weryfikacja",
        onSendText = { t, r -> engine.sendText(t, contact, r) }, onSendImage = { b, r -> engine.sendImage(b, contact, r) },
        onSendFile = { d, n, m, r -> engine.sendFile(d, n, m, contact, r) }, onSendAudio = { d, s, r -> engine.sendAudio(d, s, contact, r) }, showSender = false) {
        if (contact.identityChanged) Banner(Sowa.warn.copy(alpha = 0.2f), Icons.Default.Warning, "Klucz bezpieczeństwa tego kontaktu się zmienił. Potwierdź numer bezpieczeństwa.") { nav.navigate("contact/$id") }
        if (contact.fromUnknown && !contact.verified) Banner(Sowa.glow, Icons.Default.PersonSearch, "Ten kontakt napisał pierwszy. Zweryfikuj numer bezpieczeństwa, zanim mu zaufasz.") { nav.navigate("contact/$id") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(engine: Engine, nav: NavHostController, id: String) {
    val v by engine.db.version.collectAsState()
    val group = remember(v) { engine.db.group(id) } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val messages = remember(v) { engine.db.messagesForGroup(id) }
    LaunchedEffect(v) { engine.markRead(group) }
    ChatBody(engine, nav, title = group.name, subtitle = "${group.members.size + 1} os.", messages = messages, disappearAfter = group.disappearAfter,
        onSetDisappear = { engine.setDisappear(it, group) }, infoRoute = "groupinfo/$id", infoLabel = "Informacje o grupie",
        onSendText = { t, r -> engine.sendText(t, group, r) }, onSendImage = { b, r -> engine.sendImage(b, group, r) },
        onSendFile = { d, n, m, r -> engine.sendFile(d, n, m, group, r) }, onSendAudio = { d, s, r -> engine.sendAudio(d, s, group, r) }, showSender = true) {}
}

private class Picked(val data: ByteArray, val name: String, val mime: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(engine: Engine, nav: NavHostController, title: String, subtitle: String? = null, messages: List<Message>, disappearAfter: Int,
                     onSetDisappear: suspend (Int) -> Unit, infoRoute: String, infoLabel: String,
                     onSendText: suspend (String, String?) -> Unit, onSendImage: suspend (Bitmap, String?) -> Unit,
                     onSendFile: suspend (ByteArray, String, String?, String?) -> Unit, onSendAudio: suspend (ByteArray, Double, String?) -> Unit,
                     showSender: Boolean, banners: @Composable ColumnScope.() -> Unit) {
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var reactFor by remember { mutableStateOf<Message?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recordStart by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val indexById = remember(messages) { messages.withIndex().associate { it.value.id to it.index } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    fun nameOf(m: Message): String = when { m.isMine -> "Ty"; m.senderId != null -> senderName(engine, m); else -> title }
    fun consumeReply(): String? = replyTo?.id.also { replyTo = null }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.isMutableRequired = true } }
            onSendImage(bmp, consumeReply())
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val p = withContext(Dispatchers.IO) { readPicked(ctx, uri) }
            when {
                p == null -> Toast.makeText(ctx, "Nie udało się odczytać pliku.", Toast.LENGTH_LONG).show()
                p.data.size > Config.MAX_FILE -> Toast.makeText(ctx, "Plik jest za duży (limit ${Config.MAX_FILE / 1024 / 1024} MB).", Toast.LENGTH_LONG).show()
                else -> onSendFile(p.data, p.name, p.mime, consumeReply())
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) Toast.makeText(ctx, "Bez dostępu do mikrofonu nie nagram głosówki.", Toast.LENGTH_LONG).show() }
    val recorder = remember { VoiceRecorder(ctx) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Column { Text(title, maxLines = 1); subtitle?.let { Text(it, color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall) } } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            actions = {
                IconButton({ menu = true }) { Icon(if (disappearAfter > 0) Icons.Default.Timer else Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    Durations.options.forEach { (s, l) -> DropdownMenuItem({ Text((if (s == disappearAfter) "✓ " else "") + l) }, { menu = false; scope.launch { onSetDisappear(s) } }) }
                    HorizontalDivider()
                    DropdownMenuItem({ Text(infoLabel) }, { menu = false; nav.navigate(infoRoute) }, leadingIcon = { Icon(if (showSender) Icons.Default.Group else Icons.Default.Verified, null) })
                }
            })
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().background(Sowa.card.copy(alpha = 0.9f)).imePadding().navigationBarsPadding()) {
            replyTo?.let { r -> ComposerHint(Icons.AutoMirrored.Filled.Reply, "Odpowiedź do ${nameOf(r)}", r.summary()) { replyTo = null } }
            editing?.let { ComposerHint(Icons.Default.Edit, "Edytujesz wiadomość", it.text) { editing = null; draft = "" } }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                if (recording) {
                    var secs by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { secs = ((System.currentTimeMillis() - recordStart) / 1000).toInt(); delay(250) } }
                    Row(Modifier.weight(1f).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)); Spacer(Modifier.width(8.dp))
                        Text("Nagrywanie ${durationLabel(secs.toDouble())}", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(10.dp)); Text("◀ przesuń, aby anulować", color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Box {
                        IconButton({ attach = true }) { Icon(Icons.Default.AttachFile, "Załącznik", tint = Sowa.cyan) }
                        DropdownMenu(attach, { attach = false }) {
                            DropdownMenuItem({ Text("Zdjęcie") }, { attach = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, leadingIcon = { Icon(Icons.Default.Image, null) })
                            DropdownMenuItem({ Text("Plik") }, { attach = false; filePicker.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Default.Description, null) })
                        }
                    }
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(if (editing != null) "Nowa treść…" else "Szepnij coś…") }, maxLines = 6, shape = RoundedCornerShape(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (draft.isBlank() && editing == null) {
                    RecordButton(recording = recording, onStart = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPermission.launch(Manifest.permission.RECORD_AUDIO); false }
                        else recorder.start().also { ok -> if (ok) { recording = true; recordStart = System.currentTimeMillis() } }
                    }, onStop = { cancelled ->
                        recording = false
                        val r = recorder.stop(cancelled)
                        if (r != null) scope.launch { onSendAudio(r.first, r.second, consumeReply()) }
                        else if (!cancelled) Toast.makeText(ctx, "Za krótkie nagranie.", Toast.LENGTH_SHORT).show()
                    })
                } else Box(Modifier.size(42.dp).clip(CircleShape).background(Sowa.action).clickable {
                    val t = draft; draft = ""
                    val e = editing
                    if (e != null) { editing = null; scope.launch { engine.edit(e, t) } } else { val r = consumeReply(); scope.launch { onSendText(t, r) } }
                }, contentAlignment = Alignment.Center) { Icon(if (editing != null) Icons.Default.Check else Icons.Default.ArrowUpward, "Wyślij", tint = Color.White) }
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            banners()
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages, key = { it.id }) { m ->
                    Bubble(engine, m, quoted = m.replyTo?.let { id -> messages.firstOrNull { it.id == id } }, nameOf = ::nameOf,
                        sender = if (showSender && !m.isMine && m.senderId != null) senderName(engine, m) else null,
                        onRetry = { scope.launch { engine.retry(m) } }, onTapImage = { preview = it },
                        onReply = { replyTo = m; editing = null }, onReact = { reactFor = m },
                        onReactTap = { e -> scope.launch { engine.react(m, e) } },
                        onEdit = { editing = m; replyTo = null; draft = m.text },
                        onDeleteAll = { scope.launch { engine.deleteForAll(m) } }, onDeleteLocal = { engine.deleteLocally(m) },
                        onQuoteTap = { id -> indexById[id]?.let { i -> scope.launch { listState.animateScrollToItem(i) } } })
                }
            }
        }
    }
    preview?.let { bmp -> Box(Modifier.fillMaxSize().background(Color.Black).clickable { preview = null }, contentAlignment = Alignment.Center) { Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth()) } }
    reactFor?.let { m -> EmojiDialog(mine = engine.accountId?.let { m.reactionMap()[it] }, onPick = { e -> reactFor = null; scope.launch { engine.react(m, e) } }, onDismiss = { reactFor = null }) }
}

@Composable
private fun ComposerHint(icon: ImageVector, title: String, text: String, onClose: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sowa.cyan, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text(title, color = Sowa.cyan, style = MaterialTheme.typography.labelSmall); Text(text, color = Sowa.textMuted, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
        IconButton(onClose) { Icon(Icons.Default.Close, "Anuluj", tint = Sowa.textMuted) }
    }

@Composable
private fun EmojiDialog(mine: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reakcja") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QUICK_EMOJI.forEach { e -> Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).background(if (e == mine) Sowa.glow else Color.Transparent).clickable { onPick(if (e == mine) null else e) }.padding(4.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, { custom = it.take(Config.MAX_EMOJI) }, Modifier.weight(1f), placeholder = { Text("dowolne emoji") }, singleLine = true)
                Spacer(Modifier.width(6.dp)); TextButton({ if (custom.isNotBlank()) onPick(custom.trim()) }) { Text("OK") }
            }
            if (mine != null) TextButton({ onPick(null) }) { Text("Cofnij moją reakcję ($mine)") }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Anuluj") } })
}

@Composable
internal fun Banner(bg: Color, icon: ImageVector, text: String, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Sowa.cyan); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text("Sprawdź", color = Sowa.cyan, style = MaterialTheme.typography.labelMedium)
}

// ---------- dymek ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(engine: Engine, m: Message, quoted: Message?, nameOf: (Message) -> String, sender: String?, onRetry: () -> Unit, onTapImage: (Bitmap) -> Unit,
           onReply: () -> Unit, onReact: () -> Unit, onReactTap: (String?) -> Unit, onEdit: () -> Unit, onDeleteAll: () -> Unit, onDeleteLocal: () -> Unit, onQuoteTap: (String) -> Unit) {
    if (m.isNote) {   // notatka systemowa grupy (ktoś dodał, opuścił, zmienił)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(m.text, color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp)) }
        return
    }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    Row(Modifier.fillMaxWidth().offset { IntOffset(drag.roundToInt(), 0) }.pointerInput(m.id) {
        detectHorizontalDragGestures(onDragEnd = { if (drag > threshold) onReply(); drag = 0f }, onDragCancel = { drag = 0f }) { change, dx -> change.consume(); drag = (drag + dx).coerceIn(0f, threshold * 1.4f) }
    }, horizontalArrangement = if (m.isMine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.isMine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 290.dp)) {
            sender?.let { Text(it, color = Sowa.cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)) }
            Box {
                val press = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
                when {
                    m.deleted -> Box(press.clip(RoundedCornerShape(18.dp)).background(Brush2(Sowa.card)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text("Wiadomość usunięta", color = Sowa.textMuted, fontStyle = FontStyle.Italic)
                    }
                    m.kind == Message.IMAGE -> Column(press) {
                        quoted?.let { Quote(it, nameOf(it), onQuoteTap) }
                        val bmp = remember(m.attachmentFile) { m.attachmentFile?.let { BitmapFactory.decodeFile(File(engine.mediaDir, it).path) } }
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.widthIn(max = 240.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { onTapImage(bmp) }, onLongClick = { menu = true }))
                        else Box(Modifier.size(200.dp, 150.dp).clip(RoundedCornerShape(14.dp)).background(Sowa.card), contentAlignment = Alignment.Center) { if (m.text.isEmpty()) CircularProgressIndicator() else Text(m.text, style = MaterialTheme.typography.bodySmall) }
                    }
                    else -> Column(press.clip(RoundedCornerShape(18.dp)).background(if (m.isMine) Sowa.userBubble else Brush2(Sowa.card)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                        quoted?.let { Quote(it, nameOf(it), onQuoteTap) }
                        when (m.kind) {
                            Message.AUDIO -> AudioBubble(engine, m)
                            Message.FILE -> FileBubble(engine, m) { openAttachment(ctx, engine, m, share = false) }
                            else -> Text(m.text, color = if (m.isMine) Color.White else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                DropdownMenu(menu, { menu = false }) {
                    if (!m.deleted) {
                        DropdownMenuItem({ Text("Odpowiedz") }, { menu = false; onReply() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) })
                        DropdownMenuItem({ Text("Reaguj") }, { menu = false; onReact() }, leadingIcon = { Icon(Icons.Default.AddReaction, null) })
                        if (m.kind == Message.TEXT) DropdownMenuItem({ Text("Kopiuj") }, { menu = false; clipboard.setText(AnnotatedString(m.text)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                        if (m.hasAttachment && m.attachmentFile != null) DropdownMenuItem({ Text("Udostępnij") }, { menu = false; openAttachment(ctx, engine, m, share = true) }, leadingIcon = { Icon(Icons.Default.Share, null) })
                        if (m.isMine && m.kind == Message.TEXT) DropdownMenuItem({ Text("Edytuj") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        if (m.isMine) DropdownMenuItem({ Text("Usuń u wszystkich") }, { menu = false; onDeleteAll() }, leadingIcon = { Icon(Icons.Default.DeleteForever, null) })
                    }
                    DropdownMenuItem({ Text("Usuń u mnie") }, { menu = false; onDeleteLocal() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
            Reactions(m, engine.accountId, onReactTap)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.expiresAt != null) Icon(Icons.Default.Timer, null, tint = Sowa.textMuted, modifier = Modifier.size(10.dp))
                Text(hhmm(m.sentAt), color = Sowa.textMuted, fontSize = 10.sp)
                if (m.editedAt != null && !m.deleted) Text(" (edytowano)", color = Sowa.textMuted, fontSize = 10.sp)
                if (m.isMine && !m.deleted) when (m.status) {
                    Message.PENDING -> Icon(Icons.Default.Schedule, null, tint = Sowa.textMuted, modifier = Modifier.size(10.dp))
                    Message.SENT -> Icon(Icons.Default.Check, null, tint = Sowa.textMuted, modifier = Modifier.size(10.dp))
                    Message.DELIVERED -> Icon(Icons.Default.CheckCircle, null, tint = Sowa.cyan, modifier = Modifier.size(10.dp))
                    else -> Text(" ponów", color = Sowa.warn, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onRetry))
                }
            }
        }
    }
}

@Composable
private fun Quote(q: Message, name: String, onTap: (String) -> Unit) =
    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.25f)).clickable { onTap(q.id) }.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Sowa.cyan))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, color = Sowa.cyan, style = MaterialTheme.typography.labelSmall)
            Text(q.summary(), color = Sowa.textMuted, maxLines = 2, style = MaterialTheme.typography.bodySmall)
        }
    }

@Composable
private fun Reactions(m: Message, me: String?, onTap: (String?) -> Unit) {
    val map = remember(m.reactions) { m.reactionMap() }
    if (map.isEmpty() || m.deleted) return
    val mine = me?.let { map[it] }
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        map.values.groupingBy { it }.eachCount().forEach { (e, n) ->
            Text(if (n > 1) "$e $n" else e, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (e == mine) Sowa.glow else Sowa.card)
                .border(1.dp, if (e == mine) Sowa.cyan else Sowa.border, RoundedCornerShape(10.dp)).clickable { onTap(if (e == mine) null else e) }.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun FileBubble(engine: Engine, m: Message, onOpen: () -> Unit) {
    val size = remember(m.attachmentFile) { m.attachmentFile?.let { File(engine.mediaDir, it).length() } }
    Row(Modifier.clickable(enabled = size != null, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Description, null, tint = if (m.isMine) Color.White else Sowa.cyan, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(8.dp))
        Column {
            Text(m.fileName ?: "plik", color = if (m.isMine) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Text(if (size == null) (m.text.ifEmpty { "pobieranie…" }) else sizeLabel(size), color = if (m.isMine) Color.White.copy(alpha = 0.8f) else Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun sizeLabel(b: Long): String = when { b < 1024 -> "$b B"; b < 1024 * 1024 -> "${b / 1024} KB"; else -> "%.1f MB".format(b / 1024.0 / 1024.0) }

@Composable
private fun AudioBubble(engine: Engine, m: Message) {
    val file = m.attachmentFile?.let { File(engine.mediaDir, it) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var pos by remember { mutableIntStateOf(0) }
    val player = remember(m.attachmentFile) { file?.takeIf { it.exists() }?.let { f -> try { MediaPlayer().apply { setDataSource(f.path); prepare() } } catch (e: Exception) { null } } }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing && player != null) {
            pos = player.currentPosition; progress = if (player.duration > 0) pos.toFloat() / player.duration else 0f
            if (!player.isPlaying) { playing = false; progress = 0f; pos = 0 }
            delay(200)
        }
    }
    val fg = if (m.isMine) Color.White else Sowa.cyan
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({ player ?: return@IconButton; if (playing) { player.pause(); playing = false } else { player.start(); playing = true } }, enabled = player != null) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pauza" else "Odtwórz", tint = fg)
        }
        Column(Modifier.width(150.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = fg, trackColor = fg.copy(alpha = 0.3f))
            Text(if (player == null) (m.text.ifEmpty { "pobieranie…" }) else (if (playing) durationLabel(pos / 1000.0) + " / " else "🎤 ") + durationLabel(if (m.duration > 0) m.duration else player.duration / 1000.0),
                color = if (m.isMine) Color.White.copy(alpha = 0.85f) else Sowa.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Przytrzymanie nagrywa, puszczenie wysyła, przesunięcie w lewo anuluje. */
@Composable
private fun RecordButton(recording: Boolean, onStart: () -> Boolean, onStop: (cancelled: Boolean) -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(if (recording) Brush2(MaterialTheme.colorScheme.error) else Sowa.action).pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            if (!onStart()) return@awaitEachGesture
            var cancelled = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull() ?: break
                if (ch.position.x < -200f) cancelled = true
                if (!ch.pressed) break
            }
            onStop(cancelled)
        }
    }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, "Nagraj głosówkę", tint = Color.White) }
}

/** MediaRecorder: AAC mono 32 kb/s w m4a (odtwarzalne na iOS i Androidzie). */
class VoiceRecorder(private val ctx: android.content.Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    fun start(): Boolean = try {
        cancel()
        val f = File(ctx.cacheDir, "rec-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1); r.setAudioEncodingBitRate(32_000); r.setAudioSamplingRate(44_100); r.setOutputFile(f.path)
        r.prepare(); r.start(); rec = r; file = f; started = System.currentTimeMillis(); true
    } catch (e: Exception) { rec = null; false }
    /** Zwraca (bajty, sekundy) albo null gdy anulowano / za krótkie. */
    fun stop(cancelled: Boolean): Pair<ByteArray, Double>? {
        val r = rec ?: return null; rec = null
        val dur = (System.currentTimeMillis() - started) / 1000.0
        try { r.stop() } catch (_: Exception) { file?.delete(); r.release(); return null }
        r.release()
        val f = file ?: return null; file = null
        val out = if (!cancelled && dur >= 0.5 && f.length() > 0) f.readBytes() to dur else null
        f.delete(); return out
    }
    fun cancel() { try { rec?.stop() } catch (_: Exception) {}; rec?.release(); rec = null; file?.delete(); file = null }
}

private fun readPicked(ctx: android.content.Context, uri: Uri): Picked? = try {
    var name = "plik"
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
    val data = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    Picked(data, name, ctx.contentResolver.getType(uri))
} catch (e: Exception) { null }

/** Otwiera (ACTION_VIEW) albo udostępnia (ACTION_SEND) odszyfrowany załącznik przez FileProvider z kopii w cache. */
fun openAttachment(ctx: android.content.Context, engine: Engine, m: Message, share: Boolean) {
    val src = m.attachmentFile?.let { File(engine.mediaDir, it) }?.takeIf { it.exists() } ?: return
    val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }
    val name = (m.fileName ?: src.name).replace('/', '_')
    val dst = File(dir, name); src.copyTo(dst, overwrite = true)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dst)
    val mime = m.mime ?: when (m.kind) { Message.IMAGE -> "image/jpeg"; Message.AUDIO -> "audio/mp4"; else -> "application/octet-stream" }
    val i = if (share) Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri) } else Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime) }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, if (share) "Udostępnij" else "Otwórz w")) } catch (e: Exception) { Toast.makeText(ctx, "Brak aplikacji do otwarcia tego pliku.", Toast.LENGTH_LONG).show() }
}
