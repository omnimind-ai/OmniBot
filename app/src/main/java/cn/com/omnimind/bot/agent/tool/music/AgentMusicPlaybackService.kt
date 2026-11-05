package cn.com.omnimind.bot.agent

import android.app.Notification
import android.app.Notification.Action
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity

class AgentMusicPlaybackService : Service() {

    companion object {
        private const val ACTION_PLAY = "cn.com.omnimind.bot.agent.music.PLAY"
        private const val ACTION_PAUSE = "cn.com.omnimind.bot.agent.music.PAUSE"
        private const val ACTION_RESUME = "cn.com.omnimind.bot.agent.music.RESUME"
        private const val ACTION_STOP = "cn.com.omnimind.bot.agent.music.STOP"
        private const val ACTION_SEEK = "cn.com.omnimind.bot.agent.music.SEEK"

        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_PLAYBACK_TARGET = "extra_playback_target"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_LOOP = "extra_loop"
        private const val EXTRA_POSITION_MS = "extra_position_ms"

        private const val CHANNEL_ID = "agent_music_playback_channel"
        private const val CHANNEL_NAME = "音频播放控制"
        private const val NOTIFICATION_ID = 2048107

        fun startPlayback(
            context: Context,
            source: String,
            playbackTarget: String,
            title: String,
            loop: Boolean
        ) {
            val intent = Intent(context, AgentMusicPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_PLAYBACK_TARGET, playbackTarget)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_LOOP, loop)
            }
            startServiceCompat(context, intent)
        }

        fun pause(context: Context) {
            startServiceCompat(
                context,
                Intent(context, AgentMusicPlaybackService::class.java).apply {
                    action = ACTION_PAUSE
                }
            )
        }

        fun resume(context: Context) {
            startServiceCompat(
                context,
                Intent(context, AgentMusicPlaybackService::class.java).apply {
                    action = ACTION_RESUME
                }
            )
        }

        fun stop(context: Context) {
            startServiceCompat(
                context,
                Intent(context, AgentMusicPlaybackService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun seekTo(context: Context, positionMs: Long) {
            startServiceCompat(
                context,
                Intent(context, AgentMusicPlaybackService::class.java).apply {
                    action = ACTION_SEEK
                    putExtra(EXTRA_POSITION_MS, positionMs)
                }
            )
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var currentSource: String? = null
    private var currentPlaybackTarget: String? = null
    private var currentTitle: String = "音频播放"
    private var currentLoop: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pauseInternal(abandonFocus = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseInternal(abandonFocus = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.25f, 0.25f)
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
            }
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateSnapshotFromPlayer()
            if (mediaPlayer?.isPlaying == true) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        initAudioFocusRequest()
        initMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                val target = intent.getStringExtra(EXTRA_PLAYBACK_TARGET).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val loop = intent.getBooleanExtra(EXTRA_LOOP, false)
                handleStartPlayback(source = source, playbackTarget = target, title = title, loop = loop)
            }

            ACTION_PAUSE -> pauseInternal(abandonFocus = false)
            ACTION_RESUME -> resumeInternal()
            ACTION_STOP -> stopInternal(targetState = AgentMusicPlaybackStateStore.STATE_STOPPED)
            ACTION_SEEK -> {
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                if (positionMs >= 0L) {
                    seekInternal(positionMs)
                }
            }

            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopProgressUpdates()
        releasePlayer()
        abandonAudioFocus()
        mediaSession?.let { session ->
            runCatching {
                session.isActive = false
                session.release()
            }
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun initAudioFocusRequest() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "OmnibotMusicPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    resumeInternal()
                }

                override fun onPause() {
                    pauseInternal(abandonFocus = false)
                }

                override fun onStop() {
                    stopInternal(targetState = AgentMusicPlaybackStateStore.STATE_STOPPED)
                }

                override fun onSeekTo(pos: Long) {
                    seekInternal(pos)
                }

                override fun onSkipToNext() {
                    dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                }

                override fun onSkipToPrevious() {
                    dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            })
        }
        updateMediaSessionPlaybackState(PlaybackState.STATE_NONE)
    }

    private fun handleStartPlayback(
        source: String,
        playbackTarget: String,
        title: String,
        loop: Boolean
    ) {
        if (playbackTarget.isBlank()) {
            updateSnapshot(
                state = AgentMusicPlaybackStateStore.STATE_ERROR,
                errorMessage = "播放地址不能为空"
            )
            stopInternal(
                targetState = AgentMusicPlaybackStateStore.STATE_ERROR,
                errorMessage = "播放地址不能为空"
            )
            return
        }

        currentSource = source
        currentPlaybackTarget = playbackTarget
        currentTitle = title.ifBlank { "音频播放" }
        currentLoop = loop

        stopProgressUpdates()
        releasePlayer()
        updateSnapshot(state = AgentMusicPlaybackStateStore.STATE_PREPARING)
        updateMediaSessionMetadata(durationMs = null)
        updateMediaSessionPlaybackState(PlaybackState.STATE_BUFFERING)
        mediaSession?.isActive = true
        if (!prepareForegroundPlayback()) {
            return
        }

        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isLooping = loop
            setOnPreparedListener { prepared ->
                runCatching {
                    prepared.start()
                    updateSnapshot(
                        state = AgentMusicPlaybackStateStore.STATE_PLAYING,
                        positionMs = prepared.safeCurrentPosition(),
                        durationMs = prepared.safeDuration()
                    )
                    updateMediaSessionMetadata(prepared.safeDuration())
                    updateMediaSessionPlaybackState(PlaybackState.STATE_PLAYING)
                    startProgressUpdates()
                    notificationManager.notify(NOTIFICATION_ID, buildNotification())
                }.onFailure { error ->
                    handlePlaybackError("开始播放失败：${error.message ?: "未知错误"}")
                }
            }
            setOnCompletionListener { completed ->
                stopProgressUpdates()
                updateSnapshot(
                    state = AgentMusicPlaybackStateStore.STATE_COMPLETED,
                    positionMs = completed.safeDuration() ?: completed.safeCurrentPosition(),
                    durationMs = completed.safeDuration()
                )
                updateMediaSessionPlaybackState(PlaybackState.STATE_STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            setOnErrorListener { _, what, extra ->
                handlePlaybackError("播放失败：what=$what extra=$extra")
                true
            }
        }

        runCatching {
            configureDataSource(player, playbackTarget)
            player.prepareAsync()
            mediaPlayer = player
        }.onFailure { error ->
            runCatching { player.release() }
            handlePlaybackError("无法打开音频：${error.message ?: "未知错误"}")
        }
    }

    private fun configureDataSource(
        player: MediaPlayer,
        playbackTarget: String
    ) {
        when {
            playbackTarget.startsWith("http://", ignoreCase = true) ||
                playbackTarget.startsWith("https://", ignoreCase = true) -> {
                player.setDataSource(playbackTarget)
            }

            playbackTarget.startsWith("content://", ignoreCase = true) -> {
                player.setDataSource(applicationContext, android.net.Uri.parse(playbackTarget))
            }

            playbackTarget.startsWith("file://", ignoreCase = true) -> {
                val parsed = android.net.Uri.parse(playbackTarget)
                player.setDataSource(parsed.path ?: playbackTarget.removePrefix("file://"))
            }

            else -> {
                player.setDataSource(playbackTarget)
            }
        }
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        val player = mediaPlayer ?: return
        val paused = runCatching {
            if (player.isPlaying) {
                player.pause()
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (!paused) {
            return
        }

        stopProgressUpdates()
        if (abandonFocus) {
            abandonAudioFocus()
        }
        updateSnapshot(
            state = AgentMusicPlaybackStateStore.STATE_PAUSED,
            positionMs = player.safeCurrentPosition(),
            durationMs = player.safeDuration()
        )
        updateMediaSessionPlaybackState(PlaybackState.STATE_PAUSED)
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun resumeInternal() {
        val player = mediaPlayer ?: return
        if (!prepareForegroundPlayback()) {
            return
        }

        runCatching {
            if (AgentMusicPlaybackStateStore.snapshot().state ==
                AgentMusicPlaybackStateStore.STATE_COMPLETED
            ) {
                player.seekTo(0)
            }
            if (!player.isPlaying) {
                player.start()
            }
            updateSnapshot(
                state = AgentMusicPlaybackStateStore.STATE_PLAYING,
                positionMs = player.safeCurrentPosition(),
                durationMs = player.safeDuration()
            )
            updateMediaSessionPlaybackState(PlaybackState.STATE_PLAYING)
            startProgressUpdates()
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            handlePlaybackError("恢复播放失败：${error.message ?: "未知错误"}")
        }
    }

    private fun seekInternal(positionMs: Long) {
        val player = mediaPlayer ?: return
        runCatching {
            player.seekTo(positionMs.toInt())
            updateSnapshot(
                positionMs = positionMs,
                durationMs = player.safeDuration()
            )
            updateMediaSessionPlaybackState(
                when (AgentMusicPlaybackStateStore.snapshot().state) {
                    AgentMusicPlaybackStateStore.STATE_PLAYING -> PlaybackState.STATE_PLAYING
                    AgentMusicPlaybackStateStore.STATE_PAUSED -> PlaybackState.STATE_PAUSED
                    else -> PlaybackState.STATE_BUFFERING
                }
            )
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            handlePlaybackError("跳转播放进度失败：${error.message ?: "未知错误"}")
        }
    }

    private fun stopInternal(
        targetState: String,
        errorMessage: String? = null
    ) {
        stopProgressUpdates()
        val player = mediaPlayer
        val positionMs = player?.safeCurrentPosition() ?: AgentMusicPlaybackStateStore.snapshot().positionMs
        val durationMs = player?.safeDuration() ?: AgentMusicPlaybackStateStore.snapshot().durationMs
        releasePlayer()
        abandonAudioFocus()
        updateSnapshot(
            state = targetState,
            positionMs = positionMs,
            durationMs = durationMs,
            errorMessage = errorMessage
        )
        updateMediaSessionPlaybackState(
            if (targetState == AgentMusicPlaybackStateStore.STATE_ERROR) {
                PlaybackState.STATE_ERROR
            } else {
                PlaybackState.STATE_STOPPED
            }
        )
        mediaSession?.isActive = targetState == AgentMusicPlaybackStateStore.STATE_PLAYING
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (targetState != AgentMusicPlaybackStateStore.STATE_STOPPED &&
            targetState != AgentMusicPlaybackStateStore.STATE_COMPLETED
        ) {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    private fun handlePlaybackError(message: String) {
        stopInternal(
            targetState = AgentMusicPlaybackStateStore.STATE_ERROR,
            errorMessage = message
        )
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        val request = audioFocusRequest ?: return false
        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun prepareForegroundPlayback(): Boolean {
        // Background audio needs an active foreground service before requesting
        // focus or starting the player, including a user-initiated resume.
        return runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
            if (!requestAudioFocus()) {
                handlePlaybackError("无法获取音频焦点，请打开应用后重试")
                false
            } else {
                true
            }
        }.getOrElse { error ->
            handlePlaybackError("无法开始音频播放：${error.message ?: "未知错误"}")
            false
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) {
            return
        }
        audioFocusRequest?.let { request ->
            runCatching {
                audioManager.abandonAudioFocusRequest(request)
            }
        }
        hasAudioFocus = false
    }

    private fun startProgressUpdates() {
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.post(progressUpdater)
    }

    private fun stopProgressUpdates() {
        mainHandler.removeCallbacks(progressUpdater)
    }

    private fun updateSnapshot(
        state: String? = null,
        positionMs: Long? = null,
        durationMs: Long? = null,
        errorMessage: String? = null
    ) {
        val previous = AgentMusicPlaybackStateStore.snapshot()
        AgentMusicPlaybackStateStore.set(
            previous.copy(
                state = state ?: previous.state,
                source = currentSource ?: previous.source,
                playbackTarget = currentPlaybackTarget ?: previous.playbackTarget,
                title = currentTitle.ifBlank { previous.title.orEmpty() },
                positionMs = positionMs ?: previous.positionMs,
                durationMs = durationMs ?: previous.durationMs,
                isLooping = currentLoop,
                backend = AgentMusicPlaybackStateStore.BACKEND_OMNIBOT,
                lastError = errorMessage
            )
        )
    }

    private fun updateSnapshotFromPlayer() {
        val player = mediaPlayer ?: return
        updateSnapshot(
            positionMs = player.safeCurrentPosition(),
            durationMs = player.safeDuration()
        )
    }

    private fun updateMediaSessionMetadata(durationMs: Long?) {
        val metadata = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            if (durationMs != null && durationMs > 0L) {
                putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            }
        }.build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateMediaSessionPlaybackState(state: Int) {
        val snapshot = AgentMusicPlaybackStateStore.snapshot()
        val position = mediaPlayer?.safeCurrentPosition() ?: snapshot.positionMs
        val speed = if (state == PlaybackState.STATE_PLAYING) 1.0f else 0.0f
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, speed)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val snapshot = AgentMusicPlaybackStateStore.snapshot()
        val openAppIntent = PendingIntent.getActivity(
            this,
            811001,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val compactActions = mutableListOf<Action>()
        if (snapshot.state == AgentMusicPlaybackStateStore.STATE_PLAYING) {
            compactActions += buildNotificationAction(
                requestCode = 811002,
                action = ACTION_PAUSE,
                iconRes = android.R.drawable.ic_media_pause,
                title = "暂停"
            )
        } else {
            compactActions += buildNotificationAction(
                requestCode = 811003,
                action = ACTION_RESUME,
                iconRes = android.R.drawable.ic_media_play,
                title = "继续"
            )
        }
        compactActions += buildNotificationAction(
            requestCode = 811004,
            action = ACTION_STOP,
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            title = "停止"
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot.title ?: currentTitle)
            .setContentText(buildNotificationSubtitle(snapshot))
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.state == AgentMusicPlaybackStateStore.STATE_PLAYING)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )

        compactActions.forEach(builder::addAction)
        return builder.build()
    }

    private fun buildNotificationAction(
        requestCode: Int,
        action: String,
        iconRes: Int,
        title: String
    ): Action {
        val pendingIntent = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AgentMusicPlaybackService::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        return Action.Builder(
            Icon.createWithResource(this, iconRes),
            title,
            pendingIntent
        ).build()
    }

    private fun buildNotificationSubtitle(
        snapshot: AgentMusicPlaybackSnapshot
    ): String {
        val progress = when {
            snapshot.durationMs != null && snapshot.durationMs > 0L -> {
                "${formatDuration(snapshot.positionMs)} / ${formatDuration(snapshot.durationMs)}"
            }

            snapshot.positionMs > 0L -> formatDuration(snapshot.positionMs)
            else -> ""
        }
        val localizedContext = cn.com.omnimind.baselib.i18n.AppLocaleManager.localizedContext(this)
        val stateLabel = when (snapshot.state) {
            AgentMusicPlaybackStateStore.STATE_PREPARING -> localizedContext.getString(R.string.music_state_preparing)
            AgentMusicPlaybackStateStore.STATE_PLAYING -> localizedContext.getString(R.string.music_state_playing)
            AgentMusicPlaybackStateStore.STATE_PAUSED -> localizedContext.getString(R.string.music_state_paused)
            AgentMusicPlaybackStateStore.STATE_COMPLETED -> localizedContext.getString(R.string.music_state_completed)
            AgentMusicPlaybackStateStore.STATE_ERROR -> localizedContext.getString(R.string.music_state_error)
            else -> localizedContext.getString(R.string.music_state_stopped)
        }
        return listOf(stateLabel, progress)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "音频播放前台通知与系统媒体控制"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun dispatchSystemMediaKey(keyCode: Int) {
        val eventTime = System.currentTimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun formatDuration(valueMs: Long?): String {
        val totalSeconds = (valueMs ?: 0L).coerceAtLeast(0L) / 1000L
        val minutes = (totalSeconds / 60).toString().padStart(2, '0')
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        return "$minutes:$seconds"
    }

    private fun MediaPlayer.safeCurrentPosition(): Long {
        return runCatching { currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(0L)
    }

    private fun MediaPlayer.safeDuration(): Long? {
        return runCatching { duration.toLong() }
            .getOrNull()
            ?.takeIf { it > 0L }
    }
}
