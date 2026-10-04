package cloud.daixuan.adsentinel.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/** 拦截次数统计,仅保存在本地 SharedPreferences。 */
object BlockStats {

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun record(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = dayFormat.format(System.currentTimeMillis())
        val spDay = sp.getString(KEY_DAY, null)
        val editor = sp.edit()
        if (spDay != today) {
            editor.putString(KEY_DAY, today)
            editor.putInt(KEY_TODAY, 0)
        }
        editor.putInt(KEY_TODAY, sp.getInt(KEY_TODAY, 0) + 1)
        editor.putInt(KEY_TOTAL, sp.getInt(KEY_TOTAL, 0) + 1)
        editor.apply()
    }

    fun today(ctx: Context): Int = read(ctx)[0]

    fun total(ctx: Context): Int = read(ctx)[1]

    private fun read(ctx: Context): IntArray {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = dayFormat.format(System.currentTimeMillis())
        val savedDay = sp.getString(KEY_DAY, null)
        val todayCount = if (savedDay == today) sp.getInt(KEY_TODAY, 0) else 0
        return intArrayOf(todayCount, sp.getInt(KEY_TOTAL, 0))
    }

    private const val FILE = "adsentinel_stats"
    private const val KEY_DAY = "day"
    private const val KEY_TODAY = "today"
    private const val KEY_TOTAL = "total"
}
