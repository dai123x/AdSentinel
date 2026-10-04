package cloud.daixuan.adsentinel.data

import android.content.Context
import android.content.SharedPreferences

/** 轻量偏好封装。所有数据仅存于应用私有目录,不对外共享。 */
object Prefs {

    private const val FILE = "adsentinel"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.autoSkip: Boolean
        get() = sp(this).getBoolean("auto_skip", true)
        set(v) = sp(this).edit().putBoolean("auto_skip", v).apply()

    var Context.shakeGuard: Boolean
        get() = sp(this).getBoolean("shake_guard", true)
        set(v) = sp(this).edit().putBoolean("shake_guard", v).apply()

    var Context.blockNotify: Boolean
        get() = sp(this).getBoolean("block_notify", true)
        set(v) = sp(this).edit().putBoolean("block_notify", v).apply()

    var Context.whitelist: Set<String>
        get() = sp(this).getStringSet("whitelist", emptySet()) ?: emptySet()
        set(v) = sp(this).edit().putStringSet("whitelist", v).apply()

    var Context.customRulesJson: String?
        get() = sp(this).getString("custom_rules", null)
        set(v) = sp(this).edit().putString("custom_rules", v).apply()
}
