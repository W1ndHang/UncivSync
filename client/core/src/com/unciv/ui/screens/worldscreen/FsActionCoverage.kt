package com.unciv.ui.screens.worldscreen

import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType

/** 纯协议映射。新增枚举必须在映射或带原因的例外表里显式登记。 */
object FsActionCoverage {
    val operations = mapOf(
        UnitActionType.Fortify to "unit.fortify",
        UnitActionType.FortifyUntilHealed to "unit.fortifyUntilHealed",
        UnitActionType.Sleep to "unit.sleep",
        UnitActionType.SleepUntilHealed to "unit.sleepUntilHealed",
        UnitActionType.Automate to "unit.automate",
        UnitActionType.StopAutomation to "unit.stopAutomation",
        UnitActionType.DisbandUnit to "unit.disband",
        UnitActionType.FoundCity to "unit.foundCity",
        UnitActionType.SetUp to "unit.setUp",
        UnitActionType.Guard to "unit.guard",
        UnitActionType.HurryResearch to "unit.hurryResearch",
        UnitActionType.HurryPolicy to "unit.hurryPolicy",
        UnitActionType.HurryWonder to "unit.hurryWonder",
        UnitActionType.HurryBuilding to "unit.hurryBuilding",
        UnitActionType.FoundReligion to "unit.foundReligion",
        UnitActionType.EnhanceReligion to "unit.enhanceReligion",
        UnitActionType.Repair to "unit.repair",
        UnitActionType.Paradrop to "unit.paradrop",
        UnitActionType.AirSweep to "unit.airSweep",
        UnitActionType.AddInCapital to "unit.addInCapital",
        UnitActionType.RemoveHeresy to "unit.removeHeresy",
        UnitActionType.Skip to "unit.skip",
        UnitActionType.StopMovement to "unit.stopMovement",
        UnitActionType.StopExploration to "unit.stopMovement",
        UnitActionType.Explore to "unit.explore",
        UnitActionType.EscortFormation to "unit.escort",
        UnitActionType.StopEscortFormation to "unit.stopEscort",
        UnitActionType.TriggerUnique to "unit.triggerUnique",
        UnitActionType.Transform to "unit.transform",
        UnitActionType.SpreadReligion to "unit.spreadReligion",
        UnitActionType.ConductTradeMission to "unit.tradeMission",
    )

    val localRoutes = mapOf(
        UnitActionType.SwapUnits to "仅选择目标，WorldMapHolder 确认后发送换位操作",
        UnitActionType.ConnectRoad to "仅切换选路模式，OverlayButtonData 确认目标后发包",
        UnitActionType.ShowUnitDestination to "仅移动镜头，不改游戏状态",
        UnitActionType.ShowAdditionalActions to "仅翻动作面板页",
        UnitActionType.HideAdditionalActions to "仅翻动作面板页",
        UnitActionType.Promote to "打开 PromotionPickerScreen，由确认按钮发晋升操作",
        UnitActionType.Upgrade to "UnitActionsUpgrade 的确认回调含独立 FS 分支",
        UnitActionType.Pillage to "UnitActionsPillage 的确认回调含独立 FS 分支",
        UnitActionType.ConstructImprovement to "打开 ImprovementPickerScreen，由确认按钮发建造操作",
        UnitActionType.CreateImprovement to "UnitActionsFromUniques 回调携带具体改良和 unique 发包",
        UnitActionType.GiftUnit to "UnitActions 的赠送确认回调含独立 FS 分支",
    )

    fun opFor(action: UnitAction): Pair<String, Map<String, Any>>? {
        val op = operations[action.type] ?: return null
        val extra = when (action.type) {
            UnitActionType.TriggerUnique, UnitActionType.Transform ->
                action.associatedUnique?.text?.let { mapOf<String, Any>("unique" to it) } ?: emptyMap()
            else -> emptyMap()
        }
        return op to extra
    }
}
