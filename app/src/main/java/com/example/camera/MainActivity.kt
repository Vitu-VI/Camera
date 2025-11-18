package com.example.camera

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.camera.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    // --- VARIÁVEIS DE ESTADO (Tarefa 15.1) ---
    private var isVideoDisplayActive = true
    private var isCsvDisplayActive = true

    // --- VARIÁVEIS DE CONTROLE (Tarefa 15.2) ---
    private var videoStreamJob: Job? = null
    private val vmIpAddress = "192.168.137.35" // IP da sua VM
    private val vmBaseUrl = "http://$vmIpAddress:5000"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Listeners dos Switches (Tarefa 15.1) ---
        setupDisplaySwitches()

        // --- Listeners dos Botões de Controle (Tarefa 15.2) ---
        setupControlButtons()

        // Inicia o loop de polling do CSV (ele não consome muitos dados)
        loadCsvData("$vmBaseUrl/csv_data")
    }

    private fun setupDisplaySwitches() {
        binding.switchVideoDisplay.setOnCheckedChangeListener { _, isChecked ->
            isVideoDisplayActive = isChecked
            if (!isChecked) {
                binding.videoFeedView.setImageResource(0)
                binding.videoFeedView.setBackgroundColor(0xFFCCCCCC.toInt())
            }
        }

        binding.switchCsvDisplay.setOnCheckedChangeListener { _, isChecked ->
            isCsvDisplayActive = isChecked
            if (!isChecked) {
                binding.csvDataView.text = "Exibição de CSV pausada."
            }
        }
    }

    private fun setupControlButtons() {
        // --- CONTROLE DE VÍDEO ---
        binding.btnStartVideo.setOnClickListener {
            // 1. Manda o Jetson LIGAR a câmera
            sendControlCommand("$vmBaseUrl/control/video/start", "Iniciando câmera no Jetson...")
            // 2. Inicia o loop no App para RECEBER o vídeo
            if (videoStreamJob == null || !videoStreamJob!!.isActive) {
                videoStreamJob = loadMjpegStream("$vmBaseUrl/video_feed")
            }
        }

        binding.btnStopVideo.setOnClickListener {
            // 1. Manda o Jetson DESLIGAR a câmera
            sendControlCommand("$vmBaseUrl/control/video/stop", "Parando câmera no Jetson...")
            // 2. Para o loop no App para economizar rede
            videoStreamJob?.cancel()
            binding.videoFeedView.setImageResource(0)
            binding.videoFeedView.setBackgroundColor(0xFFCCCCCC.toInt())
        }

        // --- CONTROLE DE CSV ---
        binding.btnStartCsv.setOnClickListener {
            // 1. Manda o Jetson INICIAR o envio de CSV
            sendControlCommand("$vmBaseUrl/control/csv/start", "Iniciando envio de CSV do Jetson...")
        }

        binding.btnStopCsv.setOnClickListener {
            // 1. Manda o Jetson PARAR o envio de CSV
            sendControlCommand("$vmBaseUrl/control/csv/stop", "Parando envio de CSV do Jetson...")
        }
    }

    /**
     * NOVA FUNÇÃO (Tarefa 15.2)
     * Envia um comando POST para a VM, que o retransmite para o Jetson.
     */
    private fun sendControlCommand(url: String, toastMessage: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Cria um corpo de requisição POST vazio
                val emptyBody = "".toRequestBody(null)
                val request = Request.Builder().url(url).post(emptyBody).build()
                val response = client.newCall(request).execute()

                val responseMsg = if (response.isSuccessful) {
                    "$toastMessage (Sucesso)"
                } else {
                    "$toastMessage (Erro: ${response.code})"
                }
                showToast(responseMsg)
                response.close()

            } catch (e: Exception) {
                Log.e("CameraApp", "Falha ao enviar comando para $url", e)
                showToast("Erro de conexão ao enviar comando. Verifique a VM.")
            }
        }
    }

    private fun loadMjpegStream(url: String): Job {
        return coroutineScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val dis = DataInputStream(BufferedInputStream(response.body!!.byteStream()))

                while (isActive) {
                    // (O código de parsing do M-JPEG permanece o mesmo)
                    while (dis.readUnsignedByte() != 0xFF || dis.readUnsignedByte() != 0xD8) { }
                    val frameBuffer = ByteArrayOutputStream()
                    frameBuffer.write(0xFF)
                    frameBuffer.write(0xD8)
                    var lastByte = 0
                    while (true) {
                        val currentByte = dis.readUnsignedByte()
                        frameBuffer.write(currentByte)
                        if (lastByte == 0xFF && currentByte == 0xD9) break
                        lastByte = currentByte
                    }

                    // LÓGICA DE EXIBIÇÃO (Tarefa 15.1)
                    if (isVideoDisplayActive) {
                        val imageBytes = frameBuffer.toByteArray()
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        withContext(Dispatchers.Main) {
                            binding.videoFeedView.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e("CameraApp", "Exceção no stream de vídeo M-JPEG.", e)
                    showToast("Stream de vídeo parado ou com erro.")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.videoFeedView.setImageResource(0)
                    binding.videoFeedView.setBackgroundColor(0xFFCCCCCC.toInt())
                }
            }
        }
    }

    private fun loadCsvData(url: String) {
        coroutineScope.launch {
            while (isActive) {
                var csvData = "Aguardando dados do Jetson..."
                try {
                    val jsonResponse = withContext(Dispatchers.IO) {
                        val request = Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) response.body?.string() else null
                    }
                    if (jsonResponse != null) {
                        // Pequena melhoria para formatar o JSON
                        val json = org.json.JSONObject(jsonResponse)
                        if (json.has("data")) {
                            csvData = "Aguardando (servidor VM ocioso)..."
                        } else {
                            csvData = json.toString(2) // Formata com 2 espaços
                        }
                    }
                } catch (e: Exception) {
                    csvData = "Erro ao conectar. Verifique a VM."
                }

                // LÓGICA DE EXIBIÇÃO (Tarefa 15.1)
                if (isCsvDisplayActive) {
                    binding.csvDataView.text = csvData
                }

                delay(1000)
            }
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}