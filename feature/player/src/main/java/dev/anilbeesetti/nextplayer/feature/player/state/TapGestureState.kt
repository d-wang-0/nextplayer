package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.model.DoubleTapGesture
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.feature.player.extensions.setIsScrubbingModeEnabled
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun rememberTapGestureState(
    player: Player,
    doubleTapGesture: DoubleTapGesture,
    seekIncrementMillis: Long,
    useLongPressGesture: Boolean,
    longPressSpeed: Float,
    useSwipeSpeedGesture: Boolean,
    swipeSpeedMaxForward: Float,
    swipeSpeedMaxRewind: Float,
    swipeSpeedSensitivity: Float,
): TapGestureState {
    val coroutineScope = rememberCoroutineScope()
    val tapGestureState = remember {
        TapGestureState(
            player = player,
            doubleTapGesture = doubleTapGesture,
            seekIncrementMillis = seekIncrementMillis,
            useLongPressGesture = useLongPressGesture,
            longPressSpeed = longPressSpeed,
            useSwipeSpeedGesture = useSwipeSpeedGesture,
            swipeSpeedMaxForward = swipeSpeedMaxForward,
            swipeSpeedMaxRewind = swipeSpeedMaxRewind,
            swipeSpeedSensitivity = swipeSpeedSensitivity,
            coroutineScope = coroutineScope,
        )
    }
    return tapGestureState
}

@Stable
class TapGestureState(
    private val player: Player,
    private val seekIncrementMillis: Long,
    private val useLongPressGesture: Boolean = true,
    private val coroutineScope: CoroutineScope,
    val longPressSpeed: Float = 2.0f,
    private val useSwipeSpeedGesture: Boolean = false,
    private val swipeSpeedMaxForward: Float = PlayerPreferences.DEFAULT_SWIPE_SPEED_MAX_FORWARD,
    private val swipeSpeedMaxRewind: Float = PlayerPreferences.DEFAULT_SWIPE_SPEED_MAX_REWIND,
    private val swipeSpeedSensitivity: Float = PlayerPreferences.DEFAULT_SWIPE_SPEED_SENSITIVITY,
    val doubleTapGesture: DoubleTapGesture,
    val interactionSource: MutableInteractionSource = MutableInteractionSource(),
) {
    var seekMillis by mutableLongStateOf(value = 0L)
    var isLongPressGestureInAction by mutableStateOf(value = false)

    /** Speed shown while swiping: positive is the playback speed, negative is the rewind speed, 0 is neutral. */
    var swipeSpeed by mutableFloatStateOf(value = 0f)
        private set

    val isSwipeSpeedGestureInAction: Boolean
        get() = isLongPressGestureInAction && useSwipeSpeedGesture

    private var resetJob: Job? = null
    private var rewindJob: Job? = null
    private var currentSpeed: Float = player.playbackParameters.speed
    private var appliedSwipeSpeed = currentSpeed
    private var swipeStartX = 0f
    private var swipeRampWidth = 1f

    fun handleDoubleTap(offset: Offset, size: IntSize) {
        if (!player.isCurrentMediaItemSeekable) return

        val action = when (doubleTapGesture) {
            DoubleTapGesture.FAST_FORWARD_AND_REWIND -> {
                val viewCenterX = size.width / 2
                when {
                    offset.x < viewCenterX -> DoubleTapAction.SEEK_BACKWARD
                    else -> DoubleTapAction.SEEK_FORWARD
                }
            }

            DoubleTapGesture.BOTH -> {
                val eventPositionX = offset.x / size.width
                when {
                    eventPositionX < 0.35 -> DoubleTapAction.SEEK_BACKWARD
                    eventPositionX > 0.65 -> DoubleTapAction.SEEK_FORWARD
                    else -> DoubleTapAction.PLAY_PAUSE
                }
            }

            DoubleTapGesture.PLAY_PAUSE -> DoubleTapAction.PLAY_PAUSE

            DoubleTapGesture.NONE -> return
        }

        when (action) {
            DoubleTapAction.SEEK_BACKWARD -> {
                player.seekTo(player.currentPosition - seekIncrementMillis)
                if (seekMillis > 0L) {
                    seekMillis = 0L
                }
                seekMillis -= seekIncrementMillis
                interactionSource.tryEmit(PressInteraction.Press(pressPosition = offset))
            }

            DoubleTapAction.SEEK_FORWARD -> {
                player.seekTo(player.currentPosition + seekIncrementMillis)
                if (seekMillis < 0L) {
                    seekMillis = 0L
                }
                seekMillis += seekIncrementMillis
                interactionSource.tryEmit(PressInteraction.Press(pressPosition = offset))
            }

            DoubleTapAction.PLAY_PAUSE -> {
                when (player.isPlaying) {
                    true -> player.pause()
                    false -> player.play()
                }
            }
        }
        resetDoubleTapSeekState()
    }

    fun handleLongPress(offset: Offset) {
        if (!useLongPressGesture && !useSwipeSpeedGesture) return
        if (!player.isPlaying) return

        isLongPressGestureInAction = true
        currentSpeed = player.playbackParameters.speed
        appliedSwipeSpeed = currentSpeed
        if (!useSwipeSpeedGesture) player.setPlaybackSpeed(longPressSpeed)
    }

    fun handleOnLongPressRelease() {
        if (isLongPressGestureInAction) {
            stopRewind()
            swipeSpeed = 0f
            isLongPressGestureInAction = false
            player.setPlaybackSpeed(currentSpeed)
        }
    }

    fun onSwipeSpeedDragStart(offset: Offset, size: IntSize) {
        swipeStartX = offset.x
        swipeRampWidth = size.width * SWIPE_SPEED_RAMP_BASE_FRACTION / swipeSpeedSensitivity
    }

    fun onSwipeSpeedDrag(change: PointerInputChange) {
        val rawDelta = (change.position.x - swipeStartX) / swipeRampWidth
        val delta = when {
            rawDelta > SWIPE_SPEED_DEADZONE -> (rawDelta - SWIPE_SPEED_DEADZONE) /
                (1f - SWIPE_SPEED_DEADZONE)

            rawDelta < -SWIPE_SPEED_DEADZONE -> (rawDelta + SWIPE_SPEED_DEADZONE) /
                (1f - SWIPE_SPEED_DEADZONE)

            else -> 0f
        }.coerceIn(
            -1f,
            1f,
        )

        if (delta < 0f && player.isCurrentMediaItemSeekable) {
            startRewind(speed = (-delta).pow(SWIPE_SPEED_CURVE) * swipeSpeedMaxRewind)
        } else {
            stopRewind()
            val speed =
                1f + delta.coerceAtLeast(0f).pow(SWIPE_SPEED_CURVE) * (swipeSpeedMaxForward - 1f)
            applySwipeSpeed(speed)
            swipeSpeed = if (delta > 0f) speed else 0f
        }
    }

    /**
     * ExoPlayer cannot play backwards, so rewinding plays at 1x with periodic jumps back. Scrubbing
     * mode keeps the repeated seeks cheap and mutes the audio while they happen.
     */
    private fun startRewind(speed: Float) {
        swipeSpeed = -speed
        if (rewindJob?.isActive == true) return

        applySwipeSpeed(1f)
        player.setIsScrubbingModeEnabled(enabled = true)
        rewindJob = coroutineScope.launch {
            while (true) {
                delay(REWIND_JUMP_INTERVAL_MILLIS)
                // Net backwards movement of the rewind speed, on top of the 1x playback that continues
                val jumpMillis = ((-swipeSpeed + 1f) * REWIND_JUMP_INTERVAL_MILLIS).toLong()
                player.seekTo((player.currentPosition - jumpMillis).coerceAtLeast(0L))
            }
        }
    }

    private fun stopRewind() {
        if (rewindJob == null) return
        rewindJob?.cancel()
        rewindJob = null
        player.setIsScrubbingModeEnabled(enabled = false)
    }

    private fun applySwipeSpeed(speed: Float) {
        if (speed == appliedSwipeSpeed) return
        appliedSwipeSpeed = speed
        player.setPlaybackSpeed(speed)
    }

    private fun resetDoubleTapSeekState() {
        resetJob?.cancel()
        resetJob = coroutineScope.launch {
            delay(750.milliseconds)
            seekMillis = 0L
        }
    }
}

/** Fraction of the screen width for a full swipe at a sensitivity of 1, it grows as sensitivity drops. */
private const val SWIPE_SPEED_RAMP_BASE_FRACTION = 0.125f
private const val SWIPE_SPEED_DEADZONE = 0.05f
private const val SWIPE_SPEED_CURVE = 1.5f
private const val REWIND_JUMP_INTERVAL_MILLIS = 300L

enum class DoubleTapAction {
    SEEK_BACKWARD,
    SEEK_FORWARD,
    PLAY_PAUSE,
}
