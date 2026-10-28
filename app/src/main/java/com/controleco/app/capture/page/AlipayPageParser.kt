package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money

/**
 * 支付宝（com.eg.android.AlipayGphone）页面解析器。
 *
 * **只保留有支付宝真机样本的动账页面**：付款成功页，以及账单交易详情中的明确成功支出。版式：
 *
 * 1. 实付与标题分节点：
 *    `支付成功 | 回首页 | ￥ | 9.17 | 获得森林能量 | 鸿润福连锁超市 | ￥9.27 | 碰一下立减 | -￥0.10 | 付款方式 | 中国银行储蓄卡(****) …`
 * 2. 实付与标题同节点：
 *    `支付成功￥19.70 | 支付成功 | 19.70 | 订单金额 | ￥22.20 | 红包 | -￥2.50 | 付款方式 | 支付宝小荷包(…) …`
 * 3. 账单交易详情：
 *    `商户全称 | 支出29.49元 | 交易成功 | 订单金额 | 29.50 | ... | 支付时间 | 2026-10-26 11:04:05`
 *
 * 判据只有三条：
 * - 结果页第一段可见文字必须以 `支付成功` 开头；详情页必须同时有 `交易成功`、明确支出金额、
 *   支付时间和付款方式；
 * - 能取到标题内金额，或标题后的第一个金额（支持 `￥` 与数字拆成两个节点）；
 * - 详情页金额只取 `支出...元`，不取订单金额或优惠；交易时间参与去重。
 *
 * **不识别**首页、列表、收银台、失败/取消、收款、转账、退款及缺少明确成功支出字段的详情页
 * （收入/退款由通知与短信通道负责）。
 * 新增任何页面锚点/字段前必须先在 docs/自动记账捕获机制.md 记录来源，无明确证据不加入。
 */
object AlipayPageParser : AppPageParser {

    override val packages: Set<String> = setOf(NotificationRules.ALIPAY)
    override val label: String = "支付宝"

    private const val PAYMENT_SUCCESS = "支付成功"

    /**
     * 旧版成功页形态中的历史详情拒绝标记。明确成功支出详情在此前已按独立字段解析。
     *
     * 这些标记来自旧版生产规则的 `billDetailKeywords`、成功页“当前状态”守卫及详情字段，
     * 避免把正文里仅提到“支付成功”的历史详情误认成新的结果页。
     */
    private val historyDetailMarkers = listOf(
        "账单详情", "交易详情", "当前状态", "创建时间", "交易单号",
    )

    private val detailTimeLabels = listOf("支付时间", "付款时间", "交易时间")

    private val detailAmountAfterExpense = Regex(
        """[￥¥]?\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)\s*元""",
    )

    /**
     * 成功页明细区标记：从这些标签开始，后面的金额不是实付。
     * 出处：真实样本的 `订单金额`、`付款方式`、`红包`，以及收银台/优惠样本里的 `优惠`、`立减`。
     */
    private val breakdownMarkers = listOf("订单金额", "付款方式", "优惠", "立减", "红包")

    /**
     * 成功页徽章/按钮：与商户名同形，找商户时剔除。
     * 出处：2026-10-18 真实样本（获得森林能量/恭喜你获得/去领取/碰一下立减）。
     */
    private val badgeWords = listOf("获得森林能量", "恭喜你获得", "去领取", "碰一下立减")

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        if (isSuccessfulExpenseDetail(lines)) return parseSuccessfulExpenseDetail(snapshot, flow)

        val first = lines.firstOrNull()?.trim().orEmpty()
        if (!first.startsWith(PAYMENT_SUCCESS)) return PageParseResult.NotATradePage

        // 历史账单详情页：正文里的“支付成功”是状态字段，不是新的一笔
        if (historyDetailMarkers.any { snapshot.joined.contains(it) }) return PageParseResult.NotATradePage

        val amountText = firstPaymentAmount(lines)
            ?: return PageParseResult.SceneOnly(
                PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页无金额",
            )
        val amountCents = Money.parseCents(amountText)?.takeIf { it > 0 }
            ?: return PageParseResult.SceneOnly(
                PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页金额非法",
            )

        val instrument = paymentInstrumentOf(lines)
        val seq = flow.markEmitted("ali-success|$amountCents|${snapshot.fingerprint}")
        if (seq < 0) {
            flow.noteSuccess(instrument)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页重复事件")
        }
        flow.noteSuccess(instrument)

        val counterparty = merchantOf(lines)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.EXPENSE,
                amountCents = amountCents,
                counterparty = counterparty,
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = null,
                scene = PageScene.PAYMENT_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = if (counterparty.isNotBlank()) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·付款成功页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                amountAnchored = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, snapshot.captureTime,
                ),
            ),
        )
    }

    private fun isSuccessfulExpenseDetail(lines: List<String>): Boolean =
        lines.any { it.contains("交易成功") } &&
                lines.any { it.contains("支出") } &&
                lines.any { line -> detailTimeLabels.any(line::contains) } &&
                lines.any { it.contains("付款方式") }

    private fun parseSuccessfulExpenseDetail(
        snapshot: PageSnapshot,
        flow: PageFlow,
    ): PageParseResult {
        val lines = snapshot.lines
        val txTime = PageParseSupport.timeFromPage(snapshot)
            ?: return PageParseResult.SceneOnly(
                PageScene.TRANSACTION_DETAIL, TradeState.SUCCESS, "支付宝交易详情页无支付时间",
            )
        val amountText = expenseAmount(lines)
            ?: return PageParseResult.SceneOnly(
                PageScene.TRANSACTION_DETAIL, TradeState.SUCCESS, "支付宝交易详情页无明确支出金额",
            )
        val amountCents = Money.parseCents(amountText)?.takeIf { it > 0 }
            ?: return PageParseResult.SceneOnly(
                PageScene.TRANSACTION_DETAIL, TradeState.SUCCESS, "支付宝交易详情页支出金额非法",
            )
        val orderId = PageParseSupport.orderIdFrom(lines)
        val identity = orderId ?: txTime.toString()
        if (flow.markEmitted("ali-detail|$amountCents|$identity") < 0) {
            return PageParseResult.SceneOnly(
                PageScene.TRANSACTION_DETAIL, TradeState.SUCCESS, "支付宝交易详情页重复事件",
            )
        }

        val instrument = paymentInstrumentOf(lines)
        flow.noteSuccess(instrument)
        val counterparty = PageParseSupport.valueAfterLabel(lines, PageParseSupport.counterpartyLabels)
            ?: lines.firstOrNull()?.substringBefore("支出")?.trim()?.takeIf { it.isNotBlank() }
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.EXPENSE,
                amountCents = amountCents,
                counterparty = counterparty.orEmpty().take(60),
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.TRANSACTION_DETAIL,
                tradeState = TradeState.SUCCESS,
                confidence = if (counterparty.isNullOrBlank()) ParseConfidence.MEDIUM else ParseConfidence.HIGH,
                sourceLabel = label,
                ruleName = "$label·交易详情",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
                amountAnchored = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, txTime,
                ),
            ),
        )
    }

    private fun expenseAmount(lines: List<String>): String? {
        for ((index, line) in lines.withIndex()) {
            val labelIndex = line.indexOf("支出")
            if (labelIndex < 0) continue
            val afterLabel = line.substring(labelIndex + "支出".length)
            detailAmountAfterExpense.find(afterLabel)?.groupValues?.get(1)?.let { return it }
            if (afterLabel.isBlank()) {
                detailAmountAfterExpense.find(lines.getOrNull(index + 1).orEmpty())
                    ?.groupValues?.get(1)
                    ?.let { return it }
            }
        }
        return null
    }

    /**
     * 实付金额：标题行内金额 → 标题后第一个金额。
     *
     * 版式 2 的标题就是 `支付成功￥19.70`；版式 1 的实付被拆成 `￥` 与 `9.17` 两个节点，
     * 而订单原价 `￥9.27`、立减 `-￥0.10` 都在其后。无货币符号的裸金额要求带小数点，
     * 避免把徽章计数（如“600葵花籽”拆出的 600）当成金额。
     */
    private fun firstPaymentAmount(lines: List<String>): String? {
        PageParseSupport.amountInAnchor(lines.first())?.let { return it }
        var i = 1
        while (i < lines.size) {
            val text = lines[i].trim()
            if (text.isEmpty()) {
                i++
                continue
            }
            if (breakdownMarkers.any { text.contains(it) }) break
            if (text == "¥" || text == "￥") {
                amountToken(lines.getOrNull(i + 1).orEmpty())?.let { return it }
                i++
                continue
            }
            if (text.startsWith("¥") || text.startsWith("￥")) {
                PageParseSupport.amountInAnchor(text)?.let { return it }
            }
            amountToken(text, allowInteger = false)?.let { return it }
            i++
        }
        return null
    }

    /** 整行是纯金额节点时返回数值文本；负号开头的优惠项不算 */
    private fun amountToken(text: String, allowInteger: Boolean = true): String? {
        val t = text.trim()
        if (!PageParseSupport.isAmountLikeNode(t)) return null
        if (t.startsWith("-") || t.startsWith("−") || t.startsWith("－")) return null
        val cleaned = t.trimStart('¥', '￥', '+', ' ').trimEnd('元').trim()
        if (!allowInteger && !cleaned.contains('.')) return null
        return cleaned.takeIf { Money.parseCents(it)?.let { c -> c > 0 } == true }
    }

    /**
     * 无标签商户：从标题向后找第一个不是金额/按钮/明细标签的短文本。
     * 真实样本里商户紧跟实付金额（`鸿润福连锁超市`）；营销徽章先剔除。
     */
    private fun merchantOf(lines: List<String>): String {
        val filtered = lines.filterNot { line -> badgeWords.any { line.contains(it) } }
        val start = filtered.indexOfFirst { it.trim().startsWith(PAYMENT_SUCCESS) }
        if (start < 0) return ""
        for (i in start + 1 until minOf(filtered.size, start + 7)) {
            val text = filtered[i].trim()
            if (text.isEmpty()) continue
            if (text == "回首页" || text.startsWith(PAYMENT_SUCCESS)) continue
            if (breakdownMarkers.any { text.contains(it) }) break
            if (text.startsWith("¥") || text.startsWith("￥")) continue
            if (PageParseSupport.isAmountLikeNode(text)) continue
            return text.take(30)
        }
        return ""
    }

    /**
     * 支付方式：真实样本里 `付款方式` 独占一行，下一行是支付工具。
     * 不做多标签猜测；解析失败返回 null（宁可不匹配账户，也不猜）。
     */
    private fun paymentInstrumentOf(lines: List<String>): PaymentInstrument? {
        val index = lines.indexOfFirst { it.trim() == "付款方式" }
        if (index < 0) return null
        val raw = lines.getOrNull(index + 1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return PaymentInstrumentParser.parse(raw, label)
    }
}
