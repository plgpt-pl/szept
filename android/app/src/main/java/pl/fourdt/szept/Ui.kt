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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
        Logo(96); Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
        Button(onClick = onUnlock) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.unlock)) }
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
        Text(stringResource(R.string.tagline), color = Sowa.textMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Card2 {
            Bullet(Icons.Default.PersonOff, stringResource(R.string.onb_bullet1))
            Bullet(Icons.Default.Shield, stringResource(R.string.onb_bullet2))
            Bullet(Icons.Default.VisibilityOff, stringResource(R.string.onb_bullet3))
            Bullet(Icons.Default.Timer, stringResource(R.string.onb_bullet4))
        }
        Card2 {
            Text(stringResource(R.string.name_for_contacts_optional), color = Sowa.textMuted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.name_example)) }, singleLine = true)
            TextButton({ advanced = !advanced }) { Text(stringResource(R.string.advanced)) }
            if (advanced) OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_address)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        error?.let { Text(it, color = Sowa.warn, textAlign = TextAlign.Center) }
        GradientButton(stringResource(if (busy) R.string.creating else R.string.create_identity), enabled = !busy) {
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
            navigationIcon = { IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when (conn) { Connection.ONLINE -> Sowa.ok; Connection.CONNECTING -> Sowa.warn; else -> Color.Gray }))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(when (conn) { Connection.ONLINE -> R.string.conn_online; Connection.CONNECTING -> R.string.conn_connecting; else -> R.string.conn_offline }), color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                IconButton({ plus = true }) { Icon(Icons.Default.AddCircle, stringResource(R.string.add), tint = Sowa.cyan) }
                DropdownMenu(plus, { plus = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.add_contact)) }, { plus = false; nav.navigate("add") }, leadingIcon = { Icon(Icons.Default.QrCode, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.new_group)) }, { plus = false; nav.navigate("newgroup") }, leadingIcon = { Icon(Icons.Default.Group, null) }, enabled = contacts.isNotEmpty())
                }
            })
    }) { pad ->
        if (items.isEmpty()) Column(Modifier.fillMaxSize().padding(pad).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Logo(90); Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.empty_text), color = Sowa.textMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
            Button({ nav.navigate("add") }) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_contact)) }
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
                Text(if (last == null) stringResource(R.string.no_messages) else (if (last.isMine) stringResource(R.string.you_prefix) else "") + last.summary(),
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
fun senderName(engine: Engine, m: Message): String = m.senderId?.let { engine.db.contact(it)?.name } ?: Strings.get(R.string.unknown)

@Composable
fun GroupRow(engine: Engine, g: Group, onClick: () -> Unit) {
    val v by engine.db.version.collectAsState()
    val last = remember(g, v) { engine.db.lastMessageForGroup(g.id) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sowa.card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupAvatar(44); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, fontWeight = FontWeight.SemiBold)
                Text("  " + pluralStringResource(R.plurals.members, g.members.size + 1, g.members.size + 1), color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f)); Text(timeAgo(g.lastActivity), color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val who = when { last == null -> ""; last.isMine -> stringResource(R.string.you_prefix); last.senderId != null -> senderName(engine, last) + ": "; else -> "" }
                Text(if (last == null) stringResource(R.string.no_messages) else who + last.summary(),
                    color = Sowa.textMuted, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (g.disappearAfter > 0) Icon(Icons.Default.Timer, null, tint = Sowa.textMuted, modifier = Modifier.size(12.dp))
                if (g.unread > 0) Text("${g.unread}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(Sowa.action).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun timeAgo(t: Long): String { val d = (System.currentTimeMillis() - t) / 1000; return when { d < 60 -> stringResource(R.string.time_now); d < 3600 -> stringResource(R.string.time_min, d / 60); d < 86400 -> stringResource(R.string.time_hours, d / 3600); else -> stringResource(R.string.time_days, d / 86400) } }
fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

internal fun Brush2(c: Color) = androidx.compose.ui.graphics.Brush.linearGradient(listOf(c, c))

// ---------- dodawanie kontaktu ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(engine: Engine, nav: NavHostController) {
    var tab by remember { mutableStateOf(0) }
    var pasted by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf<ContactLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { ContactLink.parse(it)?.let { l -> scanned = l } ?: run { error = ctx.getString(R.string.not_szept_code) } } }
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.add_contact)) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TabRow(tab, containerColor = Color.Transparent) { Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.my_code)) }); Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.scan)) }) }
            if (tab == 0) engine.myLink?.let { link ->
                Card2 {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val qr = remember(link.url) { qrBitmap(link.url) }
                        Image(qr.asImageBitmap(), stringResource(R.string.qr_code), Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp))
                        Text(stringResource(R.string.show_code_hint), color = Sowa.textMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ clipboard.setText(AnnotatedString(link.url)) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.copy_link)) }
                            OutlinedButton({ ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, link.url) }, ctx.getString(R.string.share))) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share)) }
                        }
                        Text(stringResource(R.string.id_label, link.accountId), color = Sowa.textMuted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else Card2 {
                Button({ scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(ctx.getString(R.string.scan_prompt)).setBeepEnabled(false).setOrientationLocked(true)) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_qr))
                }
                Text(stringResource(R.string.or_paste_link), color = Sowa.textMuted, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(pasted, { pasted = it }, Modifier.weight(1f), placeholder = { Text("szept://add?…") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button({ ContactLink.parse(pasted)?.let { scanned = it } ?: run { error = ctx.getString(R.string.invalid_link) } }) { Text(stringResource(R.string.add)) }
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
    AlertDialog(onDismissRequest = { onDone(false) }, title = { Text(stringResource(R.string.new_contact)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.contact_name)) }, singleLine = true)
            Text(stringResource(R.string.id_label, link.accountId), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted)
            error?.let { Text(it, color = Sowa.warn) }
        }
    }, confirmButton = { TextButton({ try { engine.addContact(link, name); onDone(true) } catch (e: Exception) { error = e.message } }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton({ onDone(false) }) { Text(stringResource(R.string.cancel)) } })
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
    val safety = remember(c.identityKey) { try { engine.proto.safetyNumber(c.id, c.identityKey) } catch (e: Exception) { Strings.get(R.string.unavailable) } }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.contact)) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.ifEmpty { "?" }, 56); Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it; c.name = it; engine.db.save(c) }, Modifier.weight(1f), label = { Text(stringResource(R.string.name)) }, singleLine = true)
                }
                Text(stringResource(R.string.id_label, c.id), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Card2 {
                Text(stringResource(R.string.safety_number), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Text(safety, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.safety_hint), color = Sowa.textMuted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.verified_in_person), Modifier.weight(1f)); Switch(c.verified, { c.verified = it; engine.db.save(c) }) }
                if (c.identityChanged) TextButton({ engine.acceptNewIdentity(c) }) { Icon(Icons.Default.Key, null, tint = Sowa.warn); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.accept_new_key), color = Sowa.warn) }
            }
            Card2 {
                Text(stringResource(R.string.disappearing), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Durations.options.forEach { (s, l) -> Row(Modifier.fillMaxWidth().clickable { scope.launch { engine.setDisappear(s, c) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(c.disappearAfter == s, null); Text(stringResource(l))
                } }
            }
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.delete_chat_contact)) }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text(stringResource(R.string.delete_chat_q, c.name)) }, text = { Text(stringResource(R.string.delete_chat_text)) },
        confirmButton = { TextButton({ engine.delete(c); confirm = false; nav.popBackStack("list", false) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } })
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
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Text(stringResource(R.string.section_me), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it; engine.displayName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name_for_contacts)) }, singleLine = true)
                Text(stringResource(R.string.my_id, engine.accountId ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.one_time_keys, counts), style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted)
            }
            Card2 {
                Text(stringResource(R.string.security), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.biometric_lock), Modifier.weight(1f)); Switch(lock, { lock = it; engine.appLock = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.listen_bg)); Text(stringResource(R.string.listen_bg_hint), style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted) }
                    Switch(listen, { listen = it; engine.listenInBackground = it; if (it) ListenService.start(ctx) else ListenService.stop(ctx) })
                }
            }
            Card2 {
                Text(stringResource(R.string.server), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.address)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ engine.serverUrl = server.trim(); scope.launch { serverOk = withContext(Dispatchers.IO) { try { engine.api.health() } catch (e: Exception) { false } }; engine.connect() } }) { Text(stringResource(R.string.save_and_check)) }
                    serverOk?.let { Icon(if (it) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (it) Sowa.ok else Sowa.warn) }
                }
                Text(stringResource(R.string.server_sees), style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted)
            }
            Card2 {
                Text(stringResource(R.string.who_knows), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                listOf(R.string.wk1a to R.string.wk1b, R.string.wk2a to R.string.wk2b, R.string.wk3a to R.string.wk3b, R.string.wk4a to R.string.wk4b, R.string.wk5a to R.string.wk5b).forEach { (a, b) ->
                    Column(Modifier.padding(vertical = 4.dp)) { Text(stringResource(a)); Text(stringResource(b), style = MaterialTheme.typography.bodySmall, color = Sowa.textMuted) }
                }
            }
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.delete_account_all)) }
            Text(stringResource(R.string.about_line, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text(stringResource(R.string.delete_account_q)) }, text = { Text(stringResource(R.string.delete_account_text)) },
        confirmButton = { TextButton({ confirm = false; scope.launch { engine.deleteEverything(); ListenService.stop(ctx); nav.popBackStack("list", false) } }) { Text(stringResource(R.string.delete_everything), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } })
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
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.new_group)) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(56); Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.group_name)) }, placeholder = { Text(stringResource(R.string.group_name_example)) }, singleLine = true)
                }
                Text(stringResource(R.string.group_hint),
                    color = Sowa.textMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Text(stringResource(R.string.members_n, selected.size), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(contacts, key = { it.id }) { c -> MemberPickRow(c, c.id in selected) { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) } }
            }
            GradientButton(stringResource(if (busy) R.string.creating else R.string.create_group), enabled = selected.isNotEmpty() && !busy) {
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
    val members = remember(v) { g.members.map { mid -> engine.db.contact(mid) ?: Contact(mid, Strings.get(R.string.unknown_short, mid.take(6)), ByteArray(0), fromUnknown = true) } }
    val others = remember(v) { engine.db.contacts().filter { it.id !in g.members } }
    var name by remember(g.name) { mutableStateOf(g.name) }
    var addDialog by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.group)) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(56); Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.group_name)) }, singleLine = true)
                }
                if (name.trim() != g.name && name.isNotBlank()) TextButton({ scope.launch { engine.renameGroup(g, name) } }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save_name_sends)) }
                Text(stringResource(R.string.group_id, g.id), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Sowa.textMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.members_n, members.size + 1), color = Sowa.cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton({ picked.clear(); addDialog = true }, enabled = others.isNotEmpty()) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_people)) }
                }
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(engine.displayName.ifEmpty { stringResource(R.string.me) }, 36); Spacer(Modifier.width(10.dp)); Text(engine.displayName.ifEmpty { stringResource(R.string.me) }, Modifier.weight(1f)); Text(stringResource(R.string.you), color = Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                members.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = c.identityKey.isNotEmpty()) { nav.navigate("contact/${c.id}") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 36); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            Text(stringResource(when { c.identityChanged -> R.string.member_key_changed; c.verified -> R.string.member_verified; c.fromUnknown -> R.string.member_unverified_group; else -> R.string.member_unverified }),
                                color = if (c.identityChanged) Sowa.warn else Sowa.textMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (c.verified) Icon(Icons.Default.Verified, null, tint = Sowa.ok, modifier = Modifier.size(16.dp))
                        if (c.identityChanged) Icon(Icons.Default.Warning, null, tint = Sowa.warn, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Card2 {
                Text(stringResource(R.string.disappearing), color = Sowa.cyan, fontWeight = FontWeight.SemiBold)
                Durations.options.forEach { (s, l) -> Row(Modifier.fillMaxWidth().clickable { scope.launch { engine.setDisappear(s, g) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(g.disappearAfter == s, null); Text(stringResource(l))
                } }
            }
            OutlinedButton({ confirmLeave = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.leave_group)) }
        }
    }
    if (addDialog) AlertDialog({ addDialog = false }, title = { Text(stringResource(R.string.add_people)) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(others, key = { it.id }) { c -> MemberPickRow(c, c.id in picked) { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) } }
        }
    }, confirmButton = { TextButton({ val sel = others.filter { it.id in picked }; addDialog = false; scope.launch { engine.addMembers(g, sel) } }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton({ addDialog = false }) { Text(stringResource(R.string.cancel)) } })
    if (confirmLeave) AlertDialog({ confirmLeave = false }, title = { Text(stringResource(R.string.leave_group_q, g.name)) }, text = { Text(stringResource(R.string.leave_group_text)) },
        confirmButton = { TextButton({ confirmLeave = false; scope.launch { engine.leaveGroup(g); nav.popBackStack("list", false) } }) { Text(stringResource(R.string.leave), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text(stringResource(R.string.cancel)) } })
}
