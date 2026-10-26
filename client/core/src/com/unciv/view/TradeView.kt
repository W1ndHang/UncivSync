package com.unciv.view

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import com.unciv.json.json
import com.unciv.ui.screens.worldscreen.FrameSync
import yairm210.purity.annotations.Readonly

/** View of a trade negotiation between [civ] (always the viewer's own civ) and [otherCiv].
 *  Always built fresh, same as [TradeLogic] itself - staged offers live in this instance only. */
class TradeView(private val civ: Civilization, private val otherCiv: Civilization, gameView: GameView) : GameBasedView<Civilization>(civ, civ, gameView = gameView) {
    private val tradeLogic = TradeLogic(civ, otherCiv)

    // Data retrieval - lists are the live mutable instances, callers may add/remove offers directly
    @Readonly fun ourAvailableOffers(): TradeOffersList = tradeLogic.ourAvailableOffers
    @Readonly fun theirAvailableOffers(): TradeOffersList = tradeLogic.theirAvailableOffers
    @Readonly fun ourStagedOffers(): TradeOffersList = tradeLogic.currentTrade.ourOffers
    @Readonly fun theirStagedOffers(): TradeOffersList = tradeLogic.currentTrade.theirOffers
    @Readonly fun hasPendingOfferFromUs(): Boolean =
        otherCiv.tradeRequests.any { it.requestingCiv == civ.civID }

    // Actions - staging
    fun setStagedTrade(trade: Trade) = tradeLogic.currentTrade.set(trade)

    /** Restores a trade we already sent to [otherCiv] (if any) into staging, so it can be re-displayed or retracted. */
    fun tryLoadOurPendingOffer(): Boolean {
        val existing = otherCiv.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return false
        tradeLogic.currentTrade.set(existing.trade.reverse())
        return true
    }

    // Actions - proposing
    fun tryProposeStagedTrade(): Boolean {
        if (tradeLogic.currentTrade.ourOffers.isEmpty() && tradeLogic.currentTrade.theirOffers.isEmpty()) return false
        val reversed = tradeLogic.currentTrade.reverse()
        // UncivSync: 玩家→玩家交易实时上报（服务端权威）。Trade 序列化成 JSON 一起发，
        // 服务端写进对方的 tradeRequests 并广播 —— 对方屏幕上会自动弹出交易窗口。
        FrameSync.sendTradeOffer(otherCiv.civName, json().toJson(reversed))
        // Keep the local optimistic mirror one-request-per-source as well. Without this,
        // replacing an offer or sending a counter-offer leaves the old entry in the local
        // list until the next sim, so the original trade window comes back.
        otherCiv.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        otherCiv.tradeRequests.add(TradeRequest(civ.civID, reversed))
        civ.cache.updateCivResources()
        return true
    }

    fun tryRetractOffer(): Boolean {
        if (!hasPendingOfferFromUs()) return false
        // UncivSync: 撤回也要上报（否则服务端/对方那边还留着请求）
        FrameSync.sendTradeRetract(otherCiv.civName)
        otherCiv.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        return true
    }
}
