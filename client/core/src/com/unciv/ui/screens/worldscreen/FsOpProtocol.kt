package com.unciv.ui.screens.worldscreen

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit

/**
 * UncivSync op 框架（客户端侧）：稳定寻址 + 服务端权威（层 4）
 *
 * 第三十二批起**全面去乐观**：任何 op 本地都不预执行，状态一律等 sim 回推。
 * 因此这里不再有"执行模式 / 挂账表 / 回滚"的概念，只剩「稳定身份」这一件事。
 *
 * 所有 op 都通过 [FrameSync.sendUnitOp] / [FrameSync.sendCityOp] / [FrameSync.sendCivOp] 发出，
 * 不在 UI 层手拼 data 字典。
 */

/**
 * "结束回合之后不该再改"的 op —— 与服务端 `FsOpRegistry.TURN_LOCKED_OPS` **必须逐字一致**。
 *
 * 语义：玩家已经点过「结束回合」之后，这些 op 一律不发（服务端也会独立拒绝一遍）。
 * 这是 UncivGC 的 `sendOpChecked` 第③道门。移动 / 攻击不在名单里 —— 随时可发。
 */
val TURN_LOCKED_OPS: Set<String> = setOf(
    // 城市：生产 / 队列 / 地块 / 专家 / 焦点 / 人口
    "city.setProduction", "city.addToQueue", "city.addToQueueWithTile",
    "city.removeFromQueue", "city.removeFromQueueByName", "city.moveQueueEntry",
    "city.moveEntryToEnd",
    "city.raisePriority", "city.lowerPriority", "city.disableConstruction",
    "city.workTile", "city.stopWorkTile", "city.lockTile", "city.unlockTile",
    "city.assignSpecialist", "city.reassignPopulation", "city.setFocus",
    "city.enableManualSpecialists", "city.disableManualSpecialists",
    "city.toggleAvoidGrowth",
    // 城市：领土 / 存续 / 命名 / 生产偏好 / 金币购买
    "city.annex", "city.setPuppet", "city.setRazing", "city.buyTile", "city.buy", "city.sellBuilding", "city.rename",
    "city.setUnitSavedPromotion", "city.saveUnitPromotions",
    // 文明：全局生产禁用 / 科技 / 政策 / 信条
    "civ.setConstructionDisabled", "civ.chooseTech", "civ.choosePolicy",
    "civ.chooseBeliefs", "civ.setGoldToScience", "civ.runAutomation", "civ.setCityStateProtection", "civ.cityStatePeace"
)

/**
 * 单位的稳定身份（跨客户端/服务端一致）。
 *
 * [id] 是主键（阶段 1 之后服务端与客户端下载同一份存档，id 天然一致）；
 * [civ] + [type] + 坐标仅用于诊断和无 id 的旧协议，不得覆盖一个已提供但无效的主键。
 */
class FsUnitRef(
    val id: Int,
    val civ: String,
    val x: Int?,
    val y: Int?,
    val type: String,
) {
    fun toData(): MutableMap<String, Any> {
        val data = LinkedHashMap<String, Any>()
        data["unitId"] = id
        data["civ"] = civ
        data["unitType"] = type
        if (x != null && y != null) {
            data["fromX"] = x
            data["fromY"] = y
        }
        return data
    }

    companion object {
        fun of(unit: MapUnit): FsUnitRef {
            val pos = unit.currentTile?.position
            return FsUnitRef(unit.id, unit.civ.civName, pos?.x, pos?.y, unit.baseUnit.name)
        }
    }
}

/** 城市的稳定身份：id 为主键，坐标+名称兜底。 */
class FsCityRef(
    val id: String,
    val name: String,
    val civ: String,
    val x: Int?,
    val y: Int?,
) {
    fun toData(): MutableMap<String, Any> {
        val data = LinkedHashMap<String, Any>()
        data["cityId"] = id
        data["cityName"] = name
        data["civ"] = civ
        if (x != null && y != null) {
            data["x"] = x
            data["y"] = y
        }
        return data
    }

    companion object {
        fun of(city: City): FsCityRef {
            val pos = city.location
            return FsCityRef(city.id, city.name, city.civ.civName, pos.x, pos.y)
        }
    }
}

