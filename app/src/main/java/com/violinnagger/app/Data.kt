package com.violinnagger.app

import android.content.Context
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/** Everything the app remembers, kept in the phone's private storage. */
object Store {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("violin_nagger", Context.MODE_PRIVATE)

    fun isSetup(c: Context): Boolean = sp(c).getBoolean("setup", false)
    fun practiceMinutes(c: Context): Int = sp(c).getInt("pm", 17 * 60)
    fun trustDays(c: Context): Int = sp(c).getInt("trust", 3)
    fun needMinutes(c: Context): Int = maxOf(5, sp(c).getInt("need", 5))
    fun installed(c: Context): String =
        sp(c).getString("installed", null) ?: LocalDate.now().toString()

    fun loudAlarm(c: Context): Boolean = sp(c).getBoolean("loud", true)
    fun setLoudAlarm(c: Context, v: Boolean) = sp(c).edit().putBoolean("loud", v).apply()

    fun setPracticeMinutes(c: Context, v: Int) = sp(c).edit().putInt("pm", v).apply()
    fun setTrustDays(c: Context, v: Int) = sp(c).edit().putInt("trust", v).apply()
    fun setNeedMinutes(c: Context, v: Int) = sp(c).edit().putInt("need", maxOf(5, v)).apply()

    fun finishSetup(c: Context, pm: Int, trust: Int, loud: Boolean = true) {
        sp(c).edit()
            .putBoolean("setup", true)
            .putInt("pm", pm)
            .putInt("trust", trust)
            .putBoolean("loud", loud)
            .putString("installed", LocalDate.now().toString())
            .apply()
    }

    fun snoozeUntil(c: Context): Long = sp(c).getLong("snoozeUntil", 0L)

    fun snoozeCountToday(c: Context): Int {
        val d = sp(c).getString("snoozeDate", "")
        return if (d == LocalDate.now().toString()) sp(c).getInt("snoozeN", 0) else 0
    }

    fun recordSnooze(c: Context, untilMs: Long) {
        val n = snoozeCountToday(c) + 1
        sp(c).edit()
            .putString("snoozeDate", LocalDate.now().toString())
            .putInt("snoozeN", n)
            .putLong("snoozeUntil", untilMs)
            .apply()
    }

    fun log(c: Context): Map<String, String> {
        val cur: Set<String> = sp(c).getStringSet("log", null) ?: emptySet()
        val m = HashMap<String, String>()
        for (e in cur) {
            val i = e.indexOf('|')
            if (i > 0) m[e.substring(0, i)] = e.substring(i + 1)
        }
        return m
    }

    fun addLog(c: Context, method: String) {
        val today = LocalDate.now().toString()
        val cur: Set<String> = sp(c).getStringSet("log", null) ?: emptySet()
        val s = HashSet<String>()
        for (e in cur) if (!e.startsWith("$today|")) s.add(e)
        s.add("$today|$method")
        sp(c).edit().putStringSet("log", s).apply()
    }

    /** For testing: makes it look like you last played this many days ago. */
    fun pretendSkipped(c: Context, days: Int) {
        sp(c).edit()
            .remove("log")
            .remove("snoozeUntil")
            .remove("snoozeN")
            .remove("snoozeDate")
            .putString("installed", LocalDate.now().minusDays(days.toLong()).toString())
            .apply()
    }

    fun eraseHistory(c: Context) {
        sp(c).edit()
            .remove("log")
            .remove("snoozeUntil")
            .remove("snoozeN")
            .remove("snoozeDate")
            .putString("installed", LocalDate.now().toString())
            .apply()
    }
}

/** The rules: honor system, trust running out, how annoying it is right now. */
object Practice {
    private val INTERVAL_MIN = intArrayOf(5, 4, 4, 3, 3, 2, 2, 2, 1, 1)

    private val MSG: Array<Array<String>> = arrayOf(
        arrayOf(
            "Time to play. Your instrument has been waiting all day.",
            "Practice time. Just pick it up.",
            "Hello. Instrument. Now."
        ),
        arrayOf(
            "A few minutes. That is all this takes.",
            "It is not going to play itself.",
            "Your instrument is looking at you."
        ),
        arrayOf(
            "Still here. Still not playing.",
            "Your instrument is getting lonely.",
            "You have not picked it up yet. I checked."
        ),
        arrayOf(
            "Your teacher would know. Teachers always know.",
            "This is the one thing you said you would do today.",
            "Pick. It. Up."
        ),
        arrayOf(
            "I have all day. I do not have other hobbies.",
            "Every minute you wait, it gets harder to start.",
            "Even one shaky note is a start."
        )
    )
    private val LOCKED_MSG = arrayOf(
        "Trust revoked. I need to hear it.",
        "No more honor system. Play, and I will listen.",
        "Nice try. The tap-to-skip button is gone."
    )

    fun today(): LocalDate = LocalDate.now()

    fun playedToday(c: Context): Boolean = Store.log(c).containsKey(today().toString())

    fun lastPractice(c: Context): LocalDate {
        val best = Store.log(c).keys.maxOrNull()
        return LocalDate.parse(best ?: Store.installed(c))
    }

    fun daysSince(c: Context): Int =
        max(0L, ChronoUnit.DAYS.between(lastPractice(c), today())).toInt()

    fun locked(c: Context): Boolean = !playedToday(c) && daysSince(c) >= Store.trustDays(c)

    fun practiceTimeToday(c: Context): ZonedDateTime {
        val pm = Store.practiceMinutes(c)
        return ZonedDateTime.of(today(), LocalTime.of(pm / 60, pm % 60), ZoneId.systemDefault())
    }

    fun overdueMinutes(c: Context): Double =
        Duration.between(practiceTimeToday(c), ZonedDateTime.now()).seconds / 60.0

    fun overdue(c: Context): Boolean = !playedToday(c) && overdueMinutes(c) >= 0.0

    /** Nagging stops three hours after practice time (never past 11:59 PM). */
    fun nagWindowEnd(c: Context): ZonedDateTime {
        val end = practiceTimeToday(c).plusHours(3)
        val midnight = ZonedDateTime.of(today(), LocalTime.of(23, 59), ZoneId.systemDefault())
        return if (end.isAfter(midnight)) midnight else end
    }

    fun level(c: Context): Int {
        val step = if (locked(c)) 3.0 else 6.0
        val lv = 1 + floor(max(0.0, overdueMinutes(c)) / step).toInt()
        return lv.coerceIn(1, 10)
    }

    fun intervalMinutes(level: Int): Int = INTERVAL_MIN[level.coerceIn(1, 10) - 1]

    fun snoozeMinutes(c: Context): Int = max(1, 10 - 3 * Store.snoozeCountToday(c))

    fun streak(c: Context): Int {
        val log = Store.log(c)
        var d = today()
        if (!log.containsKey(d.toString())) d = d.minusDays(1)
        var n = 0
        while (log.containsKey(d.toString())) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun weekCount(c: Context): Int {
        val log = Store.log(c)
        var d = today()
        var n = 0
        for (i in 0 until 7) {
            if (log.containsKey(d.toString())) n++
            d = d.minusDays(1)
        }
        return n
    }

    fun message(c: Context, level: Int): String {
        if (locked(c) && Random.nextInt(2) == 0) return LOCKED_MSG[Random.nextInt(LOCKED_MSG.size)]
        val band = ((level + 1) / 2).coerceIn(1, 5)
        val arr = MSG[band - 1]
        return arr[Random.nextInt(arr.size)]
    }
}
