package cloud.daixuan.adsentinel.data

import android.content.Context
import android.content.SharedPreferences

private const val FILE = "adsentinel"

private fun sp(ctx: Context): SharedPreferences =
    ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

/** 自动点击「跳过」按钮 */
var Context.autoSkip: Boolean
    get() = sp(this).getBoolean("auto_skip", true)
    set(v) = sp(this).edit().putBoolean("auto_skip", v).apply()

/** 摇一摇防护(广告页兜底返回) */
var Context.shakeGuard: Boolean
    get() = sp(this).getBoolean("shake_guard", true)
    set(v) = sp(this).edit().putBoolean("shake_guard", v).apply()

/** 拦截时轻提示 */
var Context.blockNotify: Boolean
    get() = sp(this).getBoolean("block_notify", true)
    set(v) = sp(this).edit().putBoolean("block_notify", v).apply()

/** 白名单包名集合 */
var Context.whitelist: Set<String>
    get() = sp(this).getStringSet("whitelist", emptySet()) ?: emptySet()
    set(v) = sp(this).edit().putStringSet("whitelist", v).apply()

/** 用户自定义规则原始 JSON */
var Context.customRulesJson: String?
    get() = sp(this).getString("custom_rules", null)
    set(v) = sp(this).edit().putString("custom_rules", v).apply()
