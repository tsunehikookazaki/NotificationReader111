package net.the_okazakis.notificationreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.Menu
import android.view.MenuItem
import android.util.Log
import android.app.TimePickerDialog
import android.Manifest
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.widget.Toast

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    var isEnabled: Boolean
)

class AppAdapter(
    private val context: Context,
    private val apps: MutableList<AppInfo>,
    private val onAppToggled: (AppInfo) -> Unit,
    private val onAppSettingsClicked: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvName: TextView = view.findViewById(R.id.tv_app_name)
        val cbEnabled: CheckBox = view.findViewById(R.id.cb_enabled)
        val btnSettings: View = view.findViewById(R.id.btn_app_settings)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvName.text = app.appName
        
        holder.cbEnabled.setOnCheckedChangeListener(null)
        holder.cbEnabled.isChecked = app.isEnabled
        
        holder.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
            app.isEnabled = isChecked
            onAppToggled(app)
        }
        
        holder.btnSettings.setOnClickListener {
            onAppSettingsClicked(app)
        }
    }

    override fun getItemCount() = apps.size
}

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ツールバーを設定（これで3点リーダーが表示されるようになります）
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // 通知アクセス設定ボタン
        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }


        // Android 13以降の通知権限リクエスト
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }


        // 読み上げ間隔の設定処理
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val tvIntervalLabel = findViewById<TextView>(R.id.tv_interval_label)
        val sbIntervalMin = findViewById<SeekBar>(R.id.sb_interval_min)
        val sbIntervalSec = findViewById<SeekBar>(R.id.sb_interval_sec)

        val savedIntervalMin = prefs.getInt("read_interval", 0)
        val savedIntervalSec = prefs.getInt("read_interval_sec", 0)
        
        sbIntervalMin.progress = savedIntervalMin
        sbIntervalSec.progress = savedIntervalSec
        tvIntervalLabel.text = "同じ通知は ${savedIntervalMin}分${savedIntervalSec}秒間 読み上げない"

        val updateInterval = {
            val min = sbIntervalMin.progress
            val sec = sbIntervalSec.progress
            tvIntervalLabel.text = "同じ通知は ${min}分${sec}秒間 読み上げない"
            prefs.edit()
                .putInt("read_interval", min)
                .putInt("read_interval_sec", sec)
                .apply()
        }

        val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateInterval()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbIntervalMin.setOnSeekBarChangeListener(seekBarChangeListener)
        sbIntervalSec.setOnSeekBarChangeListener(seekBarChangeListener)

        // 読み上げ停止時間の設定処理
        val swQuietTime = findViewById<android.widget.Switch>(R.id.sw_quiet_time)
        val btnStartTime = findViewById<Button>(R.id.btn_start_time)
        val btnEndTime = findViewById<Button>(R.id.btn_end_time)

        swQuietTime.isChecked = prefs.getBoolean("quiet_time_enabled", false)
        swQuietTime.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("quiet_time_enabled", isChecked).apply()
        }

        var startHour = prefs.getInt("quiet_time_start_hour", 22)
        var startMin = prefs.getInt("quiet_time_start_min", 0)
        var endHour = prefs.getInt("quiet_time_end_hour", 7)
        var endMin = prefs.getInt("quiet_time_end_min", 0)

        fun updateTimeButtons() {
            btnStartTime.text = "開始: %02d:%02d".format(startHour, startMin)
            btnEndTime.text = "終了: %02d:%02d".format(endHour, endMin)
        }
        updateTimeButtons()

        btnStartTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                startHour = h
                startMin = m
                prefs.edit().putInt("quiet_time_start_hour", h).putInt("quiet_time_start_min", m).apply()
                updateTimeButtons()
            }, startHour, startMin, true).show()
        }

        btnEndTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                endHour = h
                endMin = m
                prefs.edit().putInt("quiet_time_end_hour", h).putInt("quiet_time_end_min", m).apply()
                updateTimeButtons()
            }, endHour, endMin, true).show()
        }

        // キーワードフィルタリングの設定処理
        val swKeywordFilter = findViewById<android.widget.Switch>(R.id.sw_keyword_filter)
        val etKeyword = findViewById<android.widget.EditText>(R.id.et_keyword)
        val btnAddKeyword = findViewById<Button>(R.id.btn_add_keyword)
        val tvKeywordsList = findViewById<TextView>(R.id.tv_keywords_list)
        val btnClearKeywords = findViewById<Button>(R.id.btn_clear_keywords)

        val isFilterEnabled = prefs.getBoolean("keyword_filter_enabled", false)
        swKeywordFilter.isChecked = isFilterEnabled

        swKeywordFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keyword_filter_enabled", isChecked).apply()
        }

        fun updateKeywordsDisplay() {
            val keywords = prefs.getStringSet("keywords", emptySet()) ?: emptySet()
            if (keywords.isEmpty()) {
                tvKeywordsList.text = "登録済み: なし"
            } else {
                tvKeywordsList.text = "登録済み: " + keywords.joinToString(", ")
            }
        }

        updateKeywordsDisplay()

        btnAddKeyword.setOnClickListener {
            val newWord = etKeyword.text.toString().trim()
            if (newWord.isNotEmpty()) {
                val keywords = prefs.getStringSet("keywords", emptySet())?.toMutableSet() ?: mutableSetOf()
                keywords.add(newWord)
                prefs.edit().putStringSet("keywords", keywords).apply()
                etKeyword.setText("")
                updateKeywordsDisplay()
            }
        }

        btnClearKeywords.setOnClickListener {
            prefs.edit().putStringSet("keywords", emptySet()).apply()
            updateKeywordsDisplay()
        }

        // 全て選択・解除ボタン
        findViewById<Button>(R.id.btn_select_all).setOnClickListener {
            setAllAppsEnabled(true)
        }
        findViewById<Button>(R.id.btn_deselect_all).setOnClickListener {
            setAllAppsEnabled(false)
        }

        setupRecyclerView()
        loadInstalledApps()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        Log.d("MainActivity", "onCreateOptionsMenu called")
        // メニュー項目を表示
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                // 使い方画面を開く
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        val rvApps = findViewById<RecyclerView>(R.id.rv_apps)
        adapter = AppAdapter(this, appList, { app ->
            saveAppState(app.packageName, app.isEnabled)
        }, { app ->
            showAppKeywordDialog(app)
        })
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter
        // ScrollView内でもスムーズに動くように（後でXMLを調整する前提）
        rvApps.isNestedScrollingEnabled = false
    }

    private fun showAppKeywordDialog(app: AppInfo) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val keyEnabled = "keyword_filter_enabled_${app.packageName}"
        val keyKeywords = "keywords_${app.packageName}"
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_keywords, null)
        val swEnabled = dialogView.findViewById<android.widget.Switch>(R.id.sw_app_keyword_filter)
        val etKeyword = dialogView.findViewById<android.widget.EditText>(R.id.et_app_keyword)
        val btnAdd = dialogView.findViewById<Button>(R.id.btn_app_add_keyword)
        val tvList = dialogView.findViewById<TextView>(R.id.tv_app_keywords_list)
        val btnClear = dialogView.findViewById<Button>(R.id.btn_app_clear_keywords)

        swEnabled.isChecked = prefs.getBoolean(keyEnabled, false)
        
        fun updateDisplay() {
            val keywords = prefs.getStringSet(keyKeywords, emptySet()) ?: emptySet()
            tvList.text = if (keywords.isEmpty()) "登録済み: なし" else "登録済み: " + keywords.joinToString(", ")
        }
        updateDisplay()

        btnAdd.setOnClickListener {
            val word = etKeyword.text.toString().trim()
            if (word.isNotEmpty()) {
                val keywords = prefs.getStringSet(keyKeywords, emptySet())?.toMutableSet() ?: mutableSetOf()
                keywords.add(word)
                prefs.edit().putStringSet(keyKeywords, keywords).apply()
                etKeyword.setText("")
                updateDisplay()
            }
        }

        btnClear.setOnClickListener {
            prefs.edit().putStringSet(keyKeywords, emptySet()).apply()
            updateDisplay()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("${app.appName} のキーワードフィルタ")
            .setView(dialogView)
            .setPositiveButton("閉じる") { _, _ ->
                prefs.edit().putBoolean(keyEnabled, swEnabled.isChecked).apply()
            }
            .show()
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        appList.clear()
        for (resolveInfo in resolveInfos) {
            val packageName = resolveInfo.activityInfo.packageName
            val appName = resolveInfo.loadLabel(pm).toString()
            val icon = resolveInfo.loadIcon(pm)
            val isEnabled = appPrefs.getBoolean(packageName, false)
            
            if (appList.none { it.packageName == packageName }) {
                appList.add(AppInfo(packageName, appName, icon, isEnabled))
            }
        }
        
        appList.sortWith(compareByDescending<AppInfo> { it.isEnabled }.thenBy { it.appName })
        adapter.notifyDataSetChanged()
    }

    private fun setAllAppsEnabled(enabled: Boolean) {
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val editor = appPrefs.edit()
        for (app in appList) {
            app.isEnabled = enabled
            editor.putBoolean(app.packageName, enabled)
        }
        editor.apply()
        appList.sortWith(compareByDescending<AppInfo> { it.isEnabled }.thenBy { it.appName })
        adapter.notifyDataSetChanged()
    }

    private fun saveAppState(packageName: String, isEnabled: Boolean) {
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        appPrefs.edit().putBoolean(packageName, isEnabled).apply()
        
        // チェックの状態が変わったので再ソートして一覧を更新
        appList.sortWith(compareByDescending<AppInfo> { it.isEnabled }.thenBy { it.appName })
        adapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        if (isNotificationServiceEnabled()) {
            tvStatus.text = "状態: 通知アクセスは許可されています✅\nチェックしたアプリの通知を読み上げます。"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            var msg = "状態: 通知アクセスが許可されていません❌\n「設定を開く」からこのアプリに許可を与えてください。"
            if (android.os.Build.VERSION.SDK_INT >= 33) { // Android 13+
                msg += "\n\n⚠️「アクセス拒否」と出る場合は、右上の「使い方」から解除手順を確認してください。"
            }
            tvStatus.text = msg
            tvStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val packageNames = NotificationManagerCompat.getEnabledListenerPackages(this)
        if (packageNames.contains(packageName)) return true
        
        val componentName = ComponentName(this, NotificationReaderService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
