package com.example.scenicobd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private enum class ConnectionStatus(
        val label: String,
        val colorHex: String
    ) {
        OFFLINE("НЕ В СЕТИ", "#FF4FD8"),
        CONNECTING("ПОДКЛЮЧЕНИЕ", "#FFB703"),
        ONLINE("В СЕТИ", "#72EFDD"),
        LIVE_TELEMETRY("ЖИВАЯ ТЕЛЕМЕТРИЯ", "#00F5D4")
    }

    private lateinit var viewStatusAccent: View
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvHud: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStartLive: Button
    private lateinit var btnStopLive: Button

    private var btSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var livePollingJob: Job? = null
    private var isConnected: Boolean = false
    private var isDemoMode: Boolean = false

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val requestBtPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                log("[БЛЮТУЗ] Разрешения получены.")
            } else {
                log("[ВНИМАНИЕ] Часть разрешений Bluetooth не выдана.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewStatusAccent = findViewById(R.id.viewStatusAccent)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvHud = findViewById(R.id.tvHud)
        tvLog = findViewById(R.id.tvLog)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnReadDtc = findViewById<Button>(R.id.btnReadDtc)
        val btnClearDtc = findViewById<Button>(R.id.btnClearDtc)
        btnStartLive = findViewById(R.id.btnStartLive)
        btnStopLive = findViewById(R.id.btnStopLive)
        val btnSaveLog = findViewById<Button>(R.id.btnSaveLog)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)
        val btnDemoMode = findViewById<Button>(R.id.btnDemoMode)

        updateStatusSync(ConnectionStatus.OFFLINE)

        btnConnect.setOnClickListener {
            if (hasBluetoothPermissions()) {
                connectToElm()
            } else {
                requestBluetoothPermissions()
            }
        }
        btnReadDtc.setOnClickListener {
            stopLivePollingAsync()
            readErrors()
        }
        btnClearDtc.setOnClickListener {
            stopLivePollingAsync()
            clearErrors()
        }
        btnStartLive.setOnClickListener { startLivePolling() }
        btnStopLive.setOnClickListener { stopLivePollingAsync() }
        btnSaveLog.setOnClickListener { saveLogToDownloads() }
        btnClearLog.setOnClickListener { clearLog() }
        btnDemoMode.setOnClickListener { runDemoTest() }

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
        }
    }

    private fun runDemoTest() {
        lifecycleScope.launch(Dispatchers.IO) {
            stopLivePollingInternal()
            isDemoMode = true
            isConnected = false
            updateStatus(ConnectionStatus.LIVE_TELEMETRY)
            setPollingButtonsState(isPolling = true)

            log("\n[ДЕМО] Запуск теста без ELM327 (Рено Сценик II 1.6 K4M)...")
            val demoRawErrors = "43 02 03 00 01 35"
            log("[ОТВЕТ ЭБУ] $demoRawErrors")
            val parsedErrors = parseDtcResponse(demoRawErrors)
            log("[НАЙДЕНЫ ОШИБКИ] Всего кодов: ${parsedErrors.size}")
            parsedErrors.forEach { code ->
                log("• $code — ${DtcDictionary.getDescription(code)}")
            }

            log("[ТЕЛЕМЕТРИЯ ВКЛ] Демо-автоопрос каждые 1.2 сек (нажмите 5 для стопа)...")
            try {
                while (isActive) {
                    val voltage = String.format(Locale.US, "%.1fВ", Random.nextDouble(13.8, 14.5))
                    val rpm = Random.nextInt(740, 920)
                    val coolant = Random.nextInt(82, 94)

                    withContext(Dispatchers.Main) {
                        tvHud.text = "АКБ: $voltage  |  ОБОРОТЫ: ${rpm.toString().padStart(4, ' ')}  |  ОЖ: ${coolant}°C"
                    }
                    log("[ДЕМО] АКБ: $voltage | Обороты: $rpm об/мин | ОЖ: $coolant °C")
                    delay(1200L)
                }
            } finally {
                setPollingButtonsState(isPolling = false)
                isDemoMode = false
                updateStatus(ConnectionStatus.OFFLINE)
                log("[ТЕЛЕМЕТРИЯ ВЫКЛ] Демо-тест завершен.")
            }
        }
    }

    private fun clearLog() {
        tvLog.text = "[ЛОГ ОЧИЩЕН] Терминал готов к новым данным.\n"
        Toast.makeText(this, "Лог очищен", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusSync(status: ConnectionStatus) {
        val color = Color.parseColor(status.colorHex)
        tvStatusBadge.text = status.label
        tvStatusBadge.setTextColor(color)
        viewStatusAccent.setBackgroundColor(color)

        tvStatusBadge.alpha = 0.35f
        tvStatusBadge.animate().alpha(1f).setDuration(260L).start()
    }

    private suspend fun updateStatus(status: ConnectionStatus) {
        withContext(Dispatchers.Main) {
            updateStatusSync(status)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            return connectGranted && scanGranted
        }
        return true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBtPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToElm() {
        lifecycleScope.launch(Dispatchers.IO) {
            stopLivePollingInternal()
            isDemoMode = false
            isConnected = false
            updateStatus(ConnectionStatus.CONNECTING)
            try {
                log("[БЛЮТУЗ] Поиск сопряженного адаптера ELM327...")
                val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                val btAdapter: BluetoothAdapter? = btManager.adapter

                if (btAdapter == null) {
                    log("[ОШИБКА] Bluetooth не поддерживается устройством.")
                    updateStatus(ConnectionStatus.OFFLINE)
                    return@launch
                }
                if (!btAdapter.isEnabled) {
                    log("[ОШИБКА] Включите Bluetooth в настройках телефона.")
                    updateStatus(ConnectionStatus.OFFLINE)
                    return@launch
                }

                val device = btAdapter.bondedDevices?.find {
                    it.name.contains("OBD", ignoreCase = true) ||
                        it.name.contains("ELM", ignoreCase = true) ||
                        it.name.contains("V-LINK", ignoreCase = true)
                }

                if (device == null) {
                    log("[ОШИБКА] Адаптер ELM327 не найден среди сопряженных устройств.")
                    updateStatus(ConnectionStatus.OFFLINE)
                    return@launch
                }

                log("[БЛЮТУЗ] Подключение к ${device.name} (${device.address})...")
                btSocket?.close()
                btSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                btSocket?.connect()

                inputStream = btSocket?.inputStream
                outputStream = btSocket?.outputStream
                log("[ОК] Связь установлена. Инициализация адаптера...")

                sendCommand("ATZ")
                sendCommand("ATE0")
                sendCommand("ATL0")
                sendCommand("ATSP0")

                isConnected = true
                updateStatus(ConnectionStatus.ONLINE)
                log("[ГОТОВО] Подключено к Рено Сценик II (1.6 K4M).")
            } catch (e: Exception) {
                isConnected = false
                updateStatus(ConnectionStatus.OFFLINE)
                log("[ОШИБКА] Сбой подключения: ${e.message}")
            }
        }
    }

    private fun startLivePolling() {
        if (!isConnected || outputStream == null || inputStream == null) {
            log("[ВНИМАНИЕ] Сначала подключитесь к ELM327 (кнопка 1) или нажмите кнопку 8 для теста.")
            return
        }
        if (livePollingJob?.isActive == true) {
            log("[ИНФО] Автообновление уже запущено.")
            return
        }

        setPollingButtonsState(isPolling = true)
        lifecycleScope.launch(Dispatchers.Main) {
            updateStatusSync(ConnectionStatus.LIVE_TELEMETRY)
        }
        log("\n[ТЕЛЕМЕТРИЯ ВКЛ] Запуск автообновления параметров...")

        livePollingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    fetchLiveSnapshot()
                    delay(1200L)
                }
            } finally {
                setPollingButtonsState(isPolling = false)
                updateStatus(if (isConnected) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
                log("[ТЕЛЕМЕТРИЯ ВЫКЛ] Автообновление остановлено.")
            }
        }
    }

    private fun stopLivePollingAsync() {
        lifecycleScope.launch {
            stopLivePollingInternal()
        }
    }

    private suspend fun stopLivePollingInternal() {
        val job = livePollingJob
        livePollingJob = null
        if (job != null && job.isActive) {
            job.cancelAndJoin()
        }
        setPollingButtonsState(isPolling = false)
        isDemoMode = false
        updateStatus(if (isConnected) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
    }

    private suspend fun setPollingButtonsState(isPolling: Boolean) {
        withContext(Dispatchers.Main) {
            btnStartLive.isEnabled = !isPolling
            btnStopLive.isEnabled = isPolling
        }
    }

    private suspend fun fetchLiveSnapshot() {
        val voltageRaw = sendCommand("ATRV")

        val rpmRaw = sendCommand("010C")
        val rpmBytes = extractPidBytes(rpmRaw, "410C")
        val rpmValue = if (rpmBytes != null && rpmBytes.size >= 2) {
            ((rpmBytes[0] * 256) + rpmBytes[1]) / 4
        } else {
            null
        }
        val rpmText = rpmValue?.let { "$it об/мин" } ?: "н/д ($rpmRaw)"

        val tempRaw = sendCommand("0105")
        val tempBytes = extractPidBytes(tempRaw, "4105")
        val tempValue = if (tempBytes != null && tempBytes.isNotEmpty()) {
            tempBytes[0] - 40
        } else {
            null
        }
        val tempText = tempValue?.let { "$it °C" } ?: "н/д ($tempRaw)"

        val hudRpm = rpmValue?.toString()?.padStart(4, ' ') ?: "----"
        val hudTemp = tempValue?.let { "$it°C" } ?: "---°C"
        withContext(Dispatchers.Main) {
            tvHud.text = "АКБ: $voltageRaw  |  ОБОРОТЫ: $hudRpm  |  ОЖ: $hudTemp"
        }

        log("АКБ: $voltageRaw | Обороты: $rpmText | ОЖ: $tempText")
    }

    private fun readErrors() {
        lifecycleScope.launch(Dispatchers.IO) {
            log("\n[ОШИБКИ] Запрос кодов неисправностей (Режим 03)...")
            val rawResponse = sendCommand("03")
            log("[ОТВЕТ ЭБУ] $rawResponse")

            val parsedErrors = parseDtcResponse(rawResponse)
            if (parsedErrors.isEmpty()) {
                log("[ОК] Активных ошибок не обнаружено.")
            } else {
                log("[НАЙДЕНЫ ОШИБКИ] Всего кодов: ${parsedErrors.size}")
                parsedErrors.forEach { code ->
                    val desc = DtcDictionary.getDescription(code)
                    log("• $code — $desc")
                }
            }
        }
    }

    private fun clearErrors() {
        lifecycleScope.launch(Dispatchers.IO) {
            log("\n[СБРОС] Очистка памяти ошибок (Режим 04)...")
            val response = sendCommand("04")
            log("[ОТВЕТ ЭБУ] $response")
            if (response.replace(" ", "").contains("44")) {
                log("[ОК] Ошибки успешно сброшены.")
            }
        }
    }

    private fun saveLogToDownloads() {
        val logText = tvLog.text.toString()
        if (logText.isBlank()) {
            Toast.makeText(this, "Лог пуст, сохранять нечего", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val fileName = "scenic_obd_$timestamp.txt"
                val header = "ДИАГНОСТИКА // РЕНО СЦЕНИК II (1.6 K4M)\n" +
                    "СТАТУС: ${tvStatusBadge.text} | ПАРАМЕТРЫ: ${tvHud.text}\nДата: $timestamp\n" +
                    "==================================================\n\n"
                val fullContent = header + logText

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Не удалось создать файл в хранилище")

                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(fullContent.toByteArray(Charsets.UTF_8))
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val file = File(downloadsDir, fileName)
                    file.writeText(fullContent, Charsets.UTF_8)
                }

                log("[СОХРАНЕНО] Отчет записан в Downloads/$fileName")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Сохранено в Downloads/$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                log("[ОШИБКА] Не удалось сохранить файл: ${e.message}")
            }
        }
    }

    private fun extractPidBytes(raw: String, header: String): List<Int>? {
        val clean = raw.replace(" ", "").uppercase()
        val idx = clean.indexOf(header)
        if (idx == -1) return null

        val payload = clean.substring(idx + header.length)
        if (payload.length < 2) return null

        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 2 <= payload.length) {
            val value = payload.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(value)
            i += 2
        }
        return bytes
    }

    private suspend fun sendCommand(cmd: String): String {
        return try {
            val out = outputStream ?: return "Нет подключения"
            val inp = inputStream ?: return "Нет подключения"

            while (inp.available() > 0) inp.read()

            out.write((cmd + "\r").toByteArray())
            out.flush()

            val responseBuilder = StringBuilder()
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 3000) {
                if (inp.available() > 0) {
                    val c = inp.read().toChar()
                    if (c == '>') break
                    responseBuilder.append(c)
                } else {
                    delay(50)
                }
            }
            responseBuilder.toString()
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        } catch (e: Exception) {
            "Ошибка обмена: ${e.message}"
        }
    }

    private fun parseDtcResponse(raw: String): List<String> {
        val codes = mutableListOf<String>()
        val cleanHex = raw.replace(" ", "").uppercase()

        val startIndex = cleanHex.indexOf("43")
        if (startIndex == -1) return codes

        val data = cleanHex.substring(startIndex + 2)
        val prefixes = arrayOf('P', 'C', 'B', 'U')

        var i = 0
        while (i + 4 <= data.length) {
            val hexPair = data.substring(i, i + 4)
            i += 4
            if (hexPair == "0000") continue

            val firstDigit = hexPair[0].digitToIntOrNull(16) ?: continue
            val prefix = prefixes[firstDigit shr 2]
            val secondChar = (firstDigit and 0x03).toString()
            val rest = hexPair.substring(1)

            codes.add("$prefix$secondChar$rest")
        }
        return codes
    }

    private suspend fun log(message: String) {
        withContext(Dispatchers.Main) {
            tvLog.append("$message\n")
        }
    }

    override fun onDestroy() {
        livePollingJob?.cancel()
        isConnected = false
        isDemoMode = false
        super.onDestroy()
        try {
            inputStream?.close()
            outputStream?.close()
            btSocket?.close()
        } catch (_: Exception) {
        }
    }
}
