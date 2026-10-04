package cloud.daixuan.adsentinel.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则模型与 JSON 解析。
 *
 * 匹配语义:
 *  - 所有正则均为「部分匹配」(containsMatchIn),需要全匹配请自行加 ^ $;
 *  - text / contentDesc / viewId 之间是「或」关系,命中任一即视为命中节点;
 *  - viewId 若同时给出,则是额外的「且」条件;
 *  - activity 收窄当前窗口的 Activity 类名,apps 收窄宿主包名。
 */
data class AdRule(
    val id: String,
    val name: String,
    val apps: List<Regex>,
    val activities: List<Regex>?,
    val texts: List<Regex>?,
    val descs: List<Regex>?,
    val viewId: Regex?,
    val action: String,
    val cooldownMs: Long,
    val enabled: Boolean,
) {
    fun matchesApp(pkg: String): Boolean = apps.any { it.containsMatchIn(pkg) }

    fun matchesActivity(cls: String?): Boolean =
        activities == null || cls == null || activities.any { it.containsMatchIn(cls) }

    fun matchesNode(
        text: String?,
        desc: String?,
        viewIdResource: String?,
    ): Boolean {
        if (viewId != null) {
            val res = viewIdResource?.substringAfterLast('/') ?: return false
            if (!viewId.containsMatchIn(res)) return false
        }
        if (texts != null && text != null && texts.any { it.containsMatchIn(text) }) return true
        if (descs != null && desc != null && descs.any { it.containsMatchIn(desc) }) return true
        return false
    }

    companion object {
        const val ACTION_CLICK = "click"
        const val ACTION_CLICK_ALL = "clickAll"
        const val ACTION_BACK = "back"
    }
}

data class RuleSet(
    val version: Int,
    val name: String,
    val updatedAt: String,
    val rules: List<AdRule>,
)

object RuleParser {

    fun parse(json: String): RuleSet {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 格式错误:${e.message}")
        }
        val version = root.optInt("version", 1)
        val name = root.optString("name", "rules")
        val updatedAt = root.optString("updatedAt", "")
        val arr = root.optJSONArray("rules")
            ?: throw IllegalArgumentException("缺少 rules 数组")

        val rules = ArrayList<AdRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = try {
                arr.getJSONObject(i)
            } catch (e: Exception) {
                throw IllegalArgumentException("rules[${i}] 不是对象:${e.message}")
            }
            rules.add(parseRule(o, i))
        }
        return RuleSet(version, name, updatedAt, rules)
    }

    private fun parseRule(o: JSONObject, index: Int): AdRule {
        val id = o.optString("id", "")
        val name = o.optString("name", id)
        try {
            require(id.isNotBlank()) { "缺少 id" }

            val apps = compileList(o.optJSONArray("apps"))
                ?: throw IllegalArgumentException("缺少 apps")
            require(apps.isNotEmpty()) { "apps 不能为空" }

            val activities = compileList(o.optJSONArray("activity"))
            val texts = compileList(o.optJSONArray("text"))
            val descs = compileList(o.optJSONArray("contentDesc"))
            val viewId = compileOne(o.optString("viewId", "").ifBlank { null })

            if (texts == null && descs == null && viewId == null) {
                throw IllegalArgumentException("text / contentDesc / viewId 至少需要一项")
            }

            val action = o.optString("action", AdRule.ACTION_CLICK)
            require(
                action == AdRule.ACTION_CLICK ||
                    action == AdRule.ACTION_CLICK_ALL ||
                    action == AdRule.ACTION_BACK
            ) { "不支持的 action:$action" }

            val cooldown = o.optLong("cooldownMs", 800L).coerceIn(0L, 60_000L)

            return AdRule(
                id = id,
                name = name,
                apps = apps,
                activities = activities,
                texts = texts,
                descs = descs,
                viewId = viewId,
                action = action,
                cooldownMs = cooldown,
                enabled = o.optBoolean("enabled", true),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("rules[$index]「$name」:${e.message}")
        }
    }

    private fun compileList(arr: JSONArray?): List<Regex>? {
        if (arr == null || arr.length() == 0) return null
        val out = ArrayList<Regex>(arr.length())
        for (i in 0 until arr.length()) {
            val raw = arr.optString(i, "")
            if (raw.isBlank()) continue
            out += if (raw == "*") Regex(".*") else Regex(raw)
        }
        return out.ifEmpty { null }
    }

    private fun compileOne(raw: String?): Regex? {
        if (raw.isNullOrBlank()) return null
        return Regex(raw)
    }
}
