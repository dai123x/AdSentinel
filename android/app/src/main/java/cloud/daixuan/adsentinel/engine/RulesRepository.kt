package cloud.daixuan.adsentinel.engine

import android.content.Context
import cloud.daixuan.adsentinel.data.customRulesJson

/** 内置规则与用户自定义规则的加载 / 导入。 */
object RulesRepository {

    private const val BUILTIN_ASSET = "rules/builtin.json"

    fun loadBuiltin(ctx: Context): RuleSet =
        ctx.assets.open(BUILTIN_ASSET).bufferedReader(Charsets.UTF_8).use {
            RuleParser.parse(it.readText())
        }

    /** 加载用户自定义规则;损坏时返回 null(不影响内置规则)。 */
    fun loadCustom(ctx: Context): RuleSet? {
        val json = ctx.customRulesJson ?: return null
        return try {
            RuleParser.parse(json)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 导入自定义规则(整包 JSON,格式同内置规则),成功返回规则条数。
     * 解析失败抛 [IllegalArgumentException],消息可直接展示给用户。
     */
    fun import(ctx: Context, json: String): Int {
        val set = RuleParser.parse(json)
        require(set.rules.isNotEmpty()) { "规则文件里没有任何规则" }
        ctx.customRulesJson = json
        EngineBox.reload(ctx)
        return set.rules.size
    }

    fun clearCustom(ctx: Context) {
        ctx.customRulesJson = null
        EngineBox.reload(ctx)
    }
}

/** 进程内唯一的规则引擎入口,服务连接时创建,设置界面导入规则后调用 [reload]。 */
object EngineBox {

    @Volatile
    private var engine: RuleEngine? = null

    @Synchronized
    fun get(ctx: Context): RuleEngine {
        return engine ?: RuleEngine(ctx.applicationContext).also {
            it.reload()
            engine = it
        }
    }

    fun reload(ctx: Context) {
        get(ctx).reload()
    }
}
