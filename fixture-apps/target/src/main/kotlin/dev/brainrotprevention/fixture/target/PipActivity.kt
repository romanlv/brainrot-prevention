package dev.brainrotprevention.fixture.target

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.TextView

/**
 * A "video" screen: holds a silent, PLAYING MediaSession and enters picture-in-picture
 * when the user leaves it, or right away with `--ez enterPip true`.
 */
class PipActivity : Activity() {
    private var session: MediaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "BRP fixture PiP"
                textSize = 24f
            }
        )
        session = MediaSession(this, "brp-fixture").apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
                    Fix.log("media", "state" to "paused")
                }
            })
            isActive = true
        }
        Fix.log("media", "state" to "playing")
        setPictureInPictureParams(params())
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra("enterPip", false) == true) {
            window.decorView.post { enterPictureInPictureMode(params()) }
        }
    }

    private fun params(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(true)
        return b.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) enterPictureInPictureMode(params())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Fix.log("pip", "mode" to isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        Fix.log("resumed", "act" to "pip")
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
