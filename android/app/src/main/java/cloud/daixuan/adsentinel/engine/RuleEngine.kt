package cloud.daixuan.adsentinel.engine

import android.content.Context
import android.os.SystemClock

/** 规则引擎:按 包名 + 窗口类名 过滤规则,并做触发冷却。 */
class RuleEngine(private val context: Context) {

    @Volatile
    private var builtin: RuleSet? = null

    @Volatile
    private var custom: RuleSet? = null

    private val cooldowns = HashMap<String, Long>()

    @Synchronized
    fun reload() {
        custom = RulesRepository.loadCustom(context)
        builtin = try {
            RulesRepository.loadBuiltin(context)
        } catch (_: Exception) {
            null
        }
    }

    val ruleCount: Int
        get() = (custom?.rules?.size ?: 0) + (builtin?.rules?.size ?: 0)

    val builtinName: String
        get() = builtin?.let { "${it.name} v${it.version} (${it.updatedAt})" } ?: "内置规则加载失败"

    fun activeRules(pkg: String, activityClass: String?): List<AdRule> {
        val all = custom?.rules.orEmpty() + builtin?.rules.orEmpty()
        return all.filter { it.enabled && it.matchesApp(pkg) && it.matchesActivity(activityClass) }
    }

    fun passesCooldown(rule: AdRule, pkg: String): Boolean {
        val key = rule.id + "|" + pkg
        val now = SystemClock.elapsedRealtime()
        synchronized(cooldowns) {
            val last = cooldowns[key] ?: 0L
            return now - last >= rule.cooldownMs
        }
    }

    fun markFired(rule: AdRule, pkg: String) {
        val key = rule.id + "|" + pkg
        synchronized(cooldowns) {
            cooldowns[key] = SystemClock.elapsedRealtime()
        }
    }
}
