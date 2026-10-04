package cloud.daixuan.adsentinel.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * 无障碍核心服务,针对各种形态的开屏广告:
 *  1. 窗口切换 / 内容变化时用规则引擎匹配「跳过」类按钮并自动点击;
 *  2. 主动监听(AdWatch):识别为广告窗口后的 8 秒内每 300ms 周期补扫,
 *     覆盖倒计时结束后才出现的按钮、WebView 内渲染的按钮等晚出现场景;
 *  3. 摇一摇防护:页面出现「摇一摇」等文案时兜底自动返回;若仍失败,
 *     尝试点按右上角(图片型跳过按钮的惯例位置);
 *  4. 非激励类 SDK 广告页无按钮时自动返回;激励视频(用户主动观看)排除保护。
 */
class SkipAdService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        /**
         * 已知广告 SDK 的窗口 Activity 类名前缀。
         * 注意:与 assets/rules/builtin.json 中 ad-sdk 规则的 activity 列表保持同步。
         */
        private val AD_ACTIVITY_PATTERNS = listOf(
            Regex("^com\\.bytedance\\.sdk\\.openadsdk\\..*"),   // 穿山甲 / Pangle / GroMore
            Regex("^com\\.byted\\.sdk\\.openadsdk\\..*"),
            Regex("^com\\.qq\\.e\\.ads\\..*"),                  // 优量汇 / 广点通
            Regex("^com\\.kwad\\..*"),                          // 快手联盟
            Regex("^com\\.baidu\\.mobads\\..*"),                // 百度
            Regex("^com\\.mbridge\\.msdk\\..*"),                // Mintegral
            Regex("^com\\.mintegral\\..*"),
            Regex("^com\\.sigmob\\..*"),
            Regex("^com\\.unity3d\\.ads\\..*"),                 // Unity Ads
            Regex("^com\\.applovin\\..*"),                      // AppLovin
            Regex("^com\\.inmobi\\.ads\\..*"),
            Regex("^com\\.vungle\\..*"),
            Regex("^com\\.ironsource\\..*"),                    // ironSource / Unity LevelPlay
            Regex("^com\\.adcolony\\..*"),
            Regex("^com\\.tapjoy\\..*"),
            Regex("^com\\.chartboost\\..*"),
            Regex("^com\\.fyber\\..*"),
            Regex("^com\\.facebook\\.ads\\..*"),                // Meta Audience Network
            Regex("^com\\.google\\.android\\.gms\\.ads\\..*"),  // AdMob / Google Mobile Ads
            Regex("^com\\.anythink\\..*"),                      // TopOn / AnyThink
            Regex("^com\\.beizi\\..*"),                         // 倍孜
            Regex("^com\\.adview\\..*"),
            Regex("^cn\\.domob\\..*"),                          // 多盟
            Regex("^com\\.domob\\..*"),
            Regex("^com\\.jd\\.ads\\..*"),                      // 京东
            Regex("^com\\.jingdong\\.ads\\..*"),
            Regex("^com\\.miui\\.zeus\\..*"),                   // 小米
            Regex("^com\\.opos\\..*"),                          // OPPO
            Regex("^com\\.hihonor\\.ads\\..*"),                 // 荣耀
            Regex("^com\\.huawei\\.hms\\.ads\\..*"),            // 华为
        )

        /** 激励视频 / 带奖广告类名特征:用户主动观看,绝不自动返回或盲点。 */
        private val REWARD_EXCLUDE = Regex("(?i)reward|incentiv|video|livestream")

        /** 宿主自己的开屏 / 欢迎页类名(嵌入式开屏广告渲染在这些页面里)。 */
        private val SPLASH_CLASS_PATTERNS = listOf(
            Regex("[Ss]plash"),
            Regex("[Ww]elcome"),
        )

        /** 页面出现这些文案即视为摇一摇类交互广告页(即使渲染在宿主自己的 Activity 里)。 */
        private val SHAKE_HINTS = listOf(
            Regex("摇一摇"),
            Regex("摇动"),
            Regex("扭一扭"),
            Regex("晃动"),
            Regex("滑一滑"),
            Regex("吹一吹"),
            Regex("拍一拍"),
            Regex("(?i)shake"),
        )

        private const val SWEEP_INTERVAL_MS = 120L
        private const val WATCH_INTERVAL_MS = 300L
        private const val WATCH_DURATION_MS = 8000L
        private const val CORNER_TAP_AFTER_MS = 1500L
        private const val BACK_DELAY_HINT_MS = 1200L
        private const val BACK_DELAY_SDK_MS = 2500L
        private const val MAX_NODES = 2500
        private const val MAX_PARENT_HOPS = 5

        /** zone=topRight 的区域边界(中心点相对屏幕的比例 / 控件最大尺寸比例)。 */
        private const val ZONE_MIN_X_RATIO = 0.55
        private const val ZONE_MAX_Y_RATIO = 0.30
        private const val ZONE_MAX_W_RATIO = 0.40
        private const val ZONE_MAX_H_RATIO = 0.20
    }

    private val handler = Handler(Looper.getMainLooper())
    private val engine by lazy { EngineBox.get(applicationContext) }

    private var lastSweepAt = 0L
    private var lastWindowClass: String? = null
    private var lastWindowPkg: String? = null
    private var pendingBack: Runnable? = null

    // AdWatch 状态
    private var watchActive = false
    private var watchPkg: String? = null
    private var watchCls: String? = null
    private var watchStartAt = 0L
    private var watchShakeHint = false
    private var watchCornerTapped = false

    private val watchTask = object : Runnable {
        override fun run() {
            if (!watchActive) return
            val pkg = watchPkg
            val cls = watchCls
            if (pkg == null || lastWindowPkg != pkg || lastWindowClass != cls) {
                stopWatch()
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - watchStartAt
            if (elapsed > WATCH_DURATION_MS) {
                stopWatch()
                return
            }
            if (applicationContext.autoSkip) {
                val result = sweep(pkg, cls, clickEnabled = true)
                if (result.actions > 0) cancelPendingBack()
                if (result.shakeHint) watchShakeHint = true
                maybeCornerTap(elapsed)
            }
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

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
        stopWatch()
        cancelPendingBack()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in applicationContext.whitelist) {
            // 白名单应用:必须彻底停掉此前为其他应用启动的监听,
            // 否则遗留的 watchTask 会拿着旧应用的状态继续扫描/点击,
            // 而此时 rootInActiveWindow 已是白名单应用的窗口,构成绕过
            cancelPendingBack()
            stopWatch()
            lastWindowPkg = null
            lastWindowClass = null
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(pkg)
        }
    }

    private fun onWindowChanged(pkg: String, cls: String?) {
        cancelPendingBack()
        stopWatch()
        lastWindowPkg = pkg
        lastWindowClass = cls

        val result = sweep(pkg, cls, clickEnabled = applicationContext.autoSkip)
        maybeScheduleBack(pkg, cls, result)
        if (isWatchWorthy(result.shakeHint, cls)) startWatch(pkg, cls, result.shakeHint)
    }

    private fun onContentChanged(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSweepAt < SWEEP_INTERVAL_MS) return
        lastSweepAt = now
        val targetPkg = pkg.ifBlank { lastWindowPkg } ?: return
        val cls = lastWindowClass
        val result = sweep(targetPkg, cls, clickEnabled = applicationContext.autoSkip)
        if (result.actions > 0) cancelPendingBack()
        if (isWatchWorthy(result.shakeHint, cls) && !watchActive) {
            // 内容变化才暴露出广告特征(如 WebView 渲染完成),补开主动监听
            startWatch(targetPkg, cls, result.shakeHint)
        }
    }

    private fun isWatchWorthy(shakeHint: Boolean, cls: String?): Boolean {
        if (shakeHint) return true
        if (cls == null) return false
        return AD_ACTIVITY_PATTERNS.any { it.containsMatchIn(cls) } ||
            SPLASH_CLASS_PATTERNS.any { it.containsMatchIn(cls) }
    }

    private fun startWatch(pkg: String, cls: String?, shakeHint: Boolean) {
        stopWatch()
        watchActive = true
        watchPkg = pkg
        watchCls = cls
        watchStartAt = SystemClock.elapsedRealtime()
        watchShakeHint = shakeHint
        watchCornerTapped = false
        handler.postDelayed(watchTask, WATCH_INTERVAL_MS)
    }

    private fun stopWatch() {
        watchActive = false
        handler.removeCallbacks(watchTask)
    }

    /**
     * 摇一摇页面的图片型跳过按钮没有文字 / 描述可匹配,但几乎总在右上角。
     * 在确认是摇一摇广告页且常规规则持续失败后,对手势点按一次右上角小型控件。
     */
    private fun maybeCornerTap(elapsedMs: Long) {
        if (!applicationContext.shakeGuard) return
        if (!watchShakeHint || watchCornerTapped) return
        if (elapsedMs < CORNER_TAP_AFTER_MS) return
        watchCornerTapped = true

        val root = rootInActiveWindow ?: return
        val screen = resources.displayMetrics
        val zoneLeft = screen.widthPixels * 0.70
        val zoneBottom = screen.heightPixels * 0.30
        val maxW = screen.widthPixels * 0.35
        val maxH = screen.heightPixels * 0.15

        val rect = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_NODES) {
            val node = queue.removeFirst()
            scanned++
            node.getBoundsInScreen(rect)
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            if (rect.width() in 1..maxW.toInt() && rect.height() in 1..maxH.toInt() &&
                cx >= zoneLeft && cy <= zoneBottom
            ) {
                if (tapByGesture(node)) {
                    BlockStats.record(applicationContext)
                    if (applicationContext.blockNotify) {
                        Toast.makeText(this, getString(R.string.toast_block_fmt, "右上角跳过(摇一摇广告)"), Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    /** 摇一摇防护调度:本轮没有点掉任何东西时,安排一次兜底返回。 */
    private fun maybeScheduleBack(pkg: String, cls: String?, result: SweepResult) {
        if (!applicationContext.shakeGuard) return
        if (result.actions > 0) {
            cancelPendingBack()
            return
        }
        if (pendingBack != null || cls == null) return

        val isSdkAd = AD_ACTIVITY_PATTERNS.any { it.containsMatchIn(cls) }
        val isSplash = SPLASH_CLASS_PATTERNS.any { it.containsMatchIn(cls) }
        val isReward = REWARD_EXCLUDE.containsMatchIn(cls)

        val delay = when {
            result.shakeHint -> BACK_DELAY_HINT_MS
            // 非激励类 SDK 广告页:即使没有匹配到按钮也兜底返回(覆盖无按钮插屏)
            isSdkAd && !isReward -> BACK_DELAY_SDK_MS
            // 宿主开屏页:仅在发现过跳过类控件但点击失败时返回,避免误退正常启动
            isSplash && result.skipFailed -> BACK_DELAY_SDK_MS
            else -> return
        }

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
        // 扫描/点击前重新核对:当前活动窗口必须仍属于目标应用,且不在白名单内。
        // 防止切换应用后遗留的定时任务拿着过期状态在新窗口里执行点击
        val rootPkg = root.packageName?.toString()
        if (rootPkg.isNullOrBlank() || rootPkg != pkg || rootPkg in applicationContext.whitelist) {
            return SweepResult(0, false, false)
        }

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
                    if (rule.zone != null && !nodeInTopRight(node)) continue
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

    private val zoneRect = Rect()

    /** zone="topRight":节点须是位于屏幕右上角的小尺寸控件(跳过按钮的惯例位置)。 */
    private fun nodeInTopRight(node: AccessibilityNodeInfo): Boolean {
        node.getBoundsInScreen(zoneRect)
        if (zoneRect.isEmpty) return false
        val screen = resources.displayMetrics
        val cx = zoneRect.exactCenterX()
        val cy = zoneRect.exactCenterY()
        if (cx < screen.widthPixels * ZONE_MIN_X_RATIO) return false
        if (cy > screen.heightPixels * ZONE_MAX_Y_RATIO) return false
        if (zoneRect.width() > screen.widthPixels * ZONE_MAX_W_RATIO) return false
        if (zoneRect.height() > screen.heightPixels * ZONE_MAX_H_RATIO) return false
        return true
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
