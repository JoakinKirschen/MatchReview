package be.matchreview.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** A strong, distinct vibration that can be felt in a pocket when a period's time is up. */
object MatchAlerts {
    /**
     * True only the first time it is called for a period, so the live screen and the
     * notification clock never both alert for the same period end.
     */
    @Synchronized
    fun claimPeriodAlert(context: Context, matchId: Long, periodNumber: Int): Boolean {
        val prefs = context.getSharedPreferences("matchreview_live_alerts", 0)
        val key = "$matchId:$periodNumber"
        if (prefs.getString("last_period_alert", null) == key) return false
        prefs.edit().putString("last_period_alert", key).commit()
        return true
    }

    fun vibratePeriodEnd(context: Context) {
        val vibrator = vibrator(context) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 200, 450, 200, 800), -1))
    }

    /** A short tick that confirms a notification button press without looking. */
    fun confirmTap(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrator(context: Context): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        return vibrator?.takeIf { it.hasVibrator() }
    }
}
