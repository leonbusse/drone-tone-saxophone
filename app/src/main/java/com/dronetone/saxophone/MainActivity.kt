package com.dronetone.saxophone

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackThread: Thread? = null
    private var currentPhase = 0.0
    private var currentFrequency = 0.0
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var detectedFrequencyState = mutableStateOf(0.0)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 100
        private const val SAMPLE_RATE = 44100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }
        
        setContent {
            DroneToneSaxophoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val detectedFreq by detectedFrequencyState
                    DroneToneScreen(
                        onPlayStopClick = { frequency, shouldPlay ->
                            if (shouldPlay) {
                                startTone(frequency)
                            } else {
                                stopTone()
                            }
                        },
                        detectedFrequency = detectedFreq
                    )
                }
            }
        }
        
        // Start tuner automatically
        startTuner()
    }

    private fun startTone(frequency: Double) {
        stopTone()
        
        val sampleRate = 44100
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // Use a larger buffer to reduce clicks and improve continuity
        val bufferSize = minBufferSize * 4

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        // Reset phase only if frequency changed
        if (currentFrequency != frequency) {
            currentPhase = 0.0
            currentFrequency = frequency
        }

        val twoPi = 2.0 * Math.PI
        val increment = (twoPi * frequency) / sampleRate
        val samplesPerWrite = bufferSize / 2 // 2 bytes per sample (16-bit)

        // Play the tone in a loop with continuous phase
        isPlaying = true
        playbackThread = Thread {
            val samples = ShortArray(samplesPerWrite)
            while (isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                // Generate samples continuously, maintaining phase across writes
                for (i in samples.indices) {
                    samples[i] = (sin(currentPhase) * Short.MAX_VALUE).toInt().toShort()
                    currentPhase += increment
                    // Keep phase in [0, 2π) range to prevent overflow
                    if (currentPhase >= twoPi) {
                        currentPhase -= twoPi
                    }
                }
                
                val written = audioTrack?.write(samples, 0, samples.size) ?: 0
                if (written < 0) {
                    break
                }
            }
        }.apply { start() }
    }

    private fun stopTone() {
        isPlaying = false
        playbackThread?.join(100) // Wait for thread to finish, with timeout
        playbackThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        // Don't reset phase here - keep it for smooth transitions when changing frequency
    }

    private fun startTuner() {
        stopTuner()
        
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        
        audioRecord?.startRecording()
        isRecording = true
        
        recordingThread = Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val frequency = detectFrequency(buffer, SAMPLE_RATE)
                    if (frequency > 0) {
                        mainHandler.post {
                            detectedFrequencyState.value = frequency
                        }
                    }
                }
            }
        }.apply { start() }
    }
    
    private fun stopTuner() {
        isRecording = false
        recordingThread?.join(100)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        detectedFrequencyState.value = 0.0
    }
    
    private fun detectFrequency(buffer: ShortArray, sampleRate: Int): Double {
        // Use autocorrelation to find fundamental frequency
        val minPeriod = sampleRate / 2000 // Max frequency ~2000 Hz
        val maxPeriod = sampleRate / 80   // Min frequency ~80 Hz
        
        var maxCorrelation = 0.0
        var bestPeriod = 0
        
        for (period in minPeriod until minOf(maxPeriod, buffer.size / 2)) {
            var correlation = 0.0
            for (i in 0 until buffer.size - period) {
                correlation += (buffer[i].toInt() * buffer[i + period].toInt())
            }
            correlation /= (buffer.size - period)
            
            if (correlation > maxCorrelation) {
                maxCorrelation = correlation
                bestPeriod = period
            }
        }
        
        return if (bestPeriod > 0 && maxCorrelation > 1000000) {
            sampleRate.toDouble() / bestPeriod
        } else {
            0.0
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Start tuner if permission is granted and not already recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED && !isRecording) {
            startTuner()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTone()
        stopTuner()
    }
}

@Composable
fun DroneToneScreen(
    onPlayStopClick: (Double, Boolean) -> Unit,
    detectedFrequency: Double
) {
    var isPlaying by remember { mutableStateOf(false) }
    var showPitchSelector by remember { mutableStateOf(false) }
    
    // Chromatic note selection: 0 = C, 1 = C#, 2 = D, ..., 11 = B
    var chromaticNote by remember { mutableStateOf(9f) } // A = 9
    var octave by remember { mutableStateOf(4) } // Start at octave 4
    
    val chromaticNoteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    
    // Calculate frequency from chromatic note and octave
    // A4 (note 9, octave 4) = 440 Hz
    // Each semitone = 2^(1/12) multiplier
    val selectedFrequency = remember(chromaticNote, octave) {
        val semitonesFromA4 = (octave - 4) * 12 + (chromaticNote.toInt() - 9)
        440.0 * 2.0.pow(semitonesFromA4 / 12.0)
    }
    
    val currentNoteName = remember(chromaticNote, octave) {
        "${chromaticNoteNames[chromaticNote.toInt()]}$octave"
    }
    
    // Tuner calculations
    val (tunerNoteName, tunerCents) = remember(detectedFrequency) {
        if (detectedFrequency > 0) {
            calculateClosestNote(detectedFrequency)
        } else {
            Pair("--", 0.0)
        }
    }
    
    // Update audio when frequency changes while playing
    LaunchedEffect(selectedFrequency, isPlaying) {
        if (isPlaying) {
            onPlayStopClick(selectedFrequency, false)
            onPlayStopClick(selectedFrequency, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Drone Tone Saxophone",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        // Tuner display (always visible)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tuner",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = tunerNoteName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (detectedFrequency > 0) {
                    Text(
                        text = "${String.format("%.2f", detectedFrequency)} Hz",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "${if (tunerCents >= 0) "+" else ""}${String.format("%.1f", tunerCents)} cents",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        text = "No signal detected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Drone tone display (clickable to show/hide pitch selector)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPitchSelector = !showPitchSelector },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (showPitchSelector) 8.dp else 4.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Drone Tone",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = currentNoteName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "${String.format("%.2f", selectedFrequency)} Hz",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (showPitchSelector) "Tap to hide selector" else "Tap to select pitch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Pitch selector (shown when button is pressed)
        if (showPitchSelector) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Chromatic note slider
                    Text(
                        text = "Note: ${chromaticNoteNames[chromaticNote.toInt()]}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = chromaticNote,
                        onValueChange = { chromaticNote = it },
                        valueRange = 0f..11f,
                        steps = 10, // 12 positions (0-11)
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Octave selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Octave:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(
                            onClick = {
                                if (octave > 0) octave--
                            },
                            enabled = octave > 0
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Lower Octave")
                        }
                        Text(
                            text = "$octave",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        IconButton(
                            onClick = {
                                if (octave < 8) octave++
                            },
                            enabled = octave < 8
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Higher Octave")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Play/Stop button for drone tone
        Button(
            onClick = {
                isPlaying = !isPlaying
                onPlayStopClick(selectedFrequency, isPlaying)
            },
            modifier = Modifier
                .width(200.dp)
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPlaying) 
                    MaterialTheme.colorScheme.error 
                else 
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isPlaying) "Stop" else "Play",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
fun DroneToneSaxophoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            secondary = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            tertiary = androidx.compose.ui.graphics.Color(0xFF3700B3)
        ),
        content = content
    )
}

// Calculate closest note and cents deviation from a frequency
fun calculateClosestNote(frequency: Double): Pair<String, Double> {
    val chromaticNoteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    
    // Calculate semitones from A4 (440 Hz)
    val semitonesFromA4 = 12 * log2(frequency / 440.0)
    
    // Round to nearest semitone
    val nearestSemitone = semitonesFromA4.roundToInt()
    
    // Calculate octave and note
    val octave = 4 + (nearestSemitone + 9) / 12
    val noteIndex = ((nearestSemitone + 9) % 12 + 12) % 12
    
    // Calculate cents deviation
    val exactSemitones = 12 * log2(frequency / 440.0)
    val cents = (exactSemitones - nearestSemitone) * 100.0
    
    val noteName = "${chromaticNoteNames[noteIndex]}$octave"
    
    return Pair(noteName, cents)
}

fun log2(x: Double): Double = ln(x) / ln(2.0)

