package com.example.pulse.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.example.pulse.data.HomeworkItem
import com.example.pulse.data.HomeworkManager
import com.example.pulse.utils.HomeworkNfcHelper
import com.example.pulse.utils.HomeworkQrHelper
import com.example.pulse.utils.NfcImportResult
import com.example.pulse.utils.NfcWriteResult
import com.example.pulse.utils.PulseHceService
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkScreen(navController: NavController) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var homeworkList by remember { mutableStateOf(HomeworkManager.loadHomeworks(context)) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showClearCompletedDialog by remember { mutableStateOf(false) }

    var showQrShareDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var showNfcDialog by remember { mutableStateOf(false) }

    fun reload() {
        homeworkList = HomeworkManager.loadHomeworks(context)
    }

    fun startQrScan() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (rawValue != null && (rawValue.startsWith("MGKAP_HW:") || rawValue.startsWith("PULSE_HW:"))) {
                    val count = HomeworkQrHelper.importFromQrPayload(context, rawValue)
                    if (count > 0) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, "Импортировано заданий: $count", Toast.LENGTH_SHORT).show()
                        reload()
                    } else {
                        Toast.makeText(context, "Все эти задания уже добавлены", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Некорректный QR-код задания МГКАП", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { }
    }

    val filteredList = remember(homeworkList, selectedTab) {
        if (selectedTab == 0) {
            homeworkList.filter { !it.isCompleted }
        } else {
            homeworkList.filter { it.isCompleted }
        }
    }

    val grouped = remember(filteredList) {
        filteredList.groupBy { it.dueDate }.toSortedMap()
    }

    val formatter = remember {
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Все задания",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBackIosNew,
                            contentDescription = "Назад",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!HomeworkNfcHelper.isNfcSupported(context)) {
                                Toast.makeText(context, "NFC не поддерживается на этом телефоне", Toast.LENGTH_SHORT).show()
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showNfcDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Nfc,
                            contentDescription = "NFC карта",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            val payload = HomeworkQrHelper.exportToQrPayload(homeworkList)
                            if (payload != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                qrBitmap = HomeworkQrHelper.generateQrBitmap(payload)
                                showQrShareDialog = true
                            } else {
                                Toast.makeText(context, "Нет активных заданий для передачи", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = "Поделиться ДЗ по QR",
                            tint = Color.White
                        )
                    }

                    IconButton(onClick = { startQrScan() }) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Сканировать QR",
                            tint = PrimaryPurple
                        )
                    }

                    if (selectedTab == 1 && filteredList.isNotEmpty()) {
                        IconButton(onClick = { showClearCompletedDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Очистить выполненные",
                                tint = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(16.dp))
                    .padding(4.dp)
            ) {
                TabButtonHW(
                    text = "Активные",
                    isSelected = selectedTab == 0,
                    modifier = Modifier.weight(1f)
                ) {
                    if (selectedTab != 0) selectedTab = 0
                }

                TabButtonHW(
                    text = "Выполненные",
                    isSelected = selectedTab == 1,
                    modifier = Modifier.weight(1f)
                ) {
                    if (selectedTab != 1) selectedTab = 1
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            AnimatedContent(
                targetState = filteredList.isEmpty(),
                transitionSpec = {
                    fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                },
                label = "empty_state"
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyState(
                        isActiveTab = selectedTab == 0,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        grouped.forEach { (date, items) ->
                            stickyHeader {
                                DateHeader(
                                    date = date,
                                    formatter = formatter,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgDark)
                                        .padding(vertical = 8.dp)
                                )
                            }

                            items(
                                items = items,
                                key = { it.id }
                            ) { hw ->
                                HomeworkCard(
                                    homework = hw,
                                    onToggle = {
                                        HomeworkManager.toggleCompleted(context, hw.id)
                                        reload()
                                    },
                                    onDelete = {
                                        showDeleteDialog = hw.id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNfcDialog) {
        NfcHologramCardDialog(
            homeworks = homeworkList,
            onDismiss = { showNfcDialog = false },
            onImportSuccess = { count, senderName ->
                reload()
                Toast.makeText(context, "Получено $count заданий от $senderName", Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showQrShareDialog && qrBitmap != null) {
        val activeCount = homeworkList.count { !it.isCompleted }
        Dialog(onDismissRequest = { showQrShareDialog = false }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Передача ДЗ",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Одногруппник может отсканировать этот QR сканером в Pulse",
                        color = TextGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR-код заданий",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Активных заданий в коде: $activeCount",
                        color = PrimaryPurple,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = { showQrShareDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Готово", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    showDeleteDialog?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Удалить задание?") },
            text = { Text("Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        HomeworkManager.deleteHomework(context, id)
                        reload()
                        showDeleteDialog = null
                    }
                ) {
                    Text("Удалить", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Отмена")
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = Color.White,
            textContentColor = TextGray
        )
    }

    if (showClearCompletedDialog) {
        AlertDialog(
            onDismissRequest = { showClearCompletedDialog = false },
            title = { Text("Очистить выполненные?") },
            text = { Text("Все выполненные задания будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        HomeworkManager.deleteCompleted(context)
                        reload()
                        showClearCompletedDialog = false
                    }
                ) {
                    Text("Очистить", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCompletedDialog = false }) {
                    Text("Отмена")
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = Color.White,
            textContentColor = TextGray
        )
    }
}

@Composable
fun NfcHologramCardDialog(
    homeworks: List<HomeworkItem>,
    onDismiss: () -> Unit,
    onImportSuccess: (Int, String) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE) }

    val myName = remember { prefs.getString("user_name", "Студент") ?: "Студент" }
    val myGroup = remember { prefs.getString("user_group", "000") ?: "000" }

    var nfcMode by remember { mutableIntStateOf(0) }
    var displayedCardName by remember { mutableStateOf(myName.uppercase()) }
    var statusText by remember { mutableStateOf("Поднесите телефон спинкой к другому устройству") }
    var isSuccess by remember { mutableStateOf(false) }
    var detectedChipType by remember { mutableStateOf<String?>(null) }

    val cardFlip = remember { Animatable(0f) }
    val sparkProgress = remember { Animatable(0f) }

    val infiniteRadar = rememberInfiniteTransition(label = "nfc_radar")
    val radarPhase by infiniteRadar.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_phase"
    )

    var rawTiltX by remember { mutableFloatStateOf(0f) }
    var rawTiltY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null) {
                    rawTiltX = (event.values[0] / 9.8f).coerceIn(-1f, 1f)
                    rawTiltY = (event.values[1] / 9.8f).coerceIn(-1f, 1f)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    val tiltX by animateFloatAsState(targetValue = rawTiltX, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "tiltX")
    val tiltY by animateFloatAsState(targetValue = rawTiltY, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "tiltY")

    // P2P HCE Payload
    val rawPayload = remember(homeworks) { HomeworkQrHelper.exportToQrPayload(homeworks) }
    val fullNfcMessage = remember(rawPayload, myName) {
        if (rawPayload == null) null
        else {
            JSONObject().apply {
                put("v", 2)
                put("from", myName)
                put("data", rawPayload)
            }.toString()
        }
    }

    // Ультра-компактный сжатый пейлоад для физических карт (NTAG213 и др.)
    val (cardPayload, packedCount) = remember(homeworks, myName, myGroup) {
        HomeworkNfcHelper.buildCardPayload(myName, homeworks, myGroup, maxCapacityBytes = 137)
    }

    val isEnabled = remember { HomeworkNfcHelper.isNfcEnabled(context) }

    LaunchedEffect(nfcMode, fullNfcMessage) {
        PulseHceService.currentPayload = if (nfcMode == 0) fullNfcMessage else null
        displayedCardName = if (nfcMode != 2) myName.uppercase() else "ОЖИДАНИЕ КАРТЫ..."
        isSuccess = false
        detectedChipType = null

        statusText = when (nfcMode) {
            0 -> "Совместите телефоны спинками (Pulse HCE)"
            1 -> if (packedCount > 0) "Приложите карту для записи $packedCount заданий" else "Приложите карту для записи ID Pulse"
            else -> "Поднесите карту или смартфон для считывания"
        }
    }

    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            sparkProgress.snapTo(0f)
            sparkProgress.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
        }
    }

    DisposableEffect(nfcMode, isEnabled, cardPayload) {
        val adapter = NfcAdapter.getDefaultAdapter(context)

        // Режим 0: Смартфон -> Смартфон (Pure HCE Sender)
        if (nfcMode == 0) {
            if (activity != null && adapter != null) {
                adapter.disableReaderMode(activity)
            }
            PulseHceService.currentPayload = fullNfcMessage
            PulseHceService.onPayloadSent = {
                activity?.runOnUiThread {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    statusText = "Успешно передано на смартфон!"
                    isSuccess = true
                    coroutineScope.launch {
                        cardFlip.animateTo(360f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                        cardFlip.snapTo(0f)
                    }
                }
            }
        } else {
            PulseHceService.currentPayload = null
            PulseHceService.onPayloadSent = null
        }

        // Режим 1 (Запись на карту) и Режим 2 (Прием с карты / смартфона)
        if (activity != null && adapter != null && isEnabled && (nfcMode == 1 || nfcMode == 2)) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

            val options = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
            }

            adapter.enableReaderMode(
                activity,
                { tag ->
                    when (nfcMode) {
                        1 -> {
                            val result = HomeworkNfcHelper.writePayloadToPhysicalTag(
                                context = context,
                                tag = tag,
                                payload = cardPayload,
                                itemsCount = packedCount
                            )
                            activity.runOnUiThread {
                                when (result) {
                                    is NfcWriteResult.Success -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        detectedChipType = result.tagType
                                        isSuccess = true
                                        statusText = if (result.isStudentProfile) {
                                            "Записан профиль ID Pulse (${result.bytesWritten} B)"
                                        } else {
                                            "Записано: ${result.itemsCount} зад. (${result.bytesWritten} B)"
                                        }
                                        coroutineScope.launch {
                                            cardFlip.animateTo(360f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                            cardFlip.snapTo(0f)
                                        }
                                    }
                                    is NfcWriteResult.ReadOnly -> {
                                        statusText = "Карта защищена от записи"
                                    }
                                    is NfcWriteResult.NotEnoughSpace -> {
                                        statusText = "Мало памяти: нужно ${result.needed} B (доступно ${result.max} B)"
                                    }
                                    is NfcWriteResult.NotFormatable -> {
                                        statusText = "Не поддерживается: ${result.techList.joinToString()}"
                                    }
                                    is NfcWriteResult.Failure -> {
                                        statusText = "Сбой: ${result.message}"
                                    }
                                }
                            }
                        }
                        2 -> {
                            val receivedRaw = HomeworkNfcHelper.readPayloadFromTag(tag)
                            activity.runOnUiThread {
                                val importRes = HomeworkNfcHelper.importAnyPayload(context, receivedRaw)
                                when (importRes) {
                                    is NfcImportResult.HomeworkImported -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        displayedCardName = importRes.senderName.uppercase()
                                        isSuccess = true
                                        statusText = "Получено: ${importRes.count} зад. от ${importRes.senderName}"
                                        onImportSuccess(importRes.count, importRes.senderName)
                                        coroutineScope.launch {
                                            cardFlip.animateTo(360f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                            cardFlip.snapTo(0f)
                                        }
                                    }
                                    is NfcImportResult.StudentProfile -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        displayedCardName = importRes.senderName.uppercase()
                                        isSuccess = true
                                        val grp = if (importRes.group.isNotBlank()) " (${importRes.group})" else ""
                                        statusText = "ID-карта: ${importRes.senderName}$grp"
                                        coroutineScope.launch {
                                            cardFlip.animateTo(360f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                            cardFlip.snapTo(0f)
                                        }
                                    }
                                    is NfcImportResult.AlreadyExists -> {
                                        statusText = "Все эти задания уже добавлены"
                                    }
                                    is NfcImportResult.Error -> {
                                        statusText = importRes.message
                                    }
                                }
                            }
                        }
                    }
                },
                flags,
                options
            )
        }

        onDispose {
            PulseHceService.currentPayload = null
            PulseHceService.onPayloadSent = null
            if (activity != null && adapter != null) {
                adapter.disableReaderMode(activity)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = BgDark),
            border = BorderStroke(1.dp, if (isSuccess) Color(0xFF00E676).copy(alpha = 0.5f) else PrimaryPurple.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "МГКАП NFC",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(SurfaceDark, RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSuccess) Color(0xFF00E676).copy(alpha = 0.4f) else PrimaryPurple.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (nfcMode) {
                                0 -> "P2P MODE"
                                1 -> "TAG WRITE"
                                else -> "RECEIVE"
                            },
                            color = if (isSuccess) Color(0xFF00E676) else PrimaryPurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .padding(3.dp)
                ) {
                    val tabs = listOf("Смартфон", "Карта", "Прием")
                    tabs.forEachIndexed { index, title ->
                        val isSelected = nfcMode == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) PrimaryPurple else Color.Transparent)
                                .clickable {
                                    nfcMode = index
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                color = if (isSelected) Color.White else TextGray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Радарные волны в фоне ожидания метки
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        val cy = h / 2f
                        val maxR = (w / 2f) * 0.95f

                        for (i in 0..2) {
                            val frac = (radarPhase + i * 0.333f) % 1f
                            val r = 40f + frac * (maxR - 40f)
                            val ringAlpha = (1f - frac).coerceIn(0f, 1f) * (if (isSuccess) 0.5f else 0.22f)
                            val ringColor = if (isSuccess) Color(0xFF00E676) else PrimaryPurple
                            drawCircle(
                                color = ringColor.copy(alpha = ringAlpha),
                                radius = r,
                                center = Offset(cx, cy),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                        }
                    }

                    // Интерактивная 3D карточка
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(180.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                coroutineScope.launch {
                                    val target = if (cardFlip.value < 90f) 180f else 0f
                                    cardFlip.animateTo(target, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                }
                            }
                    ) {
                        MgcapMinimalistCard(
                            cardName = displayedCardName,
                            groupCode = myGroup,
                            tiltX = tiltX,
                            tiltY = tiltY,
                            flipAngle = cardFlip.value,
                            isSuccess = isSuccess
                        )
                    }

                    // Салют праздничных квантовых искр успеха
                    if (sparkProgress.value in 0.01f..0.99f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val p = sparkProgress.value
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val sparkCount = 12
                            for (i in 0 until sparkCount) {
                                val angle = (i * (360f / sparkCount) + (p * 45f)) * (Math.PI / 180f).toFloat()
                                val dist = 30f + p * 130f
                                val sx = cx + kotlin.math.cos(angle) * dist
                                val sy = cy + kotlin.math.sin(angle) * dist
                                val starAlpha = (1f - p).coerceIn(0f, 1f)
                                val sparkColor = if (i % 2 == 0) Color(0xFF00E676) else Color(0xFFFFD700)
                                drawCircle(
                                    color = sparkColor.copy(alpha = starAlpha),
                                    radius = (4f * (1f - p * 0.5f)).coerceAtLeast(1f),
                                    center = Offset(sx, sy)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Чип / Статус Телеметрия
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(if (isSuccess) Color(0xFF00E676).copy(alpha = 0.12f) else SurfaceDark, RoundedCornerShape(8.dp))
                        .border(1.dp, if (isSuccess) Color(0xFF00E676).copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Rounded.CheckCircle else Icons.Default.Nfc,
                        contentDescription = null,
                        tint = if (isSuccess) Color(0xFF00E676) else PrimaryPurple,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = detectedChipType ?: when (nfcMode) {
                            0 -> "РЕЖИМ ПЕРЕДАТЧИКА"
                            1 -> "ГОТОВ К ЗАПИСИ КАРТЫ"
                            else -> "ГОТОВ К СЧИТЫВАНИЮ"
                        },
                        color = if (isSuccess) Color(0xFF00E676) else TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = statusText,
                    color = if (isSuccess) Color(0xFF00E676) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when (nfcMode) {
                        0 -> "Поднесите телефон спинкой к другому устройству"
                        1 -> "Прикоснитесь картой или брелоком к NFC-модулю смартфона"
                        else -> "Удерживайте контакт 1-2 секунды до вибрации"
                    },
                    color = TextGray,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("Закрыть", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
fun MgcapMinimalistCard(
    cardName: String,
    groupCode: String,
    tiltX: Float,
    tiltY: Float,
    flipAngle: Float,
    isSuccess: Boolean = false
) {
    val isShowingBack = flipAngle in 90f..270f
    val effectiveRotationY = if (isShowingBack) flipAngle - 180f else flipAngle

    val dynamicPrimary = MaterialTheme.colorScheme.primary
    val dynamicTertiary = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .graphicsLayer {
                rotationY = effectiveRotationY
                cameraDistance = 16f * density
            }
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = if (isSuccess) Color(0xFF00E676).copy(alpha = 0.5f) else dynamicPrimary.copy(alpha = 0.4f),
                spotColor = if (isSuccess) Color(0xFF00E676).copy(alpha = 0.6f) else dynamicPrimary.copy(alpha = 0.5f)
            )
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0A0C))
            .border(
                1.dp,
                if (isSuccess) Color(0xFF00E676).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(12.dp)
            )
    ) {
        if (!isShowingBack) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val cx = w * 0.5f + (tiltX * w * 0.35f)
                val cy = h * 0.5f + (tiltY * h * 0.35f)

                // Фоновое свечение в зависимости от статуса успеха
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isSuccess) Color(0xFF00E676).copy(alpha = 0.7f) else dynamicPrimary.copy(alpha = 0.85f),
                            if (isSuccess) Color(0xFF00B0FF).copy(alpha = 0.3f) else dynamicTertiary.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = w * 0.85f
                    )
                )

                // Радужный голографический перелив (Holo-Foil Shimmer) по гироскопу
                val holoOffset = (tiltX * 0.6f + tiltY * 0.4f).coerceIn(-1f, 1f)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x1500E5FF),
                            Color(0x2EAF52DE),
                            Color(0x35FFD700),
                            Color(0x2500E676),
                            Color(0x203D5AFE),
                            Color.Transparent
                        ),
                        start = Offset(w * (0.25f + holoOffset * 0.5f), 0f),
                        end = Offset(w * (0.85f + holoOffset * 0.5f), h)
                    )
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "МГКАП",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = 4.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141418))
                    .padding(22.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        modifier = Modifier.weight(0.3f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("STUDENT", color = Color(0xFF888890), fontSize = 9.sp, letterSpacing = 1.sp)
                        Text("GROUP", color = Color(0xFF888890), fontSize = 9.sp, letterSpacing = 1.sp)
                        Text("STATUS", color = Color(0xFF888890), fontSize = 9.sp, letterSpacing = 1.sp)
                    }

                    Column(
                        modifier = Modifier.weight(0.7f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = cardName.uppercase(),
                            color = Color.White,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = groupCode,
                            color = Color.White,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (isSuccess) "NFC LINK ACTIVE" else "NFC SECURE TRANSFER",
                            color = if (isSuccess) Color(0xFF00E676) else Color.White,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                            fontWeight = if (isSuccess) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Тонкий горизонтальный разделитель
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF2A2A30))
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "МГКАП",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "DATA EXCHANGE SYSTEM",
                            color = Color(0xFF888890),
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Три наклонные линии (Иконка)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.width(4.dp).height(12.dp).background(if (isSuccess) Color(0xFF00E676) else Color.White).graphicsLayer { rotationZ = 15f })
                        Box(modifier = Modifier.width(4.dp).height(12.dp).background(if (isSuccess) Color(0xFF00E676) else Color.White).graphicsLayer { rotationZ = 15f })
                        Box(modifier = Modifier.width(4.dp).height(12.dp).background(if (isSuccess) Color(0xFF00E676) else Color.White).graphicsLayer { rotationZ = 15f })
                    }
                }
            }
        }
    }
}

@Composable
fun TabButtonHW(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryPurple else Color.Transparent,
        animationSpec = tween(220),
        label = "tab_bg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else TextGray,
        animationSpec = tween(220),
        label = "tab_text"
    )

    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = textColor
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    formatter: DateTimeFormatter,
    modifier: Modifier = Modifier
) {
    val text = when (date) {
        LocalDate.now() -> "Сегодня"
        LocalDate.now().plusDays(1) -> "Завтра"
        LocalDate.now().minusDays(1) -> "Вчера"
        else -> date.format(formatter)
    }

    Text(
        text = text,
        color = PrimaryPurple,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@Composable
private fun EmptyState(
    isActiveTab: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(SurfaceDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isActiveTab) Icons.Default.Assignment else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = PrimaryPurple.copy(alpha = 0.7f),
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = if (isActiveTab) "Нет активных заданий" else "Пока нет выполненных",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isActiveTab)
                "Можно немного отдохнуть"
            else
                "Завершай задания — они появятся здесь",
            color = TextGray,
            fontSize = 14.sp
        )
    }
}

@Composable
fun HomeworkCard(
    homework: HomeworkItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val isCompleted = homework.isCompleted

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = if (isCompleted)
                        Icons.Rounded.CheckCircle
                    else
                        Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (isCompleted) "Вернуть в активные" else "Отметить выполненным",
                    tint = if (isCompleted) PrimaryPurple else TextGray,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = homework.subject,
                    color = if (isCompleted) TextGray else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null
                )

                if (homework.task.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = homework.task,
                        color = TextGray,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (isCompleted) TextDecoration.LineThrough else null
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Удалить",
                    tint = TextGray.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}