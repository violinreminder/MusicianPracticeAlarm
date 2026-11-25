package com.violinnagger.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import java.time.ZonedDateTime

/** Sets the next wake-up alarm. Uses the phone's alarm-clock slot, which Android honors even in battery saver. */
object Scheduler {
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun nextTriggerMs(c: Context): Long {
        val now = ZonedDateTime.now()
        val pt = Practice.practiceTimeToday(c)
        var t: ZonedDateTime
        if (Practice.playedToday(c)) {
            t = pt.plusDays(1)
        } else if (now.isBefore(pt)) {
            t = pt
        } else {
            val lv = Practice.level(c)
            t = now.plusMinutes(Practice.intervalMinutes(lv).toLong())
            if (t.isAfter(Practice.nagWindowEnd(c))) t = pt.plusDays(1)
            val snooze = Store.snoozeUntil(c)
            val snoozeAt = java.time.Instant.ofEpochMilli(snooze).atZone(now.zone)
            if (snooze > System.currentTimeMillis() && snoozeAt.isAfter(t) &&
                !snoozeAt.isAfter(Practice.nagWindowEnd(c))
            ) {
                t = snoozeAt
            }
        }
        return t.toInstant().toEpochMilli()
    }

    private fun setAlarm(ctx: Context, atMs: Long, fire: PendingIntent, show: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), fire)
        } catch (e: SecurityException) {
            // Exact alarms not allowed on this phone: fall back to a looser alarm instead of crashing.
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, fire)
            } catch (x: Exception) {
            }
        }
    }

    /** For testing: fires a real alarm after the given minutes, even with the phone locked. */
    fun scheduleTest(c: Context, minutes: Int) {
        val ctx = c.applicationContext
        val fire = PendingIntent.getBroadcast(
            ctx, 200, Intent(ctx, AlarmReceiver::class.java).putExtra("test", true), FLAGS
        )
        val show = PendingIntent.getActivity(ctx, 101, Intent(ctx, MainActivity::class.java), FLAGS)
        setAlarm(ctx, System.currentTimeMillis() + minutes * 60_000L, fire, show)
    }

    fun scheduleNext(c: Context) {
        if (!Store.isSetup(c)) return
        val ctx = c.applicationContext
        val fire = PendingIntent.getBroadcast(ctx, 100, Intent(ctx, AlarmReceiver::class.java), FLAGS)
        val show = PendingIntent.getActivity(ctx, 101, Intent(ctx, MainActivity::class.java), FLAGS)
        setAlarm(ctx, nextTriggerMs(ctx), fire, show)
    }
}

object Nag {
    const val CHANNEL = "nag_v1"
    const val ID = 1
    const val ACTION_SNOOZE = "com.violinnagger.app.SNOOZE"
    const val ACTION_HONOR = "com.violinnagger.app.HONOR"
    const val ACTION_DISMISS = "com.violinnagger.app.DISMISS"
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Practice nags", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "The reminders to practice your instrument"
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    fun post(c: Context, test: Boolean = false) {
        val ctx = c.applicationContext
        ensureChannel(ctx)
        val lvl = if (test) 1 else Practice.level(ctx)
        val locked = Practice.locked(ctx)
        val msg = Practice.message(ctx, lvl)
        val detail = if (locked) {
            msg + "\n\nOpen the app and play for " + Store.needMinutes(ctx) + " minutes. I will be listening."
        } else {
            msg
        }
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("nag", true),
            FLAGS
        )
        val b = Notification.Builder(ctx, Nag.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle((if (locked) "Trust revoked · " else "") + "Practice reminder " + lvl)
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
        if (!locked) {
            val snooze = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, ActionReceiver::class.java).setAction(ACTION_SNOOZE), FLAGS
            )
            val honor = PendingIntent.getBroadcast(
                ctx, 3, Intent(ctx, ActionReceiver::class.java).setAction(ACTION_HONOR), FLAGS
            )
            b.addAction(0, "Snooze " + Practice.snoozeMinutes(ctx) + " min", snooze)
            b.addAction(0, "I played", honor)
        }
        if (test) {
            val dismiss = PendingIntent.getBroadcast(
                ctx, 4, Intent(ctx, ActionReceiver::class.java).setAction(ACTION_DISMISS), FLAGS
            )
            b.addAction(0, "Dismiss test", dismiss)
        }
        ctx.getSystemService(NotificationManager::class.java).notify(ID, b.build())
    }

    fun cancel(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("test", false)) {
            Nag.post(context, true)
            return
        }
        if (!Store.isSetup(context)) return
        val snoozed = System.currentTimeMillis() < Store.snoozeUntil(context)
        val early = Practice.overdueMinutes(context) < -1.0
        if (!Practice.playedToday(context) && !snoozed && !early &&
            !ZonedDateTime.now().isAfter(Practice.nagWindowEnd(context))
        ) {
            Nag.post(context)
        }
        Scheduler.scheduleNext(context)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Nag.ACTION_HONOR -> {
                if (!Practice.locked(context)) Store.addLog(context, "honor")
            }
            Nag.ACTION_DISMISS -> {
                // Only clears the notification. Nothing is logged.
            }
            Nag.ACTION_SNOOZE -> {
                val mins = Practice.snoozeMinutes(context)
                Store.recordSnooze(context, System.currentTimeMillis() + mins * 60_000L)
            }
        }
        Nag.cancel(context)
        Scheduler.scheduleNext(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleNext(context)
    }
}
