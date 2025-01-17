package application

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Snackbar
import androidx.compose.material.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import capturing.CapturingService
import com.github.numq.stt.STT
import com.github.numq.vad.VoiceActivityDetection
import device.DeviceService
import interaction.InteractionScreen

internal const val SAMPLE_RATE = 16_000
internal const val WINDOW_SIZE_SAMPLES = 512

fun main(args: Array<String>) {
    val modelPath = args.first()

    val pathToBinaries = Thread.currentThread().contextClassLoader.getResource("bin")?.file

    checkNotNull(pathToBinaries) { "Binaries not found" }

    VoiceActivityDetection.load(
        libfvad = "$pathToBinaries\\libfvad.dll",
        libvad = "$pathToBinaries\\libvad.dll"
    ).getOrThrow()

    STT.load(
        ggmlbase = "$pathToBinaries\\ggml-base.dll",
        ggmlcpu = "$pathToBinaries\\ggml-cpu.dll",
        ggmlcuda = "$pathToBinaries\\ggml-cuda.dll",
        ggml = "$pathToBinaries\\ggml.dll",
        whisper = "$pathToBinaries\\whisper.dll",
        libstt = "$pathToBinaries\\libstt.dll"
    ).getOrThrow()

    singleWindowApplication(state = WindowState(width = 512.dp, height = 512.dp)) {
        val deviceService = remember { DeviceService.create().getOrThrow() }

        val capturingService = remember { CapturingService.create().getOrThrow() }

        val vad = remember { VoiceActivityDetection.create().getOrThrow() }

        val stt = remember { STT.create(modelPath = modelPath).getOrThrow() }

        val (throwable, setThrowable) = remember { mutableStateOf<Throwable?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                vad.close()
                stt.close()
            }
        }

        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                InteractionScreen(
                    deviceService = deviceService,
                    capturingService = capturingService,
                    vad = vad,
                    stt = stt,
                    handleThrowable = setThrowable
                )
                throwable?.let { t ->
                    Snackbar(
                        modifier = Modifier.padding(8.dp),
                        action = {
                            Button(onClick = { setThrowable(null) }) { Text("Dismiss") }
                        }
                    ) { Text(t.localizedMessage) }
                }
            }
        }
    }
}