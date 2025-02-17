package com.master.exoplayer

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsCollector
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.upstream.cache.*
import com.google.android.exoplayer2.util.Clock
import com.google.android.exoplayer2.util.Util
import java.io.File

class ExoPlayerHelper(val mContext: Context, private val playerView: PlayerView, enableCache: Boolean = true, private val loopVideo: Boolean = false, val loopCount: Int = Integer.MAX_VALUE) :
    DefaultLifecycleObserver {

    private var stoppedTime: Long = 0L
    private var mPlayer: ExoPlayer
    var cacheSizeInMb: Long = 500

    var progressRequired: Boolean = false

    companion object {
        private var simpleCache: SimpleCache? = null
        var mLoadControl: DefaultLoadControl? = null
        var mDataSourceFactory: DataSource.Factory? = null
        var mCacheEnabled = false
    }

    init {
        val bandwidthMeter = DefaultBandwidthMeter.Builder(mContext).build()
        if (mCacheEnabled != enableCache || mDataSourceFactory == null) {


            mDataSourceFactory = null

            val httpDataSourceFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            mDataSourceFactory = DefaultDataSource.Factory(mContext, httpDataSourceFactory)

            mLoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    32 * 1024,
                    64 * 1024,
                    1024,
                    1024
                ).build()

            if (enableCache) {
                val evictor = LeastRecentlyUsedCacheEvictor(cacheSizeInMb * 1024 * 1024)
                val file = File(mContext.cacheDir, "media")

                if (simpleCache == null)
                    simpleCache = SimpleCache(file, evictor, StandaloneDatabaseProvider(mContext))

                val dataSink = CacheDataSink.Factory()
                    .setCache(simpleCache!!)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
                    .setFragmentSize((2 * 1024 * 1024).toLong())

                mDataSourceFactory = CacheDataSource.Factory()
                    .setCache(simpleCache!!)
                    .setUpstreamDataSourceFactory(mDataSourceFactory)
                    .setCacheReadDataSourceFactory(FileDataSource.Factory())
                    .setCacheWriteDataSinkFactory(dataSink)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }
        mCacheEnabled = enableCache

        mPlayer = ExoPlayer.Builder(
            mContext,
            DefaultRenderersFactory(mContext),
            DefaultMediaSourceFactory(mContext),
            DefaultTrackSelector(mContext),
            mLoadControl!!,
            bandwidthMeter,
            AnalyticsCollector(Clock.DEFAULT)
        ).build()

        playerView.setShutterBackgroundColor(Color.TRANSPARENT)
        playerView.player = mPlayer

    }

    private var mediaSource: MediaSource? = null
    private var isPreparing = false //This flag is used only for callback

    /**
     * Sets the url to play
     *
     * @param url url to play
     * @param autoPlay whether url will play as soon it Loaded/Prepared
     */
    private var url: String = ""

    fun setUrl(url: String, autoPlay: Boolean = false) {
        if (lifecycle?.currentState == Lifecycle.State.RESUMED) {
            val resume = this.url == url
            this.url = url
            mediaSource = buildMediaSource(Uri.parse(url))
            loopIfNecessary()
            mPlayer.playWhenReady = autoPlay
            isPreparing = true
            mPlayer.setMediaSource(mediaSource!!)
            mPlayer.prepare()
            if (resume) {
                seekTo(stoppedTime)
            }
        }
    }

    var lifecycle: Lifecycle? = null
    fun makeLifeCycleAware(activity: AppCompatActivity) {
        lifecycle = activity.lifecycle
        activity.lifecycle.addObserver(this)
    }

    fun makeLifeCycleAware(fragment: Fragment) {
        lifecycle = fragment.lifecycle
        fragment.lifecycle.addObserver(this)
    }

    /**
     * Trim or clip media to given start and end milliseconds,
     * Ensure you must call this method after [setUrl] method call
     * You Make sure start time < end time ( Something you do :) )
     *
     * @param start starting time in millisecond
     * @param end ending time in millisecond
     */
    fun clip(start: Long, end: Long) {
        if (mediaSource != null) {
            mediaSource = ClippingMediaSource(mediaSource!!, start * 1000, end * 1000)
            loopIfNecessary()
        }
        mPlayer.setMediaSource(mediaSource!!)
        mPlayer.prepare()
    }

    private fun buildMediaSource(uri: Uri): MediaSource {
        val mediaItem = MediaItem.fromUri(uri)
        val type = Util.inferContentType(uri)
        when (type) {
            C.TYPE_SS -> return SsMediaSource.Factory(mDataSourceFactory!!).createMediaSource(mediaItem)
            C.TYPE_DASH -> return DashMediaSource.Factory(mDataSourceFactory!!).createMediaSource(mediaItem)
            C.TYPE_HLS -> return HlsMediaSource.Factory(mDataSourceFactory!!).createMediaSource(mediaItem)
            C.TYPE_OTHER -> return ProgressiveMediaSource.Factory(mDataSourceFactory!!).createMediaSource(mediaItem)
            else -> {
                throw IllegalStateException("Unsupported type: $type") as Throwable
            }
        }
    }

    /**
     * Looping if user set if looping necessary
     */
    private fun loopIfNecessary() {
        if (loopVideo) {
            mPlayer.repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    /**
     * Used to start player
     * Ensure you must call this method after [setUrl] method call
     */
    fun play() {
        mPlayer.playWhenReady = true
    }

    /**
     * Used to pause player
     * Ensure you must call this method after [setUrl] method call
     */
    fun pause() {
        mPlayer.playWhenReady = false
    }

    /**
     * Used to stop player
     * Ensure you must call this method after [setUrl] method call
     */
    fun stop() {
        mPlayer.stop()
    }


    /**
     * Used to seek player to given position(in milliseconds)
     * Ensure you must call this method after [setUrl] method call
     */
    fun seekTo(positionMs: Long) {
        mPlayer.seekTo(positionMs)
    }


    val durationHandler = Handler()
    private var durationRunnable: Runnable? = null

    private fun startTimer() {
        if (progressRequired) {
            if (durationRunnable != null)
                durationHandler.postDelayed(durationRunnable!!, 17)
        }
    }

    private fun stopTimer() {
        if (progressRequired) {
            if (durationRunnable != null)
                durationHandler.removeCallbacks(durationRunnable!!)
        }
    }

    /**
     * Returns SimpleExoPlayer instance you can use it for your own implementation
     */
    fun getPlayer(): ExoPlayer {
        return mPlayer
    }

    /**
     * Used to set different quality url of existing video/audio
     */
    fun setQualityUrl(qualityUrl: String) {
        val currentPosition = mPlayer.currentPosition
        mediaSource = buildMediaSource(Uri.parse(qualityUrl))
        loopIfNecessary()
        mPlayer.setMediaSource(mediaSource!!)
        mPlayer.prepare()
        mPlayer.seekTo(currentPosition)
    }

    /**
     * Normal speed is 1f and double the speed would be 2f.
     */
    fun setSpeed(speed: Float) {
        val param = PlaybackParameters(speed)
        mPlayer.playbackParameters = param
    }

    /**
     * Returns whether player is playing
     */
    fun isPlaying(): Boolean {
        return mPlayer.playWhenReady
    }

    /**
     * Toggle mute and unmute
     */
    fun toggleMuteUnMute() {
        if (mPlayer.volume == 0f) unMute() else mute()
    }

    /**
     * Mute player
     */
    fun mute() {
        mPlayer.volume = 0f
    }

    /**
     * Unmute player
     */
    fun unMute() {
        mPlayer.volume = 1f
    }


    override fun onPause(owner: LifecycleOwner) {
        mPlayer.playWhenReady = false
        stoppedTime = mPlayer.currentPosition
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mPlayer.playWhenReady = false
        stoppedTime = mPlayer.currentPosition
    }

    override fun onResume(owner: LifecycleOwner) {
        mPlayer.playWhenReady = true
        mPlayer.seekTo(stoppedTime)
        stoppedTime = 0L
    }

    //LISTENERS

    /**
     * Listener that used for most popular callbacks
     */
    fun setListener(progressRequired: Boolean = false, listener: Listener) {
        this.progressRequired = progressRequired
        mPlayer.addListener(object : Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                listener.onError(error)
            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {

                //Log.i("EXO", "onPlayerStateChanged $playWhenReady with ${url}")
                if (isPreparing && playbackState == Player.STATE_READY) {
                    isPreparing = false
                    listener.onPlayerReady()
                }
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        listener.onBuffering(true)
                    }
                    Player.STATE_READY -> {
                        listener.onBuffering(false)
                        if (playWhenReady) {
                            startTimer()
                            listener.onStart()
                        } else {
                            stopTimer()
                            listener.onStop()
                        }
                    }
                    Player.STATE_IDLE -> {
                        stopTimer()
                        listener.onBuffering(false)
                        listener.onError(null)
                    }
                    Player.STATE_ENDED -> {
                        listener.onBuffering(false)
                        stopTimer()
                        listener.onStop()
                    }
                }
            }

        })

        playerView.setControllerVisibilityListener { visibility ->
            listener.onToggleControllerVisible(visibility == View.VISIBLE)
        }

        if (progressRequired) {
            durationRunnable = Runnable {
                listener.onProgress(mPlayer.currentPosition)
                if (mPlayer.playWhenReady) {
                    durationHandler.postDelayed(durationRunnable!!, 500)
                }
            }
        }
    }

    interface Listener {
        fun onPlayerReady() {}
        fun onStart() {}
        fun onStop() {}
        fun onVideoStopped(time: Long, index: Int) {}
        fun onProgress(positionMs: Long) {}
        fun onError(error: PlaybackException?) {}
        fun onBuffering(isBuffering: Boolean) {}
        fun onToggleControllerVisible(isVisible: Boolean) {}

    }
}