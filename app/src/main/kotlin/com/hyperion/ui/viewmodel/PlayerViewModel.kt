package com.hyperion.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.Player.RepeatMode
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.paging.*
import com.google.common.util.concurrent.MoreExecutors
import com.hyperion.domain.manager.AccountManager
import com.hyperion.domain.manager.DownloadManager
import com.hyperion.domain.manager.PreferencesManager
import com.hyperion.domain.paging.BrowsePagingSource
import com.hyperion.player.PlaybackService
import com.zt.innertube.domain.model.DomainFormat
import com.zt.innertube.domain.model.DomainVideo
import com.zt.innertube.domain.model.DomainVideoPartial
import com.zt.innertube.domain.repository.InnerTubeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val application: Application,
    private val innerTube: InnerTubeRepository,
    private val accountManager: AccountManager,
    private val downloadManager: DownloadManager,
    private val pagingConfig: PagingConfig,
    val preferences: PreferencesManager
) : ViewModel() {
    @Immutable
    sealed interface State {
        object Loaded : State
        object Loading : State
        class Error(val exception: Exception) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set
    var video by mutableStateOf<DomainVideo?>(null)
        private set
    var videoFormats = mutableStateListOf<DomainFormat.Video>()
        private set
    private var audioSource: ProgressiveMediaSource? = null
    var videoFormat: DomainFormat.Video? = null
    var isFullscreen by mutableStateOf(false)
        private set
    var showFullDescription by mutableStateOf(false)
        private set
    var showControls by mutableStateOf(false)
        private set
    var showQualityPicker by mutableStateOf(false)
        private set
    var showDownloadDialog by mutableStateOf(false)
        private set

    private val listener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlayerViewModel.isPlaying = isPlaying
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            this@PlayerViewModel.isLoading = isLoading
        }

        override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
            this@PlayerViewModel.playbackState = playbackState
        }

        override fun onPlayerError(error: PlaybackException) {
            state = State.Error(error)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            this@PlayerViewModel.repeatMode = repeatMode
        }
    }

    private val dataSourceFactory = DefaultHttpDataSource.Factory()

    var isPlaying by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set

    @get:Player.State
    var playbackState by mutableStateOf(Player.STATE_IDLE)
        private set
    var duration by mutableStateOf(Duration.ZERO)
        private set
    var position by mutableStateOf(Duration.ZERO)
        private set
    @get:RepeatMode
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var relatedVideos = emptyFlow<PagingData<DomainVideoPartial>>()
        private set

    lateinit var player: MediaController

    init {
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()

        controllerFuture.addListener(
            /* listener = */ {
                player = controllerFuture.get()
                player.addListener(listener)
                player.prepare()

                viewModelScope.launch {
                    while (isActive) {
                        duration = player.duration.takeUnless { it == C.TIME_UNSET }?.milliseconds ?: Duration.ZERO
                        position = player.currentPosition.milliseconds
                        delay(500)
                    }
                }
            },
            /* executor = */ MoreExecutors.directExecutor()
        )
    }

    override fun onCleared() {
        player.release()
    }

    fun shareVideo() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"

            putExtra(Intent.EXTRA_TEXT, video!!.shareUrl)
            putExtra(Intent.EXTRA_TITLE, video!!.title)
        }

        application.startActivity(Intent.createChooser(shareIntent, null).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    fun skipForward() = player.seekForward()
    fun skipBackward() = player.seekBack()
    fun skipNext() = player.seekToNext()
    fun skipPrevious() = player.seekToPrevious()
    fun seekTo(milliseconds: Float) = player.seekTo(milliseconds.toLong())

    fun toggleDescription() {
        showFullDescription = !showFullDescription
    }

    fun toggleControls() {
        showControls = !showControls
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun toggleFullscreen() = if (isFullscreen) exitFullscreen() else enterFullscreen()

    fun showQualityPicker() {
        showQualityPicker = true
    }

    fun hideQualityPicker() {
        showQualityPicker = false
    }

    fun showDownloadDialog() {
        showDownloadDialog = true
    }

    fun hideDownloadDialog() {
        showDownloadDialog = false
    }

    // TODO: Use enum for like & dislike
    fun updateVote(like: Boolean) {
        viewModelScope.launch {
            try {

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun download() {
        viewModelScope.launch {
            try {

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setFormat(format: DomainFormat.Video) {
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(format.url))

        val mergingMediaSource = MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ true,
            /* clipDurations = */ true,
            /* ...mediaSources = */ videoSource, audioSource!!
        )

        player.setMediaItem(
            mergingMediaSource.mediaItem.let {
                it.buildUpon().setMediaId(it.localConfiguration!!.uri.toString()).build()
            }
        )
    }

    fun loadVideo(id: String) {
        state = State.Loading

        viewModelScope.launch {
            try {
                video = innerTube.getVideo(id)

                state = State.Loaded

                videoFormats.clear()
                videoFormats.addAll(video!!.formats.filterIsInstance<DomainFormat.Video>())
                videoFormat = videoFormats.first()

                val audioStream = video!!.formats.filterIsInstance<DomainFormat.Audio>().last()
                audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(audioStream.url))

                setFormat(videoFormat!!)

                relatedVideos = Pager(pagingConfig) {
                    BrowsePagingSource { key ->
                        if (key == null) {
                            innerTube.getNext(video!!.id).relatedVideos
                        } else {
                            innerTube.getRelatedVideos(video!!.id, key)
                        }
                    }
                }.flow.cachedIn(viewModelScope)
            } catch (e: Exception) {
                e.printStackTrace()
                state = State.Error(e)
            }
        }
    }

    fun updateSubscription(isSubscribed: Boolean) {
        viewModelScope.launch {
            try {
                val channelId = video!!.author.id
                // Make request to subscribe
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun enterFullscreen() {
        isFullscreen = true
    }

    fun exitFullscreen() {
        isFullscreen = false
    }

    fun showComments() {

    }

    fun selectFormat(selectedFormat: DomainFormat.Video) {
        setFormat(selectedFormat)
        hideQualityPicker()
    }

    fun toggleLoop() {
        player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ONE
        }
        showQualityPicker = false
    }
}