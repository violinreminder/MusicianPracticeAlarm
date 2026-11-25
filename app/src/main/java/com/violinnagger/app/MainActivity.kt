package com.violinnagger.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val BG = 0xFF1A110C.toInt()
private val CARD = 0xFF26190F.toInt()
private val LINE = 0xFF3D2A1B.toInt()
private val TEXT = 0xFFF4E8D6.toInt()
private val MUTED = 0xFFB39B82.toInt()
private val BRASS = 0xFFE3A03C.toInt()
private val BRASS_INK = 0xFF2A1700.toInt()
private val BTN = 0xFF3A2819.toInt()
private val ALARM = 0xFFE5533D.toInt()
private val OK = 0xFF7FBF6A.toInt()
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var setupPanel: LinearLayout
    private lateinit var mainPanel: LinearLayout

    // setup screen
    private lateinit var setupTimeBtn: Button
    private lateinit var setupTrust: Spinner
    private var setupPm = 17 * 60

    // main screen
    private lateinit var modeTv: TextView
    private lateinit var headlineTv: TextView
    private lateinit var sublineTv: TextView
    private lateinit var nextTv: TextView
    private lateinit var statStreak: TextView
    private lateinit var statSince: TextView
    private lateinit var statWeek: TextView
    private lateinit var actionsCard: LinearLayout
    private lateinit var btnListen: Button
    private lateinit var btnHonor: Button
    private lateinit var actionNote: TextView
    private lateinit var listenPanel: LinearLayout
    private lateinit var listenTime: TextView
    private lateinit var listenProgress: ProgressBar
    private lateinit var listenMeter: ProgressBar
    private lateinit var listenMsg: TextView
    private lateinit var checksBox: LinearLayout
    private lateinit var timeBtn: Button
    private lateinit var needSpinner: Spinner
    private lateinit var trustSpinner: Spinner
    private lateinit var loudBtn: Button
    private var setupLoud = true
    private lateinit var eraseBtn: Button

    private val needOptions = intArrayOf(5, 10, 15, 20, 30, 45, 60)
    private val trustOptions = intArrayOf(1, 2, 3, 4, 5, 7)

    private var listener: Listener? = null
    private var justDone = false
    private var eraseArmedAt = 0L
    private var fatalShown = false
    private lateinit var crashCard: LinearLayout
    private lateinit var crashText: TextView

    private val tick = object : Runnable {
        override fun run() {
            try {
                refresh()
            } catch (e: Throwable) {
                showFatal(e)
                return
            }
            h.postDelayed(this, 1000)
        }
    }

    // ---------- small view helpers ----------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun tv(
        text: String,
        size: Float = 16f,
        color: Int = TEXT,
        bold: Boolean = false,
        serif: Boolean = false
    ): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        t.typeface = Typeface.create(
            if (serif) Typeface.SERIF else Typeface.DEFAULT,
            if (bold) Typeface.BOLD else Typeface.NORMAL
        )
        return t
    }

    private fun shape(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != null) g.setStroke(dp(1), stroke)
        return g
    }

    private fun btn(text: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 16f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (primary) BRASS_INK else TEXT)
        b.background = shape(if (primary) BRASS else BTN, 12)
        b.minHeight = dp(52)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun card(): LinearLayout {
        val l = vbox()
        l.background = shape(CARD, 14, LINE)
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
        return l
    }

    private fun add(parent: LinearLayout, v: View, topDp: Int = 0) {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(topDp)
        parent.addView(v, lp)
    }

    private fun spinner(items: List<String>, selected: Int): Spinner {
        val s = Spinner(this)
        val act = this
        val adapter = object : ArrayAdapter<String>(act, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(TEXT)
                v.textSize = 17f
                v.setPadding(dp(8), dp(14), dp(8), dp(14))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(TEXT)
                v.setBackgroundColor(CARD)
                v.textSize = 17f
                v.setPadding(dp(16), dp(16), dp(16), dp(16))
                return v
            }
        }
        s.adapter = adapter
        s.setPopupBackgroundDrawable(shape(CARD, 12, LINE))
        s.setSelection(selected)
        return s
    }

    private fun fmtMinutes(pm: Int): String =
        LocalTime.of(pm / 60, pm % 60).format(DateTimeFormatter.ofPattern("h:mm a"))

    private fun dur(m: Int): String = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, hh, mm -> onPicked(hh * 60 + mm) }, current / 60, current % 60, false).show()
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        try {
            create()
        } catch (e: Throwable) {
            showFatal(e)
        }
    }

    private fun showFatal(e: Throwable) {
        fatalShown = true
        CrashLog.save(this, e)
        h.removeCallbacks(tick)
        val t = TextView(this)
        t.text = "Musician Practice Alarm hit an error.\nPlease take a screenshot of this screen and send it to Claude.\n\n" + CrashLog.text(e)
        t.setTextColor(TEXT)
        t.setBackgroundColor(BG)
        t.textSize = 12f
        t.setTextIsSelectable(true)
        t.setPadding(dp(16), dp(32), dp(16), dp(32))
        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(t)
        setContentView(sv)
    }

    private fun buildCrashCard() {
        crashCard = card()
        add(crashCard, tv("The app crashed last time", 16f, ALARM, true))
        crashText = tv("", 11f, MUTED)
        crashText.setTextIsSelectable(true)
        add(crashCard, crashText, 6)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val copy = btn("Copy details", false) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("crash", CrashLog.read(this) ?: ""))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        val dismiss = btn("Dismiss", false) {
            CrashLog.clear(this)
            updateCrashCard()
        }
        row.addView(copy, LinearLayout.LayoutParams(0, WRAP, 1f))
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.leftMargin = dp(10)
        row.addView(dismiss, lp)
        add(crashCard, row, 10)
        crashCard.visibility = View.GONE
        add(root, crashCard, 0)
    }

    private fun updateCrashCard() {
        val c = CrashLog.read(this)
        if (c == null) {
            crashCard.visibility = View.GONE
        } else {
            crashText.text = c
            crashCard.visibility = View.VISIBLE
        }
    }

    private fun create() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.isFillViewport = true
        root = vbox()
        root.setPadding(dp(18), dp(28), dp(18), dp(28))
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(scroll)
        buildCrashCard()
        buildSetup()
        buildMain()
        handleIntent(intent)
        showPanels()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        try {
            Nag.silence()
        } catch (e: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.getBooleanExtra("nag", false) && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (fatalShown) return
        try {
            showPanels()
            updateCrashCard()
            if (Store.isSetup(this)) {
                Scheduler.scheduleNext(this)
                syncSettingsViews()
            }
            refreshChecks()
            h.removeCallbacks(tick)
            h.post(tick)
        } catch (e: Throwable) {
            showFatal(e)
        }
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
        stopListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshChecks()
        if (requestCode == 2 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        }
    }

    private fun showPanels() {
        val ready = Store.isSetup(this)
        setupPanel.visibility = if (ready) View.GONE else View.VISIBLE
        mainPanel.visibility = if (ready) View.VISIBLE else View.GONE
    }

    // ---------- setup screen ----------

    private fun buildSetup() {
        setupPanel = vbox()
        add(setupPanel, tv("Set up your alarm", 28f, TEXT, true, true))
        add(setupPanel, tv("Three questions. You can change all of them later.", 15f, MUTED), 6)
        add(setupPanel, tv("What time do you want to practice?", 17f), 28)
        setupTimeBtn = btn("", false) {
            pickTime(setupPm) { pm ->
                setupPm = pm
                updateSetupTime()
            }
        }
        add(setupPanel, setupTimeBtn, 8)
        add(setupPanel, tv("How many days can you skip before I stop trusting you?", 17f), 28)
        setupTrust = spinner(listOf("1 day, then I have to hear you", "2 days", "3 days", "5 days"), 2)
        add(setupPanel, setupTrust, 8)
        add(
            setupPanel,
            tv("Until then you can tap \"I played\". After that, the microphone has to hear you play.", 14f, MUTED),
            16
        )
        add(setupPanel, tv("Should reminders ring like an alarm?", 17f), 28)
        val setupLoudBtn = btn(loudLabel(true), false) {}
        setupLoudBtn.setOnClickListener {
            setupLoud = !setupLoud
            setupLoudBtn.text = loudLabel(setupLoud)
        }
        add(setupPanel, setupLoudBtn, 8)
        add(
            setupPanel,
            tv(
                "ON: rings like a phone alarm and keeps going until you respond to the reminder.\n\n" +
                    "OFF: one sound per reminder, then it waits quietly in your notifications. Easier to ignore, so pick this only if the ringing would be a problem.",
                14f, MUTED
            ),
            12
        )
        add(setupPanel, btn("Start my alarm", true) { finishSetup() }, 24)
        updateSetupTime()
        root.addView(setupPanel, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun updateSetupTime() {
        setupTimeBtn.text = fmtMinutes(setupPm) + "  (tap to change)"
    }

    private fun finishSetup() {
        val trust = intArrayOf(1, 2, 3, 5)[setupTrust.selectedItemPosition]
        Store.finishSetup(this, setupPm, trust, setupLoud)
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
        Scheduler.scheduleNext(this)
        syncSettingsViews()
        showPanels()
        refresh()
        refreshChecks()
    }

    // ---------- main screen ----------

    private fun buildMain() {
        mainPanel = vbox()
        add(mainPanel, tv("Musician Practice Alarm", 26f, TEXT, true, true))

        val status = card()
        modeTv = tv("", 13f, BRASS, true)
        headlineTv = tv("", 28f, TEXT, false, true)
        sublineTv = tv("", 15f, MUTED)
        add(status, modeTv)
        add(status, headlineTv, 8)
        add(status, sublineTv, 4)
        nextTv = tv("", 14f, MUTED)
        add(status, nextTv, 10)
        add(mainPanel, status, 16)

        val stats = card()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        statStreak = tv("0", 26f, TEXT, true, true)
        statSince = tv("0", 26f, TEXT, true, true)
        statWeek = tv("0/7", 26f, TEXT, true, true)
        for ((num, label) in listOf(
            statStreak to "day streak",
            statSince to "days since last",
            statWeek to "this week"
        )) {
            val col = vbox()
            col.gravity = Gravity.CENTER_HORIZONTAL
            num.gravity = Gravity.CENTER
            col.addView(num, LinearLayout.LayoutParams(WRAP, WRAP))
            val cap = tv(label, 13f, MUTED)
            cap.gravity = Gravity.CENTER
            col.addView(cap, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        stats.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        add(mainPanel, stats, 12)

        listenPanel = card()
        add(listenPanel, tv("Listening for your playing", 18f, TEXT, true, true))
        listenTime = tv("5:00", 44f, TEXT, false, true)
        listenTime.gravity = Gravity.CENTER
        add(listenPanel, listenTime, 8)
        add(listenPanel, tv("left to play", 13f, MUTED).also { it.gravity = Gravity.CENTER })
        listenProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        listenProgress.max = 1000
        add(listenPanel, listenProgress, 12)
        listenMeter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        listenMeter.max = 100
        add(listenPanel, listenMeter, 8)
        listenMsg = tv("", 15f, MUTED)
        listenMsg.gravity = Gravity.CENTER
        add(listenPanel, listenMsg, 10)
        add(listenPanel, btn("Stop", false) { stopListening() }, 12)
        listenPanel.visibility = View.GONE
        add(mainPanel, listenPanel, 12)

        actionsCard = card()
        btnListen = btn("", true) { startListening() }
        btnHonor = btn("I played", false) { honor() }
        actionNote = tv("", 14f, MUTED)
        add(actionsCard, btnListen)
        add(actionsCard, btnHonor, 10)
        add(actionsCard, actionNote, 10)
        add(mainPanel, actionsCard, 12)

        val checks = card()
        add(checks, tv("Can reminders reach you?", 18f, TEXT, true, true))
        checksBox = vbox()
        add(checks, checksBox, 8)
        add(mainPanel, checks, 12)

        val settings = card()
        add(settings, tv("Settings", 18f, TEXT, true, true))
        add(settings, tv("Practice time", 14f, MUTED), 12)
        timeBtn = btn("", false) {
            pickTime(Store.practiceMinutes(this)) { pm ->
                Store.setPracticeMinutes(this, pm)
                Scheduler.scheduleNext(this)
                syncSettingsViews()
                refresh()
            }
        }
        add(settings, timeBtn, 4)
        add(settings, tv("Playing needed", 14f, MUTED), 12)
        needSpinner = spinner(needOptions.map { "$it min" }, 0)
        add(settings, needSpinner, 4)
        add(settings, tv("Require sound after this many days without playing", 14f, MUTED), 12)
        trustSpinner = spinner(trustOptions.map { if (it == 1) "1 day" else "$it days" }, 2)
        add(settings, trustSpinner, 4)
        add(settings, tv("Alarm sound", 14f, MUTED), 12)
        loudBtn = btn(loudLabel(Store.loudAlarm(this)), false) {
            Store.setLoudAlarm(this, !Store.loudAlarm(this))
            loudBtn.text = loudLabel(Store.loudAlarm(this))
        }
        add(settings, loudBtn, 4)
        add(settings, tv("History", 14f, MUTED), 18)
        eraseBtn = btn("Erase my history", false) { eraseClicked() }
        add(settings, eraseBtn, 10)
        add(mainPanel, settings, 12)

        needSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (Store.needMinutes(this@MainActivity) != needOptions[pos]) {
                    Store.setNeedMinutes(this@MainActivity, needOptions[pos])
                    refresh()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        trustSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (Store.trustDays(this@MainActivity) != trustOptions[pos]) {
                    Store.setTrustDays(this@MainActivity, trustOptions[pos])
                    Scheduler.scheduleNext(this@MainActivity)
                    refresh()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(mainPanel, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun syncSettingsViews() {
        timeBtn.text = fmtMinutes(Store.practiceMinutes(this)) + "  (tap to change)"
        val ni = needOptions.indexOf(Store.needMinutes(this))
        if (ni >= 0 && needSpinner.selectedItemPosition != ni) needSpinner.setSelection(ni)
        val ti = trustOptions.indexOf(Store.trustDays(this))
        if (ti >= 0 && trustSpinner.selectedItemPosition != ti) trustSpinner.setSelection(ti)
        loudBtn.text = loudLabel(Store.loudAlarm(this))
    }

    private fun loudLabel(on: Boolean) = if (on) "Ring like an alarm: ON" else "Ring like an alarm: OFF"

    private fun eraseClicked() {
        val now = System.currentTimeMillis()
        if (now - eraseArmedAt < 4000) {
            Store.eraseHistory(this)
            Nag.cancel(this)
            Scheduler.scheduleNext(this)
            eraseArmedAt = 0
            eraseBtn.text = "Erase my history"
            refresh()
        } else {
            eraseArmedAt = now
            eraseBtn.text = "Tap again to erase"
            h.postDelayed({ eraseBtn.text = "Erase my history" }, 4000)
        }
    }

    private fun honor() {
        if (Practice.locked(this)) return
        Store.addLog(this, "honor")
        Nag.cancel(this)
        Scheduler.scheduleNext(this)
        refresh()
    }

    private fun refresh() {
        if (!Store.isSetup(this)) return
        val done = Practice.playedToday(this)
        val locked = Practice.locked(this)
        if (!done) justDone = false
        modeTv.text = when {
            done -> "DONE FOR TODAY"
            locked -> "TRUST REVOKED"
            else -> "HONOR SYSTEM"
        }
        modeTv.setTextColor(
            when {
                done -> OK
                locked -> ALARM
                else -> BRASS
            }
        )
        val ptText = Practice.practiceTimeToday(this).format(DateTimeFormatter.ofPattern("h:mm a"))
        val need = Store.needMinutes(this)
        if (done && justDone) {
            headlineTv.text = "Nice. $need minutes done."
            sublineTv.text = "Reminders are off until tomorrow. Trust restored."
        } else if (done) {
            headlineTv.text = "You played today."
            sublineTv.text = if (Store.log(this)[Practice.today().toString()] == "mic") {
                "The microphone heard you."
            } else {
                "Logged on your word."
            }
        } else if (Practice.overdue(this)) {
            headlineTv.text = "Overdue by " + dur(Practice.overdueMinutes(this).toInt())
            val d = Practice.daysSince(this)
            sublineTv.text = if (locked) {
                "$d ${if (d == 1) "day" else "days"} without playing. I need to hear it."
            } else {
                "Practice time was $ptText."
            }
        } else {
            val mins = ceil(-Practice.overdueMinutes(this)).toInt()
            headlineTv.text = "Practice at $ptText"
            val left = Store.trustDays(this) - Practice.daysSince(this)
            sublineTv.text = "In " + dur(mins) + ". " + if (locked) {
                "Trust is revoked, so this one has to be heard."
            } else {
                "$left ${if (left == 1) "day" else "days"} until trust runs out."
            }
        }
        statStreak.text = Practice.streak(this).toString()
        statSince.text = Practice.daysSince(this).toString()
        statWeek.text = Practice.weekCount(this).toString() + "/7"
        btnHonor.visibility = if (locked || done) View.GONE else View.VISIBLE
        btnListen.text = "Start playing ($need min)"
        actionNote.text = when {
            locked -> "The honor button is gone until you play $need minutes the microphone can hear."
            done -> "Want to play more? Tap start. It still listens."
            else -> ""
        }
        actionNote.visibility = if (actionNote.text.isEmpty()) View.GONE else View.VISIBLE
        val nextAt = java.time.Instant.ofEpochMilli(Scheduler.nextTriggerMs(this))
            .atZone(java.time.ZoneId.systemDefault())
        nextTv.text = "Next reminder: " + nextAt.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    }

    private fun refreshChecks() {
        if (!::checksBox.isInitialized) return
        checksBox.removeAllViews()
        val nm = getSystemService(NotificationManager::class.java)
        var rows = 0

        fun row(text: String, action: String, onClick: () -> Unit) {
            rows++
            add(checksBox, tv(text, 14f, MUTED), if (rows == 1) 0 else 14)
            add(checksBox, btn(action, false, onClick), 6)
        }

        val notifPerm = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!notifPerm) {
            row("Notifications are not allowed yet, so I can't nag you.", "Allow notifications") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        } else if (!nm.areNotificationsEnabled()) {
            row("Notifications are turned off for this app.", "Open notification settings") {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        val alarms = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            row("Alarms are not allowed, so reminders may arrive late.", "Allow alarms and reminders") {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            row("The microphone is not allowed yet, so I can't hear you play.", "Allow microphone") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4)
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            row("Full-screen alerts are off. Reminders will only pop up at the top of the screen.", "Allow full-screen alerts") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                )
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            row("Some phones put apps to sleep. Allow this one to run in the background.", "Allow background use") {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
        if (rows == 0) {
            add(checksBox, tv("All set. Reminders can reach you.", 15f, OK))
        }
    }

    // ---------- listening ----------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (listener != null) return
        Nag.cancel(this)
        listenPanel.visibility = View.VISIBLE
        actionsCard.visibility = View.GONE
        listenMsg.text = "Play something. I am listening."
        listenProgress.progress = 0
        listenMeter.progress = 0
        val need = Store.needMinutes(this) * 60
        listenTime.text = "${need / 60}:${String.format("%02d", need % 60)}"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val l = Listener(
            { r -> onHeard(r) },
            { msg ->
                stopListening()
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        )
        listener = l
        l.start()
    }

    private fun stopListening() {
        listener?.stop()
        listener = null
        if (::listenPanel.isInitialized) {
            listenPanel.visibility = View.GONE
            actionsCard.visibility = View.VISIBLE
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onHeard(r: Analyzer.Result) {
        if (listener == null) return
        val need = Store.needMinutes(this) * 60.0
        listenProgress.progress = (min(1.0, r.credit / need) * 1000).toInt()
        val left = max(0.0, need - r.credit)
        listenTime.text = "${(left / 60).toInt()}:${String.format("%02d", (left % 60).toInt())}"
        listenMeter.progress = min(100, (r.rms * 900).toInt())
        listenMsg.text = when {
            r.playing -> "Hearing you play. Keep going."
            r.rms < 0.006f -> "Quiet. Play something."
            else -> "I hear sound, but not a held note. Hold one long note."
        }
        if (r.credit >= need) {
            stopListening()
            Store.addLog(this, "mic")
            Nag.cancel(this)
            Scheduler.scheduleNext(this)
            justDone = true
            refresh()
        }
    }
}
