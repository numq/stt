package interaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import application.SAMPLE_RATE
import application.WINDOW_SIZE_SAMPLES
import capturing.CapturingService
import com.github.numq.stt.STT
import com.github.numq.vad.VoiceActivityDetection
import device.Device
import device.DeviceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem


@Composable
fun InteractionScreen(
    deviceService: DeviceService,
    capturingService: CapturingService,
    vad: VoiceActivityDetection,
    stt: STT,
    handleThrowable: (Throwable) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope { Dispatchers.IO }

    var deviceJob by remember { mutableStateOf<Job?>(null) }

    var capturingJob by remember { mutableStateOf<Job?>(null) }

    val capturingDevices = remember { mutableStateListOf<Device>() }

    var selectedCapturingDevice by remember { mutableStateOf<Device?>(null) }

    var refreshRequested by remember { mutableStateOf(true) }

    val recognizedChunks = remember { mutableStateListOf<String>() }

    val listState = rememberLazyListState()

    LaunchedEffect(recognizedChunks.size) {
        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
    }

    LaunchedEffect(refreshRequested) {
        deviceJob?.cancel()
        deviceJob = null

        if (refreshRequested) {
            deviceJob = coroutineScope.launch {
                deviceService.listCapturingDevices().onSuccess { devices ->
                    if (devices != capturingDevices) {
                        capturingDevices.clear()
                        capturingDevices.addAll(devices)

                        if (selectedCapturingDevice !in capturingDevices) {
                            selectedCapturingDevice = null
                        }
                    }
                }.onFailure(handleThrowable)

                refreshRequested = false
            }
        }
    }

    LaunchedEffect(selectedCapturingDevice) {
        capturingJob?.cancel()
        capturingJob = null

        when (val device = selectedCapturingDevice) {
            null -> return@LaunchedEffect

            else -> {
                capturingJob = coroutineScope.launch {
                    ByteArrayOutputStream().use { baos ->
                        capturingService.capture(device = device, chunkSize = WINDOW_SIZE_SAMPLES * 2).catch {
                            handleThrowable(it)
                        }.collect { pcmBytes ->
                            val isSpeechDetected = vad.detect(
                                pcmBytes = pcmBytes,
                                sampleRate = device.sampleRate,
                                channels = device.channels
                            ).getOrThrow()

                            if (isSpeechDetected) {
                                baos.write(
                                    AudioSystem.getAudioInputStream(
                                        AudioFormat(
                                            SAMPLE_RATE.toFloat(),
                                            16,
                                            1,
                                            true,
                                            false
                                        ),
                                        AudioInputStream(
                                            pcmBytes.inputStream(),
                                            with(device) {
                                                AudioFormat(
                                                    sampleRate.toFloat(),
                                                    sampleSizeInBits,
                                                    channels,
                                                    isSigned,
                                                    isBigEndian
                                                )
                                            },
                                            pcmBytes.size.toLong()
                                        )
                                    ).readBytes()
                                )
                            } else if (baos.size() >= SAMPLE_RATE * 2) {
                                stt.recognize(pcmBytes = baos.toByteArray()).onSuccess { recognizedText ->
                                    println(recognizedText)
                                    if (recognizedText.isNotBlank()) {
                                        recognizedChunks.add(recognizedText.lowercase())
                                    }
                                }.getOrThrow()

                                baos.reset()
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(modifier = Modifier.fillMaxWidth().weight(.5f)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Capturing devices", modifier = Modifier.padding(8.dp))
                        when (refreshRequested) {
                            true -> IconButton(onClick = {
                                refreshRequested = false
                            }) {
                                Icon(Icons.Default.Cancel, null)
                            }

                            false -> IconButton(onClick = {
                                refreshRequested = true
                            }) {
                                Icon(Icons.Default.Refresh, null)
                            }
                        }
                    }
                    when {
                        refreshRequested -> Box(
                            modifier = Modifier.weight(1f), contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }

                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(space = 8.dp, alignment = Alignment.Top),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            items(capturingDevices, key = (Device::name)) { device ->
                                Card(
                                    modifier = Modifier.fillMaxWidth()
                                        .alpha(alpha = if (device == selectedCapturingDevice) .5f else 1f).clickable {
                                            selectedCapturingDevice = device.takeIf { it != selectedCapturingDevice }
                                        }) {
                                    Text(device.name, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                state = listState
            ) {
                items(recognizedChunks) { recognizedChunk ->
                    Text(text = recognizedChunk)
                }
            }

            Button(onClick = {
                recognizedChunks.clear()
            }, enabled = recognizedChunks.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text("Clear", modifier = Modifier.padding(8.dp))
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            }
        }
    }
}