package pl.fourdt.szept

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- nawigacja ----------
@Composable
fun SzeptNav(engine: Engine, nav: NavHostController, pendingLink: MutableState<ContactLink?>) {
    NavHost(nav, startDestination = "list") {
        composable("list") { ChatListScreen(engine, nav) }
        composable("chat/{id}") { ChatScreen(engine, nav, it.arguments!!.getString("id")!!) }
        composable("group/{id}") { GroupChatScreen(engine, nav, it.arguments!!.getString("id")!!) }
        composable("newgroup") { NewGroupScreen(engine, nav) }
        composable("groupinfo/{id}") { GroupInfoScreen(engine, nav, it.arguments!!.getString("id")!!) }
        composable("add") { AddContactScreen(engine, nav) }
        composable("contact/{id}") { ContactInfoScreen(engine, nav, it.arguments!!.getString("id")!!) }
        composable("settings") { SettingsScreen(engine, nav) }
    }
    pendingLink.value?.let { link -> AddContactDialog(engine, link, onDone = { pendingLink.value = null }) }
}

@Composable fun rememberDb(engine: Engine): Int { val v by engine.db.version.collectAsState(); return v }

// ---------- wspólne ----------
@Composable
fun Card2(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sowa.card)
    .border(1.dp, Sowa.border, RoundedCornerShape(16.dp)).padding(14.dp), content = content)

@Composable
fun Avatar(name: String, size: Int = 40) = Box(Modifier.size(size.dp).clip(CircleShape).background(Sowa.action), contentAlignment = Alignment.Center) {
    Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.45).sp)
}

@Composable
fun Logo(size: Int) = Image(painterResource(R.drawable.logo), null, Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.22).dp)))

@Composable
fun GradientButton(text: String, enabled: Boolean = true, onClick: () -> Unit) = Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
    .background(if (enabled) Sowa.action else Brush2(Sowa.border)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
}

@Composable
fun LockScreen(onUnlock: () -> Unit) = Box(Modifier.fillMaxSize().background(Sowa.backdrop), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Logo(96); Text("Szept zablokowany", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onUnlock) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Odblokuj") }
    }
}

// ---------- onboarding ----------
@Composable
fun OnboardingScreen(engine: Engine) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Config.DEFAULT_SERVER) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(30.dp)); Logo(120)
        Text("Szept", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("Rozmowy, których nikt poza Wami nie przeczyta.", color = Sowa.textMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Card2 {
            Bullet(Icons.Default.PersonOff, "Bez numeru telefonu, e-maila i konta. Twoja tożsamość to klucz wygenerowany na tym telefonie.")
            Bullet(Icons.Default.Shield, "Szyfrowanie end-to-end protokołem Signal (PQXDH + Double Ratchet, odporność postkwantowa).")
            Bullet(Icons.Default.VisibilityOff, "Serwer nie zna nadawcy ani treści – przechowuje tylko zaszyfrowane koperty do chwili odbioru.")
            Bullet(Icons.Default.Timer, "Znikające wiadomości, blokada biometryczna, zero kopii w chmurze, zero usług Google.")
        }
        Card2 {
            Text("Nazwa dla kontaktów (opcjonalnie)", color = Sowa.textMuted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = { Text("np. Adam") }, singleLine = true)
            TextButton({ advanced = !advanced }) { Text("Zaawansowane") }
            if (advanced) OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Adres serwera") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        error?.let { Text(it, color = Sowa.warn, textAlign = TextAlign.Center) }
        GradientButton(if (busy) "Tworzę…" else "Utwórz tożsamość", enabled = !busy) {
            busy = true; error = null
            engine.displayName = name.trim(); engine.serverUrl = server.trim()
            scope.launch { try { engine.register() } catch (e: Exception) { error = e.message }; busy = false }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) = Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, null, tint = Sowa.cyan, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
}

// ---------- lista (kontakty ∪ grupy po ostatniej aktywności) ----------
private sealed class ChatItem(val key: String, val lastActivity: Long) {
    class C(val c: Contact) : ChatItem("c:" + c.id, c.lastActivity)
    class G(val g: Group) : ChatItem("g:" + g.id, g.lastActivity)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(engine: Engine, nav: NavHostController) {
    val v by engine.db.version.collectAsState()
    val contacts = remember(v) { engine.db.contacts() }
    val items = remember(v) { (contacts.map { ChatItem.C(it) } + engine.db.groups().map { ChatItem.G(it) }).sortedByDescending { it.lastActivity } }
    val conn by engine.connection.collectAsState()
    var plus by remember { mutableStateOf(false) }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Szept", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            navigationIcon = { IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Ustawienia") } },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when (conn) { Connection.ONLINE -> Sowa.ok; Connection.CONNECTING -> Sowa.warn; else -> Color.Gray }))
                    Spacer(Modifier.width(6.dp))
                    Text(when (conn) { Connection.ONLINE -> "połączono"; Connection.CONNECTING -> "łączenie…"; else -> "offline" }, color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                IconButton({ plus = true }) { Icon(Icons.Default.AddCircle, "Dodaj", tint = Sowa.cyan) }
                DropdownMenu(plus, { plus = false }) {
                    DropdownMenuItem({ Text("Dodaj kontakt") }, { plus = false; nav.navigate("add") }, leadingIcon = { Icon(Icons.Default.QrCode, null) })
                    DropdownMenuItem({ Text("Nowa grupa") }, { plus = false; nav.navigate("newgroup") }, leadingIcon = { Icon(Icons.Default.Group, null) }, enabled = contacts.isNotEmpty())
                }
            })
    }) { pad ->
        if (items.isEmpty()) Column(Modifier.fillMaxSize().padding(pad).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Logo(90); Spacer(Modifier.height(14.dp))
            Text("Nikogo tu jeszcze nie ma", style = MaterialTheme.typography.titleMedium)
            Text("Dodaj kontakt, pokazując swój kod QR albo skanując cudzy. Bez numerów, bez listy znajomych na serwerze.", color = Sowa.textMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
            Button({ nav.navigate("add") }) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(6.dp)); Text("Dodaj kontakt") }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.C -> ContactRow(engine, item.c) { nav.navigate("chat/${item.c.id}") }
                    is ChatItem.G -> GroupRow(engine, item.g) { nav.navigate("group/${item.g.id}") }
                }
            }
        }
    }
}

@Composable
fun ContactRow(engine: Engine, c: Contact, onClick: () -> Unit) {
    val last = remember(c, engine.db.version.collectAsState().value) { engine.db.lastMessage(c.id) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sowa.card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.name, 44); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontWeight = FontWeight.SemiBold)
                if (c.verified) Icon(Icons.Default.Verified, null, tint = Sowa.ok, modifier = Modifier.size(14.dp).padding(start = 2.dp))
                if (c.identityChanged) Icon(Icons.Default.Warning, null, tint = Sowa.warn, modifier = Modifier.size(14.dp).padding(start = 2.dp))
                Spacer(Modifier.weight(1f)); Text(timeAgo(c.lastActivity), color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when { last == null -> "Brak wiadomości"; last.attW > 0 -> (if (last.isMine) "Ty: " else "") + "📷 Zdjęcie"; else -> (if (last.isMine) "Ty: " else "") + last.text },
                    color = Sowa.textMuted, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (c.disappearAfter > 0) Icon(Icons.Default.Timer, null, tint = Sowa.textMuted, modifier = Modifier.size(12.dp))
                if (c.unread > 0) Text("${c.unread}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(Sowa.action).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun GroupAvatar(size: Int = 44) = Box(Modifier.size(size.dp).clip(CircleShape).background(Brush2(Sowa.glow)).border(1.dp, Sowa.border, CircleShape), contentAlignment = Alignment.Center) {
    Icon(Icons.Default.Group, null, tint = Sowa.cyan, modifier = Modifier.size((size * 0.55).dp))
}

/** Nazwa nadawcy cudzej wiadomości grupowej: z kontaktu, inaczej „Nieznany”. */
fun senderName(engine: Engine, m: Message): String = m.senderId?.let { engine.db.contact(it)?.name } ?: "Nieznany"

@Composable
fun GroupRow(engine: Engine, g: Group, onClick: () -> Unit) {
    val v by engine.db.version.collectAsState()
    val last = remember(g, v) { engine.db.lastMessageForGroup(g.id) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sowa.card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupAvatar(44); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, fontWeight = FontWeight.SemiBold)
                Text("  ${g.members.size + 1} os.", color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f)); Text(timeAgo(g.lastActivity), color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val who = when { last == null -> ""; last.isMine -> "Ty: "; last.senderId != null -> senderName(engine, last) + ": "; else -> "" }
                Text(when { last == null -> "Brak wiadomości"; last.attW > 0 -> who + "📷 Zdjęcie"; else -> who + last.text },
                    color = Sowa.textMuted, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (g.disappearAfter > 0) Icon(Icons.Default.Timer, null, tint = Sowa.textMuted, modifier = Modifier.size(12.dp))
                if (g.unread > 0) Text("${g.unread}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(Sowa.action).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

fun timeAgo(t: Long): String { val d = (System.currentTimeMillis() - t) / 1000; return when { d < 60 -> "teraz"; d < 3600 -> "${d / 60} min"; d < 86400 -> "${d / 3600} godz."; else -> "${d / 86400} dni" } }
fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

// ---------- czat (wspólny szkielet dla rozmowy 1:1 i grupy) ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(engine: Engine, nav: NavHostController, id: String) {
    val v by engine.db.version.collectAsState()
    val contact = remember(v) { engine.db.contact(id) } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val messages = remember(v) { engine.db.messages(id) }
    LaunchedEffect(v) { engine.markRead(contact) }
    ChatBody(engine, nav, title = contact.name, messages = messages, disappearAfter = contact.disappearAfter,
        onSetDisappear = { engine.setDisappear(it, contact) }, infoRoute = "contact/$id", infoLabel = "Informacje i weryfikacja",
        onSendText = { engine.sendText(it, contact) }, onSendImage = { engine.sendImage(it, contact) }, showSender = false) {
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
        onSendText = { engine.sendText(it, group) }, onSendImage = { engine.sendImage(it, group) }, showSender = true) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(engine: Engine, nav: NavHostController, title: String, subtitle: String? = null, messages: List<Message>, disappearAfter: Int,
                     onSetDisappear: suspend (Int) -> Unit, infoRoute: String, infoLabel: String, onSendText: suspend (String) -> Unit,
                     onSendImage: suspend (Bitmap) -> Unit, showSender: Boolean, banners: @Composable ColumnScope.() -> Unit) {
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.isMutableRequired = true } }
            onSendImage(bmp)
        }
    }
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
        Row(Modifier.fillMaxWidth().background(Sowa.card.copy(alpha = 0.9f)).padding(10.dp).imePadding().navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
            IconButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.Image, "Zdjęcie", tint = Sowa.cyan) }
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Szepnij coś…") }, maxLines = 6, shape = RoundedCornerShape(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (draft.isBlank()) Brush2(Sowa.border) else Sowa.action)
                .clickable(enabled = draft.isNotBlank()) { val t = draft; draft = ""; scope.launch { onSendText(t) } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowUpward, "Wyślij", tint = Color.White)
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            banners()
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages, key = { it.id }) { m ->
                    Bubble(engine, m, onRetry = { scope.launch { engine.retry(m) } }, onTapImage = { preview = it },
                        sender = if (showSender && !m.isMine && m.senderId != null) senderName(engine, m) else null)
                }
            }
        }
    }
    preview?.let { bmp -> Box(Modifier.fillMaxSize().background(Color.Black).clickable { preview = null }, contentAlignment = Alignment.Center) { Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth()) } }
}

@Composable
private fun Banner(bg: Color, icon: ImageVector, text: String, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Sowa.cyan); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text("Sprawdź", color = Sowa.cyan, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun Bubble(engine: Engine, m: Message, onRetry: () -> Unit, onTapImage: (Bitmap) -> Unit, sender: String? = null) {
    if (m.groupId != null && m.senderId == null && !m.isMine && m.attW == 0) {   // notatka systemowa grupy (ktoś dodał, opuścił, zmienił)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(m.text, color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp)) }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.isMine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.isMine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 290.dp)) {
            sender?.let { Text(it, color = Sowa.cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)) }
            if (m.attW > 0) {
                val bmp = remember(m.attachmentFile) { m.attachmentFile?.let { BitmapFactory.decodeFile(File(engine.mediaDir, it).path) } }
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.widthIn(max = 240.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).clickable { onTapImage(bmp) })
                else Box(Modifier.size(200.dp, 150.dp).clip(RoundedCornerShape(14.dp)).background(Sowa.card), contentAlignment = Alignment.Center) { if (m.text.isEmpty()) CircularProgressIndicator() else Text(m.text, style = MaterialTheme.typography.bodySmall) }
            } else Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (m.isMine) Sowa.userBubble else Brush2(Sowa.card)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(m.text, color = if (m.isMine) Color.White else MaterialTheme.colorScheme.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.expiresAt != null) Icon(Icons.Default.Timer, null, tint = Sowa.textMuted, modifier = Modifier.size(10.dp))
                Text(hhmm(m.sentAt), color = Sowa.textMuted, fontSize = 10.sp)
                if (m.isMine) when (m.status) {
                    Message.PENDING -> Icon(Icons.Default.Schedule, null, tint = Sowa.textMuted, modifier = Modifier.size(10.dp))
                    Message.SENT -> Icon(Icons.Default.Check, null, tint = Sowa.textMuted, modifier = Modifier.size(10.dp))
                    Message.DELIVERED -> Icon(Icons.Default.CheckCircle, null, tint = Sowa.cyan, modifier = Modifier.size(10.dp))
                    else -> Text(" ponów", color = Sowa.warn, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onRetry))
                }
            }
        }
    }
}
private fun Brush2(c: Color) = androidx.compose.ui.graphics.Brush.linearGradient(listOf(c, c))

// ---------- dodawanie kontaktu ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(engine: Engine, nav: NavHostController) {
    var tab by remember { mutableStateOf(0) }
    var pasted by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf<ContactLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { ContactLink.parse(it)?.let { l -> scanned = l } ?: run { error = "To nie jest kod Szept." } } }
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Dodaj kontakt") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TabRow(tab, containerColor = Color.Transparent) { Tab(tab == 0, { tab = 0 }, text = { Text("Mój kod") }); Tab(tab == 1, { tab = 1 }, text = { Text("Skanuj") }) }
            if (tab == 0) engine.myLink?.let { link ->
                Card2 {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val qr = remember(link.url) { qrBitmap(link.url) }
                        Image(qr.asImageBitmap(), "Kod QR", Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp))
                        Text("Pokaż ten kod osobie, z którą chcesz rozmawiać, albo wyślij link innym kanałem. Kod zawiera Twój identyfikator i klucz publiczny – nic więcej.", color = Sowa.textMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ clipboard.setText(AnnotatedString(link.url)) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Kopiuj link") }
                            val ctx = LocalContext.current
                            OutlinedButton({ ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, link.url) }, "Udostępnij")) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Udostępnij") }
                        }
                        Text("ID: ${link.accountId}", color = Sowa.textMuted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else Card2 {
                Button({ scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Zeskanuj kod Szept").setBeepEnabled(false).setOrientationLocked(true)) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Skanuj kod QR")
                }
                Text("albo wklej link", color = Sowa.textMuted, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(pasted, { pasted = it }, Modifier.weight(1f), placeholder = { Text("szept://add?…") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button({ ContactLink.parse(pasted)?.let { scanned = it } ?: run { error = "Niepoprawny link." } }) { Text("Dodaj") }
                }
                error?.let { Text(it, color = Sowa.warn, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    scanned?.let { link -> AddContactDialog(engine, link) { added -> scanned = null; if (added) nav.popBackStack() } }
}

@Composable
fun AddContactDialog(engine: Engine, link: ContactLink, onDone: (Boolean) -> Unit) {
    var name by remember { mutableStateOf(link.name ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { onDone(false) }, title = { Text("Nowy kontakt") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nazwa kontaktu") }, singleLine = true)
            Text("ID: ${link.accountId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted)
            error?.let { Text(it, color = Sowa.warn) }
        }
    }, confirmButton = { TextButton({ try { engine.addContact(link, name); onDone(true) } catch (e: Exception) { error = e.message } }) { Text("Dodaj") } },
        dismissButton = { TextButton({ onDone(false) }) { Text("Anuluj") } })
}

fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

// ---------- info o kontakcie ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(engine: Engine, nav: NavHostController, id: String) {
    val v by engine.db.version.collectAsState()
    val c = remember(v) { engine.db.contact(id) } ?: return
    var name by remember { mutableStateOf(c.name) }
    val safety = remember(c.identityKey) { try { engine.proto.safetyNumber(c.id, c.identityKey) } catch (e: Exception) { "niedostępny" } }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Kontakt") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.ifEmpty { "?" }, 56); Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it; c.name = it; engine.db.save(c) }, Modifier.weight(1f), label = { Text("Nazwa") }, singleLine = true)
                }
                Text("ID: ${c.id}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Card2 {
                Text("Numer bezpieczeństwa", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Text(safety, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
                Text("Porównaj ten numer z numerem widocznym u kontaktu (osobiście albo głosowo). Jeśli są identyczne, nikt nie podsłuchuje po drodze – także my jako operator serwera.", color = Sowa.textMuted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) { Text("Zweryfikowano osobiście", Modifier.weight(1f)); Switch(c.verified, { c.verified = it; engine.db.save(c) }) }
                if (c.identityChanged) TextButton({ engine.acceptNewIdentity(c) }) { Icon(Icons.Default.Key, null, tint = Sowa.warn); Spacer(Modifier.width(6.dp)); Text("Zaakceptuj nowy klucz kontaktu", color = Sowa.warn) }
            }
            Card2 {
                Text("Znikające wiadomości", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Durations.options.forEach { (s, l) -> Row(Modifier.fillMaxWidth().clickable { scope.launch { engine.setDisappear(s, c) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(c.disappearAfter == s, null); Text(l)
                } }
            }
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Usuń rozmowę i kontakt") }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Usunąć rozmowę z ${c.name}?") }, text = { Text("Wiadomości znikną z tego telefonu.") },
        confirmButton = { TextButton({ engine.delete(c); confirm = false; nav.popBackStack("list", false) }) { Text("Usuń", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Anuluj") } })
}

// ---------- ustawienia ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(engine: Engine, nav: NavHostController) {
    var name by remember { mutableStateOf(engine.displayName) }
    var server by remember { mutableStateOf(engine.serverUrl) }
    var serverOk by remember { mutableStateOf<Boolean?>(null) }
    var listen by remember { mutableStateOf(engine.listenInBackground) }
    var lock by remember { mutableStateOf(engine.appLock) }
    var counts by remember { mutableStateOf("…") }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { counts = withContext(Dispatchers.IO) { try { engine.api.keyCounts().let { "${it.prekeys} / ${it.kyber_prekeys}" } } catch (e: Exception) { "–" } } }
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Ustawienia") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Text("Ja", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it; engine.displayName = it }, Modifier.fillMaxWidth(), label = { Text("Nazwa dla kontaktów") }, singleLine = true)
                Text("Mój identyfikator: ${engine.accountId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, modifier = Modifier.padding(top = 6.dp))
                Text("Klucze jednorazowe na serwerze: $counts", style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted)
            }
            Card2 {
                Text("Bezpieczeństwo", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Blokada biometryczna / kodem", Modifier.weight(1f)); Switch(lock, { lock = it; engine.appLock = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Nasłuch w tle"); Text("Stałe połączenie z Twoim serwerem zamiast usług Google. Dostajesz powiadomienie „Nowa wiadomość” bez treści.", style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted) }
                    Switch(listen, { listen = it; engine.listenInBackground = it; if (it) ListenService.start(ctx) else ListenService.stop(ctx) })
                }
            }
            Card2 {
                Text("Serwer", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Adres") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ engine.serverUrl = server.trim(); scope.launch { serverOk = withContext(Dispatchers.IO) { try { engine.api.health() } catch (e: Exception) { false } }; engine.connect() } }) { Text("Zapisz i sprawdź") }
                    serverOk?.let { Icon(if (it) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (it) Sowa.ok else Sowa.warn) }
                }
                Text("Serwer widzi tylko: Twój losowy identyfikator, klucze publiczne i zaszyfrowane koperty do chwili odbioru. Nie zna nadawcy, treści ani Twoich kontaktów.", style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted)
            }
            Card2 {
                Text("Co wie kto", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                listOf("Treść wiadomości" to "tylko Ty i odbiorca", "Kto z kim pisze" to "nikt (koperta HPKE, wysyłka anonimowa)", "Kiedy odbierasz" to "serwer (konieczne do doręczenia)",
                    "Adres IP" to "Cloudflare i serwer na czas połączenia, nie zapisywany", "Google" to "nic – brak Play Services, własny kanał powiadomień").forEach { (a, b) ->
                    Column(Modifier.padding(vertical = 4.dp)) { Text(a); Text(b, style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted) }
                }
            }
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Usuń konto i wszystkie dane") }
            Text("Szept ${BuildConfig.VERSION_NAME} · libsignal (AGPL) · kod źródłowy otwarty", style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Usunąć konto?") }, text = { Text("Klucze, kontakty i wiadomości znikną bezpowrotnie z tego telefonu i z serwera.") },
        confirmButton = { TextButton({ confirm = false; scope.launch { engine.deleteEverything(); ListenService.stop(ctx); nav.popBackStack("list", false) } }) { Text("Usuń wszystko", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Anuluj") } })
}

// ---------- grupy ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(engine: Engine, nav: NavHostController) {
    val v by engine.db.version.collectAsState()
    val contacts = remember(v) { engine.db.contacts() }
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Nowa grupa") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(56); Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Nazwa grupy") }, placeholder = { Text("np. Ekipa") }, singleLine = true)
                }
                Text("Grupa istnieje tylko w telefonach członków. Serwer nie wie, kto w niej jest – każda wiadomość jest szyfrowana osobno do każdej osoby.",
                    color = Sowa.textMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Text("Członkowie (${selected.size})", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(contacts, key = { it.id }) { c -> MemberPickRow(c, c.id in selected) { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) } }
            }
            GradientButton(if (busy) "Tworzę…" else "Utwórz grupę", enabled = selected.isNotEmpty() && !busy) {
                busy = true
                scope.launch {
                    val g = engine.createGroup(name, contacts.filter { it.id in selected })
                    nav.popBackStack(); nav.navigate("group/${g.id}")
                }
            }
        }
    }
}

@Composable
private fun MemberPickRow(c: Contact, checked: Boolean, onToggle: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sowa.card).clickable(onClick = onToggle).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.name, 36); Spacer(Modifier.width(10.dp))
        Text(c.name, Modifier.weight(1f))
        if (c.verified) Icon(Icons.Default.Verified, null, tint = Sowa.ok, modifier = Modifier.size(16.dp))
        Checkbox(checked, { onToggle() })
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(engine: Engine, nav: NavHostController, id: String) {
    val v by engine.db.version.collectAsState()
    val g = remember(v) { engine.db.group(id) } ?: run { LaunchedEffect(Unit) { nav.popBackStack("list", false) }; return }
    val members = remember(v) { g.members.map { mid -> engine.db.contact(mid) ?: Contact(mid, "Nieznany ${mid.take(6)}", ByteArray(0), fromUnknown = true) } }
    val others = remember(v) { engine.db.contacts().filter { it.id !in g.members } }
    var name by remember(g.name) { mutableStateOf(g.name) }
    var addDialog by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Grupa") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(56); Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Nazwa grupy") }, singleLine = true)
                }
                if (name.trim() != g.name && name.isNotBlank()) TextButton({ scope.launch { engine.renameGroup(g, name) } }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Zapisz nazwę (wyśle do członków)") }
                Text("ID grupy: ${g.id}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Członkowie (${members.size + 1})", color = Sowa.cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton({ picked.clear(); addDialog = true }, enabled = others.isNotEmpty()) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(4.dp)); Text("Dodaj osoby") }
                }
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(engine.displayName.ifEmpty { "Ja" }, 36); Spacer(Modifier.width(10.dp)); Text(engine.displayName.ifEmpty { "Ja" }, Modifier.weight(1f)); Text("Ty", color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                members.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = c.identityKey.isNotEmpty()) { nav.navigate("contact/${c.id}") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 36); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            Text(when { c.identityChanged -> "klucz się zmienił – sprawdź"; c.verified -> "zweryfikowano osobiście"; c.fromUnknown -> "niezweryfikowany (dodany przez grupę)"; else -> "niezweryfikowany" },
                                color = if (c.identityChanged) Sowa.warn else Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (c.verified) Icon(Icons.Default.Verified, null, tint = Sowa.ok, modifier = Modifier.size(16.dp))
                        if (c.identityChanged) Icon(Icons.Default.Warning, null, tint = Sowa.warn, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Card2 {
                Text("Znikające wiadomości", color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Durations.options.forEach { (s, l) -> Row(Modifier.fillMaxWidth().clickable { scope.launch { engine.setDisappear(s, g) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(g.disappearAfter == s, null); Text(l)
                } }
            }
            OutlinedButton({ confirmLeave = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(Modifier.width(6.dp)); Text("Opuść grupę") }
        }
    }
    if (addDialog) AlertDialog({ addDialog = false }, title = { Text("Dodaj osoby") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(others, key = { it.id }) { c -> MemberPickRow(c, c.id in picked) { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) } }
        }
    }, confirmButton = { TextButton({ val sel = others.filter { it.id in picked }; addDialog = false; scope.launch { engine.addMembers(g, sel) } }, enabled = picked.isNotEmpty()) { Text("Dodaj") } },
        dismissButton = { TextButton({ addDialog = false }) { Text("Anuluj") } })
    if (confirmLeave) AlertDialog({ confirmLeave = false }, title = { Text("Opuścić grupę „${g.name}”?") }, text = { Text("Pozostali dostaną informację, a wiadomości grupy znikną z tego telefonu.") },
        confirmButton = { TextButton({ confirmLeave = false; scope.launch { engine.leaveGroup(g); nav.popBackStack("list", false) } }) { Text("Opuść", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Anuluj") } })
}
