package net.the_okazakis.notificationreader

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class NotificationReaderService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private val TAG = "NotificationReader"

    override fun onCreate() {
        super.onCreate()
        // TTSエンジンの初期化
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 日本語に設定
            val result = tts.setLanguage(Locale.JAPAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "日本語のTTSデータがありません、またはサポートされていません。")
            } else {
                isTtsInitialized = true
                Log.d(TAG, "TTS初期化完了")
            }
        } else {
            Log.e(TAG, "TTSの初期化に失敗しました。")
        }
    }

    private val lastReadTimes = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        sbn?.let {
            val packageName = it.packageName
            val notification = it.notification
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            
            // キーワードフィルタの判定
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val appKeyEnabled = "keyword_filter_enabled_$packageName"
            val appKeyKeywords = "keywords_$packageName"

            val isAppFilterEnabled = prefs.getBoolean(appKeyEnabled, false)
            val isGlobalFilterEnabled = prefs.getBoolean("keyword_filter_enabled", false)

            val allKeywords = mutableSetOf<String>()
            if (isAppFilterEnabled) {
                allKeywords.addAll(prefs.getStringSet(appKeyKeywords, emptySet()) ?: emptySet())
            }
            if (isGlobalFilterEnabled) {
                allKeywords.addAll(prefs.getStringSet("keywords", emptySet()) ?: emptySet())
            }

            if ((isAppFilterEnabled || isGlobalFilterEnabled) && allKeywords.isNotEmpty()) {
                val content = "$title $text"
                val found = allKeywords.filter { it.isNotEmpty() }.any { content.contains(it, ignoreCase = true) }
                if (!found) {
                    Log.d(TAG, "キーワード不一致につきスキップ: $packageName (キーワードリスト: $allKeywords, 内容: $content)")
                    return
                }
            }

            // チェックボックスの設定を確認
            val appPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
            val isEnabled = appPrefs.getBoolean(packageName, false)
            
            if (!isEnabled) {
                Log.d(TAG, "アプリが有効でないためスキップ: $packageName")
                return
            }

            Log.d(TAG, "通知受信（読み上げ対象）: [$packageName] Title: $title, Text: $text")

            if (title.isNotEmpty() || text.isNotEmpty()) {
                // 重複判定ロジック
                val notificationKey = "${packageName}_${title}_${text}"
                val currentTime = System.currentTimeMillis()
                val lastReadTime = lastReadTimes[notificationKey] ?: 0L
                
                // ユーザー設定の間隔
                val intervalMinutes = prefs.getInt("read_interval", 0)
                val intervalSeconds = prefs.getInt("read_interval_sec", 0)
                val totalIntervalSeconds = intervalMinutes * 60 + intervalSeconds
                val minIntervalMillis = if (totalIntervalSeconds > 0) {
                    totalIntervalSeconds * 1000L
                } else {
                    5000L
                }

                if (currentTime - lastReadTime < minIntervalMillis) {
                    Log.d(TAG, "重複または短期間の再通知のためスキップ: $packageName (間隔: ${currentTime - lastReadTime}ms < $minIntervalMillis)")
                    return
                }

                // 読み上げ停止時間の判定
                if (prefs.getBoolean("quiet_time_enabled", false)) {
                    val startHour = prefs.getInt("quiet_time_start_hour", 22)
                    val startMin = prefs.getInt("quiet_time_start_min", 0)
                    val endHour = prefs.getInt("quiet_time_end_hour", 7)
                    val endMin = prefs.getInt("quiet_time_end_min", 0)

                    val calendar = java.util.Calendar.getInstance()
                    val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                    val currentMin = calendar.get(java.util.Calendar.MINUTE)

                    val currentTimeInMin = currentHour * 60 + currentMin
                    val startTimeInMin = startHour * 60 + startMin
                    val endTimeInMin = endHour * 60 + endMin

                    val isQuietTime = if (startTimeInMin <= endTimeInMin) {
                        currentTimeInMin in startTimeInMin until endTimeInMin
                    } else {
                        currentTimeInMin >= startTimeInMin || currentTimeInMin < endTimeInMin
                    }

                    if (isQuietTime) {
                        Log.d(TAG, "停止時間（おやすみモード）中のため読み上げスキップ")
                        return
                    }
                }

                // 読み上げ実行
                lastReadTimes[notificationKey] = currentTime

                // キーワードの除外
                var cleanTitle = title
                var cleanText = text
                allKeywords.filter { it.isNotEmpty() }.forEach { keyword ->
                    cleanTitle = cleanTitle.replace(keyword, "", ignoreCase = true)
                    cleanText = cleanText.replace(keyword, "", ignoreCase = true)
                }

                val appName = getAppNameFromPackage(packageName)
                val speechText = "${appName}から通知です。 ${cleanTitle}。 ${cleanText}"
                Log.d(TAG, "読み上げ実行: $speechText")
                speak(speechText)
            } else {
                Log.d(TAG, "通知内容が空のためスキップ: $packageName")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // 通知が消去された時の処理（今回は特に不要）
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            "不明なアプリ"
        }
    }

    private fun speak(text: String) {
        if (isTtsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
        } else {
            Log.w(TAG, "TTSがまだ準備できていません。読み飛ばし: $text")
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
