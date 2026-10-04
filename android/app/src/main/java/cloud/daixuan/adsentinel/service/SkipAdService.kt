package cloud.daixuan.adsentinel.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import cloud.daixuan.adsentinel.R
import cloud.daixuan.adsentinel.data.BlockStats
import cloud.daixuan.adsentinel.data.autoSkip
import cloud.daixuan.adsentinel.data.blockNotify
import cloud.daixuan.adsentinel.data.shakeGuard
import cloud.daixuan.adsentinel.data.whitelist
import cloud.daixuan.adsentinel.engine.AdRule
import cloud.daixuan.adsentinel.engine.EngineBox

/**
 * 无障碍核心服务:
 *  1. 窗口切换 / 内容变化时,用规则引擎匹配「跳过」类按钮并自动点击;
 *  2. 摇一摇防护:页面出现「摇一摇」提示,或广告 SDK 广告页跳过点击失败时,
 *     到达兜底时限后自动按返回,防止手一抖就跳转到电商 / 游戏页面。
 */
class SkipAdService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        /** 已知广告 SDK 的窗口 Activity 类名前缀。 */
        private val AD_ACTIVITY_PATTERNS = listOf(
            Regex("^com\\.bytedance\\.sdk\\.openadsdk\\..*"),
            Regex("^com\\.byted\\.sdk\\.openadsdk\\..*"),
            Regex("^com\\.qq\\.e\\.ads\\..*"),
            Regex("^com\\.kwad\\..*"),
            Regex("^com\\.baidu\\.mobads\\..*"),
            Regex("^com\\.mbridge\\.msdk\\..*"),
            Regex("^com\\.mintegral\\..*"),
            Regex("^com\\.sigmob\\..*"),
            Regex("^com\\.unity3d\\.ads\\..*"),
            Regex("^com\\.applovin\\..*"),
            Regex("^com\\.inmobi\\.ads\\..*"),
            Regex("^cn\\.domob\\..*"),
            Regex("^com\\.domob\\..*"),
            Regex("^com\\.jd\\.ads\\..*"),
            Regex("^com\\.jingdong\\.ads\\..*"),
        )

        /** 页面出现这些文案时,视为摇一摇类广告页(即使广告渲染在宿主自己的 Activity 里)。 */
        private val SHAKE_HINTS = listOf(
            Regex("摇一摇"),
            Regex("摇动手机"),
            Regex("扭一扭"),
            Regex("(?i)shake"),
        )

        private const val SWEEP_INTERVAL_MS = 120L
        private const val MAX_NODES = 2500
        private const val MAX_PARENT_HOPS = 5
        private const val BACK_DELAY_HINT_MS = 1200L
        private const val BACK_DELAY_SDK_MS = 2500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val engine by lazy { EngineBox.get(applicationContext) }

    private var lastSweepAt = 0L
    private var lastWindowClass: String? = null
    private var lastWindowPkg: String? = null
    private var pendingBack: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        EngineBox.get(applicationContext)
        isRunning = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        cancelPendingBack()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in applicationContext.whitelist) {
            cancelPendingBack()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(pkg, event.className?.toString())
        }
    }

    private fun onWindowChanged(pkg: String, cls: String?) {
        cancelPendingBack()
        lastWindowPkg = pkg
        lastWindowClass = cls
        val result = sweep(pkg, cls, clickEnabled = applicationContext.autoSkip)
        maybeScheduleBack(pkg, cls, result)
    }

    private fun onContentChanged(pkg: String, cls: String?) {
        val now = System.currentTimeMillis()
        if (now - lastSweepAt < SWEEP_INTERVAL_MS) return
        lastSweepAt = now
        // TYPE_WINDOW_CONTENT_CHANGED 的 className 通常是 View 类名而非 Activity,
        // Activity 归属要以最近一次窗口切换事件记录的为准。
        val targetPkg = pkg.ifBlank { lastWindowPkg } ?: return
        val result = sweep(targetPkg, lastWindowClass, clickEnabled = applicationContext.autoSkip)
        if (result.actions > 0) cancelPendingBack()
    }

    /** 摇一摇防护调度:本轮没有点掉任何东西时,安排一次兜底返回。 */
    private fun maybeScheduleBack(pkg: String, cls: String?, result: SweepResult) {
        if (!applicationContext.shakeGuard) return
        if (result.actions > 0) {
            cancelPendingBack()
            return
        }
        if (pendingBack != null) return

        val isSdkAd = cls != null && AD_ACTIVITY_PATTERNS.any { it.containsMatchIn(cls) }
        if (!result.shakeHint && !isSdkAd) return
        // 只有出现摇一摇提示时才对宿主自己的窗口做兜底返回;纯 SDK 广告页
        // (例如激励视频)只在「跳过点击失败」时返回,避免打断用户主动观看。
        if (!result.shakeHint && !result.skipFailed) return

        val delay = if (result.shakeHint) BACK_DELAY_HINT_MS else BACK_DELAY_SDK_MS
        val task = Runnable {
            pendingBack = null
            val stillSame = lastWindowPkg == pkg && lastWindowClass == cls
            if (!stillSame) return@Runnable
            // 最后一刻再试一次点击,能点掉就不返回
            val retry = sweep(pkg, cls, clickEnabled = applicationContext.autoSkip)
            if (retry.actions > 0) return@Runnable
            performGlobalAction(GLOBAL_ACTION_BACK)
            BlockStats.record(applicationContext)
            if (applicationContext.blockNotify) {
                Toast.makeText(this, getString(R.string.back_block_fmt, "摇一摇广告页"), Toast.LENGTH_SHORT).show()
            }
        }
        pendingBack = task
        handler.postDelayed(task, delay)
    }

    private fun cancelPendingBack() {
        pendingBack?.let { handler.removeCallbacks(it) }
        pendingBack = null
    }

    // ------------------------------------------------------------------ 匹配与点击

    private data class SweepResult(
        val actions: Int,
        val shakeHint: Boolean,
        val skipFailed: Boolean,
    )

    /**
     * 遍历当前活动窗口节点树,执行规则。
     * @param clickEnabled 为 false 时只做检测(供单独开启摇一摇防护的用户使用),不执行点击。
     */
    private fun sweep(pkg: String, cls: String?, clickEnabled: Boolean): SweepResult {
        val rules = engine.activeRules(pkg, cls)
        val root = rootInActiveWindow ?: return SweepResult(0, false, false)

        var shakeHint = false
        var skipFailed = false
        val hits = LinkedHashMap<String, Pair<AdRule, MutableList<AccessibilityNodeInfo>>>()

        var scanned = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && scanned < MAX_NODES) {
            val node = queue.removeFirst()
            scanned++
            if (!shakeHint) {
                val t = node.text?.toString()
                if (t != null && SHAKE_HINTS.any { it.containsMatchIn(t) }) shakeHint = true
            }
            if (clickEnabled) {
                for (rule in rules) {
                    if (node.matches(rule)) {
                        hits.getOrPut(rule.id) { rule to mutableListOf() }.second.add(node)
                        break
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        if (!clickEnabled) return SweepResult(0, shakeHint, false)

        var actions = 0
        for ((_, pair) in hits) {
            val (rule, nodes) = pair
            if (!engine.passesCooldown(rule, pkg)) continue
            var done = false
            when (rule.action) {
                AdRule.ACTION_BACK -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    done = true
                }

                AdRule.ACTION_CLICK_ALL -> {
                    for (n in nodes) if (clickNode(n)) done = true
                }

                else -> {
                    done = clickNode(nodes.first())
                    if (!done) skipFailed = true
                }
            }
            if (done) {
                engine.markFired(rule, pkg)
                actions++
                BlockStats.record(applicationContext)
                if (applicationContext.blockNotify) {
                    Toast.makeText(this, getString(R.string.toast_block_fmt, rule.name), Toast.LENGTH_SHORT).show()
                }
            }
        }
        return SweepResult(actions, shakeHint, skipFailed)
    }

    private fun AccessibilityNodeInfo.matches(rule: AdRule): Boolean {
        val id = try { viewIdResourceName } catch (_: Exception) { null }
        val text = try { text?.toString() } catch (_: Exception) { null }
        val desc = try { contentDescription?.toString() } catch (_: Exception) { null }
        return rule.matchesNode(text, desc, id)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var parent = node.parent
        var hops = 0
        while (parent != null && hops < MAX_PARENT_HOPS) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val next = parent.parent
            hops++
            parent = next
        }
        return tapByGesture(node)
    }

    /** 控件本身不可点、父级也不可点时,用无障碍手势模拟一次点按(minSdk 24 起可用)。 */
    private fun tapByGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val screen = resources.displayMetrics
        if (rect.centerX() < 0 || rect.centerX() > screen.widthPixels) return false
        if (rect.centerY() < 0 || rect.centerY() > screen.heightPixels) return false

        val path = Path().apply {
            moveTo(rect.exactCenterX(), rect.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
