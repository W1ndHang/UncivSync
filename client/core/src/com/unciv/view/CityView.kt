package com.unciv.view

import com.unciv.logic.automation.Automation
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.StatTreeNode
import com.unciv.logic.city.CityFocus
import com.unciv.logic.city.CityResources
import com.unciv.logic.city.GreatPersonPointsBreakdown
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.screens.worldscreen.FrameSync
import yairm210.purity.annotations.Readonly

/** View of a [City] from the perspective of the viewer's [gameView]. UI should use this and not city directly.
 * This should only be for cities we can see as if we own them - our cities, spied cities, or if we're spectator */
class CityView(city: City,
               viewer: Civilization,
               spectatorMode: Boolean = false,
               gameView: GameView) : ForeignCityView(city, viewer, spectatorMode, gameView) {
    // Navigation
    /** The viewing player's full CivView (always a self-view). For the city's owning civ, use [owningCiv]. */
    @Readonly fun viewingCiv(): CivView = gameView.civView

    /** Cities the viewer can page through in CityScreen: own cities normally, or spy-visited cities when spying. */
    @Readonly fun getViewableCities(): List<CityView> {
        val isSpying = city.civ !== viewer && viewer.gameInfo.isEspionageEnabled() && !viewer.isSpectator()
        return if (isSpying) viewer.espionageManager.getCitiesWithOurSpies()
            .filter { it.civ != viewer }
            .map { gameView.getCityView(it) }
        else city.civ.cities.map { gameView.getCityView(it) }
    }

    // Data retrieval
    @Readonly fun isInRange(tileView: TileView): Boolean = tileView.unwrap() in city.tilesInRange

    @Readonly fun centerTile(): TileView = gameView.tileMapView.getTile(city.getCenterTile())
    @Readonly fun getTiles(): Sequence<TileView> = city.getTiles().map { gameView.tileMapView.getTile(it) }
    @Readonly fun tileView(tile: Tile): TileView = gameView.tileMapView.getTile(tile)

    @Readonly fun getWorkRange(): Int = city.getWorkRange()
    @Readonly fun isWorked(tileView: TileView): Boolean = city.isWorked(getTile(tileView))
    @Readonly fun canBuyTile(tileView: TileView): Boolean = city.expansion.canBuyTile(getTile(tileView))
    @Readonly fun getGoldCostOfTile(tileView: TileView, extraTiles: Int = 0): Int =
        city.expansion.getGoldCostOfTile(getTile(tileView), extraTiles)
    // Population
    @Readonly fun getFreePopulation(): Int = city.population.getFreePopulation()
    @Readonly fun getPopulationCount(): Int = city.population.population
    @Readonly fun getFoodStored(): Int = city.population.foodStored
    @Readonly fun getFoodToNextPopulation(): Int = city.population.getFoodToNextPopulation()
    @Readonly fun getMaxSpecialists(): Counter<String> = city.population.getMaxSpecialists()
    @Readonly fun getNewSpecialists(): Counter<String> = city.population.getNewSpecialists()
    val manualSpecialists: Boolean get() = city.manualSpecialists
    @Readonly fun getNumTurnsToStarvation(): Int? = city.population.getNumTurnsToStarvation()
    @Readonly fun getNumTurnsToNewPopulation(): Int? = city.population.getNumTurnsToNewPopulation()
    @Readonly fun foodForNextTurn(): Int = city.foodForNextTurn()
    @Readonly fun getStatsOfSpecialist(specialistName: String): Stats = city.cityStats.getStatsOfSpecialist(specialistName)

    // City state
    @Readonly fun isStarving(): Boolean = city.isStarving()
    @Readonly fun isGrowing(): Boolean = city.isGrowing()
    @Readonly fun isInResistance(): Boolean = city.isInResistance()
    @Readonly fun isWeLoveTheKingDayActive(): Boolean = city.isWeLoveTheKingDayActive()
    val demandedResource: String get() = city.demandedResource
    @Readonly fun getFlag(flag: CityFlags): Int = city.getFlag(flag)
    @Readonly fun getCityFocus(): CityFocus = city.getCityFocus()
    val avoidGrowth: Boolean get() = city.avoidGrowth
    @Readonly fun getState(): GameContext = city.state

    // Stats
    @Readonly fun getCurrentCityStats(): Stats = city.cityStats.currentCityStats
    @Readonly fun getHappinessList(): Map<String, Float> = city.cityStats.happinessList
    @Readonly fun getBaseStatTree(): StatTreeNode = city.cityStats.baseStatTree
    @Readonly fun getStatPercentBonusTree(): StatTreeNode = city.cityStats.statPercentBonusTree
    @Readonly fun getFinalStatList(): Map<String, Stats> = city.cityStats.finalStatList

    // Expansion
    @Readonly fun hasChoosableTiles(): Boolean = city.expansion.getChoosableTiles().any()
    @Readonly fun getCultureToNextTile(): Int = city.expansion.getCultureToNextTile()
    @Readonly fun getCultureStored(): Int = city.expansion.cultureStored

    // Constructions
    val constructions: CityConstructionsView get() = CityConstructionsView(city.cityConstructions, gameView, viewer, spectatorMode)
    @Readonly fun currentConstructionName(): String = city.cityConstructions.currentConstructionName()
    @Readonly fun getBuiltBuildings(): Sequence<Building> = city.cityConstructions.getBuiltBuildings()
    @Readonly fun isPuppet(): Boolean = city.isPuppet
    @Readonly fun hasMatchingUnique(uniqueType: UniqueType): Boolean = city.getMatchingUniques(uniqueType).any()
    @Readonly fun getDisabledConstructions(): Set<String> = city.disabledConstructions
    @Readonly fun isStatRelated(stat: Stat, building: Building): Boolean = building.isStatRelated(stat, city)
    @Readonly fun getProductionTooltip(construction: PerpetualConstruction): String = construction.getProductionTooltip(city)
    @Readonly fun getResourceRequirementsPerTurn(construction: IConstruction): Counter<String> =
        if (construction is BaseUnit) construction.getResourceRequirementsPerTurn(city.civ.state)
        else construction.getResourceRequirementsPerTurn(city.state)
    @Readonly fun getStockpiledResourceRequirements(construction: IConstruction): Counter<String> =
        construction.getStockpiledResourceRequirements(city.state)
    @Readonly fun getConstructionProductionCost(construction: INonPerpetualConstruction): Int =
        construction.getProductionCost(city.civ, city)
    @Readonly fun getUnitDescription(unit: BaseUnit): String = unit.getDescription(city)
    @Readonly fun getBuildingDescription(building: Building): String = building.getDescription(city, true)
    @Readonly fun getConversionRate(statConversion: PerpetualConstruction.StatConversion): Int = statConversion.getConversionRate(city)
    @Readonly fun getGoldForSellingBuilding(buildingName: String): Int = city.getGoldForSellingBuilding(buildingName)
    @Readonly fun hasSoldBuildingThisTurn(): Boolean = city.hasSoldBuildingThisTurn
    @Readonly fun isGodModeEnabled(): Boolean = city.civ.gameInfo.gameParameters.godMode
    @Readonly fun getUnitShouldUseSavedPromotion(baseUnit: String): Boolean? = city.unitShouldUseSavedPromotion[baseUnit]
    @Readonly fun getCityAmbienceSound(): String = city.civ.getEra().citySound
    @Readonly fun isBeingRazed(): Boolean = city.isBeingRazed
    @Readonly fun isCapital(): Boolean = city.isCapital()
    @Readonly fun getGarrison(): MapUnitView? = city.getGarrison()?.let { gameView.getMapUnitView(it) }
    @Readonly fun canBeDestroyed(): Boolean = city.canBeDestroyed()
    @Readonly fun getExpandRange(): Int = city.getExpandRange()
    @Readonly fun chooseNewTileToOwn(): TileView? = city.expansion.chooseNewTileToOwn()?.let { gameView.tileMapView.getTile(it) }
    @Readonly fun getImprovementToCreate(construction: Building): TileImprovement? =
        construction.getImprovementToCreate(city.getRuleset(), city.civ)
    @Readonly fun hasFreeBuilding(building: Building): Boolean =
        city.civ.civConstructions.hasFreeBuilding(city, building)

    // Resources/misc
    @Readonly fun getResourceStockpiles(): Counter<String> = city.resourceStockpiles
    @Readonly fun getCityResourcesAvailableToCity(): ResourceSupplyList = CityResources.getCityResourcesAvailableToCity(city)
    @Readonly fun getGreatPersonPointsBreakdown(): GreatPersonPointsBreakdown = GreatPersonPointsBreakdown(city)
    @Readonly fun getRuleset(): Ruleset = city.getRuleset()
    @Readonly fun getBuildingStats(building: Building): Stats = building.getStats(city)

    @Readonly fun getStatReserve(stat: Stat): Int = city.getStatReserve(stat)
    @Readonly fun canBePurchasedWithAnyStat(construction: INonPerpetualConstruction): Boolean =
        construction.canBePurchasedWithAnyStat(city)
    @Readonly fun canBePurchasedWithStat(construction: INonPerpetualConstruction, stat: Stat): Boolean =
        construction.canBePurchasedWithStat(city, stat)

    @Readonly fun isOwnedByViewer(): Boolean = city.civ === viewer
    @Readonly fun isOwnedTile(tileView: TileView): Boolean = tileView.unwrap().getCity() === city
    @Readonly fun getStatDiffForImprovement(tileView: TileView, improvement: TileImprovement): Stats =
        tileView.unwrap().stats.getStatDiffForImprovement(improvement, city.civ, city)
    @Readonly fun rankStatsValue(stats: Stats): Float = Automation.rankStatsValue(stats, city.civ)
    @Readonly private fun getTile(tileView: TileView) = tileView.unwrap()

    // ACTIONS
    // Gate all city actions on a single source of truth (Civilization.canOperate): in online
    // multiplayer every human acts on their own cities simultaneously, so there is no
    // "current player" restriction. This fixes "a second player can't queue production /
    // crashes with an empty queue" when two cities are managed at once.
    private fun canChangeState() = city.civ === viewer && viewer.canOperate()

    // UncivSync ─────────────────────────────────────────────────────────────────
    // 城市写操作统一上报服务端（同步回合下服务端是唯一权威）。
    //
    // CityView 是城市操作的总门面：CityScreen / CityConstructionsTable / CitizenManagementTable /
    // SpecialistAllocationTable 等所有城市 UI 都经由这里改状态。所以"op 接线"在这一层做一次
    // 就覆盖全部入口（包括以后新增的），不必逐个 UI 去补 —— 这是系统化接线，不是打补丁。
    //
    // 返回是否已接管；发包失败也不得回落本地，否则断线时会出现分叉。
    private fun fsOp(op: String, extra: Map<String, Any> = emptyMap()): Boolean {
        if (!FrameSync.isFsMode(city.civ.gameInfo)) return false
        com.unciv.logic.FsAudit.intent(city.civ.gameInfo, op)
        FrameSync.sendCityOp(city, op, extra = extra)
        return true
    }

    private fun tileKeys(tileView: TileView): Map<String, Any> =
        mapOf("tileX" to tileView.position().x, "tileY" to tileView.position().y)

    fun tryLockTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (!isWorked(tileView)) return false
        if (fsOp("city.lockTile", tileKeys(tileView))) return true
        return city.lockTile(getTile(tileView))
    }
    fun tryUnlockTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.unlockTile", tileKeys(tileView))) return true
        return city.unlockTile(getTile(tileView))
    }
    fun tryBuyTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (!city.expansion.canBuyTile(getTile(tileView))) return false
        if (fsOp("city.buyTile", tileKeys(tileView))) return true
        city.expansion.buyTile(getTile(tileView))
        return true
    }
    fun tryWorkTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.workTile", tileKeys(tileView))) return true
        return city.workTile(getTile(tileView))
    }
    fun tryStopWorkingTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.stopWorkTile", tileKeys(tileView))) return true
        return city.stopWorkingTile(getTile(tileView))
    }
    fun tryAddToQueue(name: String): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.addToQueue", mapOf("newConstruction" to name))) return true
        city.cityConstructions.addToQueue(name)
        return true
    }
    fun tryRemoveFromQueue(index: Int, automatic: Boolean): Boolean {
        if (!canChangeState()) return false
        val name = city.cityConstructions.constructionQueue.getOrNull(index)
        if (fsOp("city.removeFromQueue", buildMap {
            put("index", index)
            if (name != null) put("newConstruction", name)
        })) return true
        city.cityConstructions.removeFromQueue(index, automatic)
        return true
    }
    fun tryRaisePriority(index: Int): Int? {
        if (!canChangeState()) return null
        val name = city.cityConstructions.constructionQueue.getOrNull(index)
        if (fsOp("city.raisePriority", buildMap {
            put("index", index)
            if (name != null) put("constructionName", name)
            if (name != null) put("newConstruction", name)
        })) return null
        return city.cityConstructions.raisePriority(index)
    }
    fun tryLowerPriority(index: Int): Int? {
        if (!canChangeState()) return null
        val name = city.cityConstructions.constructionQueue.getOrNull(index)
        if (fsOp("city.lowerPriority", buildMap {
            put("index", index)
            if (name != null) put("constructionName", name)
            if (name != null) put("newConstruction", name)
        })) return null
        return city.cityConstructions.lowerPriority(index)
    }
    fun updateTileStats() = city.cityStats.updateTileStats()

    fun updateCityStats() = city.cityStats.update()
    fun tryRenameCity(name: String): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.rename", mapOf("name" to name))) return true
        city.name = name
        return true
    }
    fun tryAnnexCity(): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.annex")) return true
        city.annexCity()
        return true
    }
    fun trySetRazing(raze: Boolean): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.setRazing", mapOf("razing" to raze))) return true
        city.isBeingRazed = raze
        return true
    }
    fun tryAddToQueueWithTile(construction: IConstruction, tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.addToQueueWithTile", tileKeys(tileView) + mapOf("newConstruction" to construction.name))) return true
        city.cityConstructions.addToQueue(construction, tile = tileView.unwrap())
        return true
    }
    fun trySetUnitShouldUseSavedPromotion(baseUnit: String, value: Boolean): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.setUnitSavedPromotion", mapOf("baseUnit" to baseUnit, "value" to value))) return true
        city.unitShouldUseSavedPromotion[baseUnit] = value
        return true
    }
    fun trySellBuilding(construction: Building): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.sellBuilding", mapOf("buildingName" to construction.name))) return true
        city.sellBuilding(construction)
        return true
    }
    fun tryPurchaseConstruction(construction: INonPerpetualConstruction, queuePosition: Int,
                                stat: Stat, tileView: TileView?): Boolean {
        if (!canChangeState()) return false
        val data = buildMap<String, Any> {
            put("construction", construction.name)
            put("queuePosition", queuePosition)
            put("stat", stat.name)
            if (tileView != null) putAll(tileKeys(tileView))
        }
        if (fsOp("city.buy", data)) return true
        return city.cityConstructions.purchaseConstruction(construction, queuePosition, false, stat, tileView?.unwrap())
    }
    fun tryMoveEntryToTop(index: Int) {
        if (!canChangeState()) return
        val name = city.cityConstructions.constructionQueue.getOrNull(index)
        if (fsOp("city.moveQueueEntry", buildMap {
            put("index", index)
            if (name != null) put("newConstruction", name)
        })) return
        city.cityConstructions.moveEntryToTop(index)
    }
    fun tryMoveEntryToEnd(index: Int) {
        if (!canChangeState()) return
        val name = city.cityConstructions.constructionQueue.getOrNull(index)
        if (fsOp("city.moveEntryToEnd", buildMap {
            put("index", index)
            if (name != null) put("newConstruction", name)
        })) return
        city.cityConstructions.moveEntryToEnd(index)
    }
    fun tryAddToQueueConstruction(construction: IConstruction, addToTop: Boolean = false) {
        if (!canChangeState()) return
        if (fsOp("city.addToQueue", mapOf("newConstruction" to construction.name, "addToTop" to addToTop))) return
        city.cityConstructions.addToQueue(construction, addToTop = addToTop)
    }
    fun tryRemoveAllByName(name: String) {
        if (!canChangeState()) return
        if (fsOp("city.removeFromQueueByName", mapOf("newConstruction" to name))) return
        city.cityConstructions.removeAllByName(name)
    }
    fun tryDisableConstruction(name: String) {
        if (!canChangeState()) return
        if (fsOp("city.disableConstruction", mapOf("newConstruction" to name, "disable" to true))) return
        city.disabledConstructions.add(name)
    }
    fun tryEnableConstruction(name: String) {
        if (!canChangeState()) return
        if (fsOp("city.disableConstruction", mapOf("newConstruction" to name, "disable" to false))) return
        city.disabledConstructions.remove(name)
    }
    fun tryReassignPopulation(resetLocked: Boolean = false): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.reassignPopulation", mapOf("resetLocked" to resetLocked))) return true
        city.reassignPopulation(resetLocked)
        return true
    }
    fun tryToggleAvoidGrowth(): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.toggleAvoidGrowth")) return true
        city.avoidGrowth = !city.avoidGrowth
        city.reassignPopulation()
        return true
    }
    fun tryEnableManualSpecialists(): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.enableManualSpecialists")) return true
        city.manualSpecialists = true
        return true
    }
    fun tryDisableManualSpecialists(): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.disableManualSpecialists")) return true
        city.manualSpecialists = false
        city.reassignPopulation()
        return true
    }
    fun tryAssignSpecialist(specialistName: String): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.assignSpecialist", mapOf("specialistName" to specialistName, "delta" to 1))) return true
        city.population.specialistAllocations.add(specialistName, 1)
        city.manualSpecialists = true
        city.cityStats.update()
        return true
    }
    fun tryUnassignSpecialist(specialistName: String): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.assignSpecialist", mapOf("specialistName" to specialistName, "delta" to -1))) return true
        city.population.specialistAllocations.add(specialistName, -1)
        city.manualSpecialists = true
        city.cityStats.update()
        return true
    }
    fun trySetCityFocus(focus: CityFocus): Boolean {
        if (!canChangeState()) return false
        if (fsOp("city.setFocus", mapOf("newFocus" to focus.name))) return true
        city.setCityFocus(focus)
        city.reassignPopulation()
        return true
    }

}
