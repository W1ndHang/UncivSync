package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import yairm210.purity.annotations.Readonly
import java.util.EnumSet
import kotlin.text.ifEmpty

/**
 * [Popup] communicating events other than trade offers to the player.
 * (e.g. First Contact, Wonder built, Tech researched,...)
 *
 * **Opens itself at the end of instantiation!**
 *
 * (In rare cases, it chooses not to: Mods making a RecapturedCivilian not find the unit as it was illegal and removed after the actual capture)
 *
 * Called in [WorldScreen].update, which pulls them from viewingCiv.popupAlerts.
 *
 * @param worldScreen The parent screen
 * @param popupAlert The [PopupAlert] entry to present
 *
 * @see AlertType
 *
 * Attention developers: This is a Popup with `Scrollability.WithoutButtons`, and that means the
 * content area has two parts - one scrolls and the bottom not. Use Popup's normal `add` for stuff that
 * should go to the upper scrolling part and all typical closing buttons should *only* use Popup's
 * add*Button methods - for a good exception see `addCityConquered`.
 * That also means colspan is independent for top and bottom, and you need no row() between them.
 */
class AlertPopup(
    private val worldScreen: WorldScreen,
    private val popupAlert: PopupAlert
): Popup(worldScreen) {
    
    companion object {
        private const val SEPARATOR_LINE_TO_TEXT_PADDING = 25f
        private val LIGHTER_RED_COLOR = Color(1f, 1/3f, 1/3f, 1f)
        private val LIGHTER_GREEN_COLOR = Color(1/3f, 1f, 1/3f, 1f)
        private val LIGHTER_ORANGE_COLOR = Color(1f, 2/5f, 0f, 1f)
    }

    //region convenience getters
    private val music get() = UncivGame.Current.musicController
    private val gameInfo get() = worldScreen.gameInfo
    private val viewingCiv get() = worldScreen.selectedGameView.civView.getCiv()
    private val stageWidth get() = worldScreen.stage.width
    private val stageHeight get() = worldScreen.stage.height
    @Readonly private fun getCiv(civName: String) = gameInfo.getCivilization(civName)
    @Readonly private fun getCity(cityId: String) = gameInfo.getCities().first { it.id == cityId }
    //endregion

    // This redirects all addCloseButton uses with only text and no action to accept the space key
    private fun addCloseButton(text: String = Constants.close) =
        addCloseButton(text, KeyboardBinding.NextTurnAlternate, null)

    init {
        var shouldOpen = true

        // This makes the buttons fill up available width. See comments in #9559.
        // To implement a middle ground, I would either simply replace growX() with minWidth(240f) or so,
        // or replace the Popup.equalizeLastTwoButtonWidths() function with something intelligent not
        // limited to two buttons.
        bottomTable.defaults().growX()

        when (popupAlert.type) {
            // Cities
            AlertType.CityConquered -> addCityConquered()
            AlertType.CityTraded -> addCityTraded()
            AlertType.DiplomaticMarriage -> addDiplomaticMarriage()
            // Demands and diplomacy
            AlertType.FirstContact -> addFirstContact()
            AlertType.WarDeclaration -> shouldOpen = addWarDeclaration()
            AlertType.BorderConflict -> shouldOpen = addBorderConflict()
            AlertType.TilesStolen -> shouldOpen = addTilesStolen()
            AlertType.Denounced -> shouldOpen = addDenouncement()
            
            // demands
            AlertType.DemandToStopSettlingCitiesNear -> shouldOpen = addDemand(Demand.DoNotSettleNearUs)
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSettleNearUs)
            AlertType.DemandToStopSpreadingReligion -> shouldOpen = addDemand(Demand.DoNotSpreadReligion)
            AlertType.ReligionSpreadDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSpreadReligion)
            AlertType.DemandToStopSpyingOnUs -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.SpyingOnUsDespiteOurPromise -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.DemandToNotAttackUs -> shouldOpen = addDemand(Demand.DoNotAttackUs)
            AlertType.AttackedUsDespitePromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotAttackUs)
            AlertType.AcceptingDemand -> shouldOpen = addAcceptingDemand()
            AlertType.RejectingDemand -> shouldOpen = addRejectingDemand()
            
            AlertType.DeclarationOfFriendship -> shouldOpen = addDeclarationOfFriendship()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> 
                shouldOpen = addBulliedOrAttackedProtectedOrAlliedMinor()
            AlertType.Defeated -> addDefeated()
            // We did stuff
            AlertType.WonderBuilt -> addWonderBuilt()
            AlertType.TechResearched -> addTechResearched()
            AlertType.GoldenAge -> addGoldenAge()
            AlertType.StartIntro -> addStartIntro()
            AlertType.RecapturedCivilian -> shouldOpen = addRecapturedCivilian()
            AlertType.GameHasBeenWon -> addGameHasBeenWon()
            AlertType.Event -> shouldOpen = addEvent()
        }
        if (shouldOpen) open()
        else dropAlert(popupAlert)
    }

    //region AlertType handlers

    private fun addBorderConflict(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addGoodSizedLabel("Remove your troops in our border immediately!")
        addCloseButton("Sorry.", KeyboardBinding.Confirm)
        addCloseButton("Never!", KeyboardBinding.Cancel)
        return true
    }
    
    private fun addTilesStolen(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addGoodSizedLabel("Those lands were not yours to take. This has not gone unnoticed.")
        addCloseButton()
        return true
    }

    private fun addBulliedOrAttackedProtectedOrAlliedMinor(): Boolean {
        val involvedCivs = popupAlert.value.split('@')
        val bullyOrAttacker = getCiv(involvedCivs[0])
        if (bullyOrAttacker.isDefeated()) return false
        val cityState = getCiv(involvedCivs[1])
        val player = viewingCiv
        addLeaderName(bullyOrAttacker)

        val isAtLeastNeutral = bullyOrAttacker.getDiplomacyManager(player)!!.isRelationshipLevelGE(RelationshipLevel.Neutral)
        val text = when {
            popupAlert.type == AlertType.BulliedProtectedMinor && isAtLeastNeutral ->  // Nice message
                "I've been informed that my armies have taken tribute from [${cityState.civName}], a city-state under your protection.\nI assure you, this was quite unintentional, and I hope that this does not serve to drive us apart."
            popupAlert.type == AlertType.BulliedProtectedMinor ->  // Nasty message
                "We asked [${cityState.civName}] for a tribute recently and they gave in.\nYou promised to protect them from such things, but we both know you cannot back that up."
            isAtLeastNeutral ->  // Nice message
                "It's come to my attention that I may have attacked [${cityState.civName}].\nWhile it was not my goal to be at odds with your empire, this was deemed a necessary course of action."
            else ->  // Nasty message
                "I thought you might like to know that I've launched an invasion of one of your little pet states.\nThe lands of [${cityState.civName}] will make a fine addition to my own."
        }
        addGoodSizedLabel(text).row()
        
        if (!player.isAtWarWith(bullyOrAttacker)) {
            addCloseButton("THIS MEANS WAR!", KeyboardBinding.Confirm) {
            player.getDiplomacyManager(bullyOrAttacker)!!.sideWithCityState()
            val warReason = if (popupAlert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
            player.getDiplomacyManager(bullyOrAttacker)!!.declareWar(DeclareWarReason(warReason, cityState))
            cityState.getDiplomacyManager(player)!!.influence += 20f // You went to war for us!!
        }.row()}

        addCloseButton("You'll pay for this!", KeyboardBinding.Confirm) {
            player.getDiplomacyManager(bullyOrAttacker)!!.sideWithCityState()
        }.row()

        addCloseButton("Very well.", KeyboardBinding.Cancel) {
            player.addNotification("You have broken your Pledge to Protect [${cityState.civName}]!",
                cityState.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cityState.civName)
            cityState.cityStateFunctions.removeProtectorCiv(player, forced = true)
        }.row()
        
        return true
    }

    /**
     * UncivSync: 同步模式下把"占领后怎么处理"交给服务端。
     *
     * 以前这四个选项（并吞 / 傀儡 / 烧毁 / 归还）都是纯本地执行 —— 服务端不知道，
     * 下个 sim 就把城市纠正回傀儡（或者根本没转手）。返回 true 表示已发 op，本地不要再改。
     */
    private fun sendConquerChoice(city: City, choice: String): Boolean {
        if (!FrameSync.isFsMode(gameInfo)) return false
        // 已经归我方的城市（联姻 / 交易得到）的 annex/puppet 用更精确的城市 op，
        // 不走占领选择 —— 服务端那边的 annex 分支会先 puppetCity，对自有城市多余。
        // liberate / raze / destroy 无论城市是否已归我方都走同一条 op：服务端实现
        // （puppetCity → annexCity? → liberateCity/setBeingRazed/destroyCity）与
        // 本地路径逐句一致，联姻/交易城市的"归还/烧毁"不能再落回本地执行 —— 那会
        // 绕过服务端权威，下一条 sim 就把状态纠正回来（客户端看到"点了又弹回"）。
        val alreadyMine = city.civ == gameInfo.getCurrentPlayerCivilization()
        if (alreadyMine && (choice == "annex" || choice == "puppet")) {
            if (choice == "annex") FrameSync.sendCityOp(city, "city.annex")
            else FrameSync.sendCityOp(city, "city.setPuppet", extra = mapOf("puppet" to true))
            return true
        }
        FrameSync.sendCivOp(
            "civ.cityConquerChoice",
            mapOf(
                "cityId" to city.id,
                "cityName" to city.name,
                "civ" to city.civ.civName,
                "x" to city.location.x,
                "y" to city.location.y,
                "choice" to choice
            )
        )
        return true
    }

    private fun addCityConquered() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        val conqueringCiv = gameInfo.getCurrentPlayerCivilization()

        if (city.foundingCivObject != null
                && city.civ != city.foundingCivObject // can't liberate if the city actually belongs to those guys
                && conqueringCiv != city.foundingCivObject) { // or belongs originally to us
            addLiberateOption(city, conqueringCiv)
            addSeparator()
        }

        if (conqueringCiv.isOneCityChallenger()) {
            addDestroyOption(city) {
                city.puppetCity(conqueringCiv)
                city.destroyCity()
            }
        } else {
            val mayAnnex = !conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
            }
            addSeparator()

            addPuppetOption(city, mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
            }
            addSeparator()

            addRazeOption(city, mayAnnex = mayAnnex, conqueringCiv)
        }
    }

    private fun addDemandViolationNoticed(demand: Demand): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated()) return false
        addLeaderName(otherciv)
        addGoodSizedLabel(demand.violationNoticedText).row()
        addCloseButton("Very well.")
        return true
    }

    private fun addCityTraded() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        val conqueringCiv = gameInfo.getCurrentPlayerCivilization()

        if (!conqueringCiv.isAtWarWith(city.foundingCivObject!!)) {
            addLiberateOption(city, conqueringCiv)
            addSeparator()
        }
        addCloseButton("Keep it").row()
    }

    private fun addDeclarationOfFriendship(): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated() || otherciv.getDiplomacyManager(viewingCiv)!!.diplomaticStatus == DiplomaticStatus.War) return false
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)!!
        addLeaderName(otherciv)
        addTopicHeader("DECLARATION OF FRIENDSHIP", LIGHTER_GREEN_COLOR)
        addGoodSizedLabel(
                if (otherciv.nation.declaringFriendship.isNotEmpty()) otherciv.nation.declaringFriendship else "My friend, shall we declare our friendship to the world?"
        ).row()
        addCloseButton("Declare Friendship ([30] turns)", KeyboardBinding.Confirm) {
            // UncivSync: 回应宣友提议（服务端权威签署）
            if (FrameSync.isFsMode(viewingCiv.gameInfo))
                FrameSync.sendCivOp("civ.friendshipAccept", mapOf("requestingCiv" to otherciv.civID))
            playerDiploManager.signDeclarationOfFriendship()
        }.row()
        addCloseButton("We are not interested.", KeyboardBinding.Cancel) {
            // UncivSync: 拒绝宣友提议（服务端权威记录冷却）
            if (FrameSync.isFsMode(viewingCiv.gameInfo))
                FrameSync.sendCivOp("civ.friendshipDecline", mapOf("requestingCiv" to otherciv.civID))
            playerDiploManager.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
        }.row()
        val music = UncivGame.Current.musicController
        music.playVoice("${otherciv.nation.name}.declaringFriendship")
        return true
    }

    private fun addDenouncement(): Boolean {
        val denouncer = getCiv(popupAlert.value)
        if (denouncer.isDefeated())
            return false
        addLeaderName(denouncer)
        addTopicHeader("DENOUNCEMENT", LIGHTER_ORANGE_COLOR)
        // normal message unless we are enemies
        val leaderMessage = if (denouncer.getDiplomacyManager(viewingCiv)!!.isRelationshipLevelGE(RelationshipLevel.Competitor)) {
            music.playVoice("${denouncer.nation.name}.neutralDenouncing")
            denouncer.nation.neutralDenouncing.ifEmpty { "You have violated our bond of trust. This is intolerable!" }
        } else {
            music.playVoice("${denouncer.nation.name}.hateDenouncing")
            denouncer.nation.hateDenouncing.ifEmpty { "You are a scourge upon this earth. I denounce you!" }
        }
        addGoodSizedLabel(leaderMessage).row()
        val diplomacy = viewingCiv.getDiplomacyManager(denouncer)!!
        if (diplomacy.canDeclareWar()) {
            addCloseButton("THIS MEANS WAR! (Declare war)") {
                // UncivSync: 被谴责后立刻宣战也要上报
                if (FrameSync.isFsMode(viewingCiv.gameInfo))
                    FrameSync.sendCivOp("civ.declareWar", mapOf("otherCivId" to denouncer.civID))
                diplomacy.declareWar()
            }.row()
        }
        addCloseButton("Very well.", KeyboardBinding.Cancel).row()
        return true
    }
    
    private fun addDefeated() {
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.defeated).row()
        addCloseButton("Farewell.")
        music.chooseTrack(civInfo.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
        music.playVoice("${civInfo.civName}.defeated")
    }

    private fun addDemand(demand: Demand): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated()) return false
        
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)!!
        addLeaderName(otherciv)
        addGoodSizedLabel(demand.demandText).row()
        addCloseButton(demand.acceptDemandText, KeyboardBinding.Confirm) {
            // UncivSync: 接受对方的索求（服务端权威写入承诺标记）
            if (FrameSync.isFsMode(viewingCiv.gameInfo))
                FrameSync.sendCivOp("civ.demandAccept",
                    mapOf("requestingCiv" to otherciv.civID, "demand" to demand.name))
            playerDiploManager.agreeToDemand(demand)
        }.row()
        addCloseButton(demand.refuseDemandText, KeyboardBinding.Cancel) {
            // UncivSync: 拒绝对方的索求（服务端权威记录）
            if (FrameSync.isFsMode(viewingCiv.gameInfo))
                FrameSync.sendCivOp("civ.demandRefuse",
                    mapOf("requestingCiv" to otherciv.civID, "demand" to demand.name))
            playerDiploManager.refuseDemand(demand)
            if (demand == Demand.DoNotAttackUs)
                viewingCiv.getDiplomacyManager(otherciv)!!.declareWar()
        }
        return true
    }

    private fun addAcceptingDemand(): Boolean {
        val otherCiv = getCiv(popupAlert.value)
        if (otherCiv.isDefeated())
            return false
        addLeaderName(otherCiv)
        addTopicHeader("ACCEPTING DEMAND", Color.YELLOW)
        val leaderMessage = otherCiv.nation.acceptingDemand.ifEmpty {
            "We will comply, but our consent is given grudgingly."
        }
        addGoodSizedLabel(leaderMessage).row()
        music.playVoice("${otherCiv.civName}.acceptingDemand")
        addCloseButton("Very well.", KeyboardBinding.Cancel)
        return true
    }
    
    private fun addRejectingDemand(): Boolean {
        val otherCiv = getCiv(popupAlert.value)
        if (otherCiv.isDefeated())
            return false
        addLeaderName(otherCiv)
        addTopicHeader("REJECTING DEMAND", LIGHTER_ORANGE_COLOR)
        val theirDiplomacy = otherCiv.getDiplomacyManager(viewingCiv)!!
        val leaderMessage = if (theirDiplomacy.isRelationshipLevelGE(RelationshipLevel.Competitor)) {
            music.playVoice("${otherCiv.nation.name}.neutralRejectingDemand")
            otherCiv.nation.neutralRejectingDemand.ifEmpty {
                "Your demands are in poor taste. We shall decide this matter on our own."
            }
        } else {
            music.playVoice("${otherCiv.nation.name}.hateRejectingDemand")
            otherCiv.nation.hateRejectingDemand.ifEmpty {
                "Did you really expect us to bend to such brazen demands?"
            }
        }
        addGoodSizedLabel(leaderMessage).row()
        addCloseButton("You'll pay for this!")
        addCloseButton("Very well.", KeyboardBinding.Cancel)
        equalizeLastTwoButtonWidths()
        return true
    }

    private fun addDiplomaticMarriage() {
        val city = getCity(popupAlert.value)
        addGoodSizedLabel(city.name.tr() + ": " + "What would you like to do with the city?".tr(), Constants.headingFontSize) // Add name because there might be several cities
            .padBottom(20f).row()
        val marryingCiv = gameInfo.getCurrentPlayerCivilization()

        if (marryingCiv.isOneCityChallenger()) {
            addDestroyOption(city) {
                city.destroyCity(overrideSafeties = true)
            }
        } else {
            val mayAnnex = !marryingCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex) {}
            addSeparator()

            addPuppetOption(city, mayAnnex) {
                city.isPuppet = true
                city.cityStats.update()
            }
        }
    }

    private fun addFirstContact() {
        val civInfo = getCiv(popupAlert.value)
        val nation = civInfo.nation
        addLeaderName(civInfo)
        music.chooseTrack(civInfo.civName, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.introduction")
        if (civInfo.isCityState) {
            addGoodSizedLabel("We have encountered the City-State of [${nation.name}]!").row()
            addCloseButton("Excellent!")
        } else {
            addGoodSizedLabel(nation.introduction).row()
            addCloseButton("A pleasure to meet you.")
        }
    }

    private fun addGameHasBeenWon() {
        val victoryData = gameInfo.victoryData!!
        addGoodSizedLabel("[${victoryData.winningCivObject.civName}] has won a [${victoryData.victoryType}] Victory!").row()
        addButton("Victory status") { close(); worldScreen.game.pushScreen{ VictoryScreen(worldScreen) } }.row()
        addCloseButton()
    }

    private fun addGoldenAge() {
        addGoodSizedLabel("GOLDEN AGE")
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        addGoodSizedLabel("Your citizens have been happy with your rule for so long that the empire enters a Golden Age!").row()
        addCloseButton()
        music.chooseTrack(viewingCiv.civName, MusicMood.Golden, MusicTrackChooserFlags.setSpecific)
    }

    /** @return false to skip opening this Popup, as we're running in the initialization phase before the Popup is open */
    private fun addRecapturedCivilian(): Boolean {
        val position = HexCoord.fromString(popupAlert.value)
        val tile = gameInfo.tileMap[position]
        val capturedUnit = tile.civilianUnit  // This has got to be it
            ?: return false // the unit disappeared somehow? maybe a modded action?
        val originalOwner = capturedUnit.originalOwningCiv!!
        if (originalOwner.isDefeated()) return false
        val captor = viewingCiv

        addGoodSizedLabel("Return [${capturedUnit.name}] to [${originalOwner.civName}]?")
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        addGoodSizedLabel("The [${capturedUnit.name}] we liberated originally belonged to [${originalOwner.civName}]. They will be grateful if we return it to them.").row()

        bottomTable.defaults().pad(0f, 30f) // Small buttons, plenty of pad so we don't fat-finger it

        addCloseButton(Constants.yes, KeyboardBinding.Confirm) {
            // Return it to original owner
            val unitName = capturedUnit.baseUnit.name
            capturedUnit.destroy()
            val closestCity = originalOwner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

            if (closestCity != null) {
                // Attempt to place the unit near their nearest city
                originalOwner.units.placeUnitNearTile(closestCity.location.toHexCoord(), unitName)
            }

            if (originalOwner.isCityState) {
                originalOwner.getDiplomacyManagerOrMeet(captor).addInfluence(45f)
            } else if (originalOwner.isMajorCiv()) {
                // No extra bonus from doing it several times
                originalOwner.getDiplomacyManagerOrMeet(captor)
                    .setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
            }
            val notificationSequence = sequence {
                yield(LocationAction(tile.position))
                if (closestCity != null)
                    yield(LocationAction(closestCity.location))
                yield(DiplomacyAction(captor))
                yield(CivilopediaAction("Tutorial/Barbarians"))
            }
            originalOwner.addNotification("Your captured [${unitName}] has been returned by [${captor.civName}]", notificationSequence, NotificationCategory.Diplomacy, NotificationIcon.Trade, unitName, captor.civName)
        }
        addCloseButton(Constants.no, KeyboardBinding.Cancel) {
            // Take it for ourselves
            BattleUnitCapture.captureOrConvertToWorker(capturedUnit, captor)
        }
        return true
    }

    private fun addStartIntro() {
        val civInfo = viewingCiv
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.startIntroPart1).row()
        addGoodSizedLabel(civInfo.nation.startIntroPart2).row()
        addCloseButton("Let's begin!")

        // Since there's introduction text, play the startIntroPart1 voice hook with the nation's theme.
        val music = UncivGame.Current.musicController
        music.chooseTrack(civInfo.nation.name, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.nation.name}.startIntroPart1")
    }

    private fun addTechResearched() {
        val tech = gameInfo.ruleset.technologies[popupAlert.value]!!
        addGoodSizedLabel(tech.name)
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        val centerTable = Table()
        centerTable.add(tech.quote.toLabel().apply { wrap = true }).width(stageWidth / 3)
        centerTable.add(ImageGetter.getTechIconPortrait(tech.name, 100f)).pad(20f)
        val descriptionScroll = ScrollPane(tech.getDescription(viewingCiv).toLabel().apply { wrap = true })
        centerTable.add(descriptionScroll).width(stageWidth / 3).maxHeight(stageHeight / 2)
        add(centerTable).row()
        addCloseButton()
        music.chooseTrack(tech.name, MusicMood.Researched, MusicTrackChooserFlags.setSpecific)
    }

    private fun addWarDeclaration(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        // technically they already declared war, but if they're dead it'll be strange that they talk to us
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addTopicHeader("DECLARATION OF WAR", LIGHTER_RED_COLOR)
        val leaderMessage = civInfo.nation.declaringWar
        if (leaderMessage.isNotEmpty())
            addGoodSizedLabel(leaderMessage).row()
        addCloseButton("You'll pay for this!")
        addCloseButton("Very well.")
        equalizeLastTwoButtonWidths()
        music.chooseTrack(civInfo.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.declaringWar")
        return true
    }

    private fun addTopicHeader(text: String, color: Color) {
        addGoodSizedLabel(text, color=color, size=Constants.smallerHeadingFontSize)
            .padBottom(20f).row()
    }

    private fun addWonderBuilt() {
        val wonder = gameInfo.ruleset.buildings[popupAlert.value]!!
        addGoodSizedLabel(wonder.name)
        addSeparator().padBottom(10f)
        if(ImageGetter.wonderImageExists(wonder.name)) {    // Wonder Graphic exists
            if(stageHeight * 3 > stageWidth * 4) {    // Portrait
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 1.5f)
                    .height(stageWidth / 3)
                    .row()
            }
            else {  // Landscape (or squareish)
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 2.5f)
                    .height(stageWidth / 5)
                    .row()
            }
        } else {    // Fallback
            add(ImageGetter.getConstructionPortrait(wonder.name, 100f)).pad(20f).row()
        }

        val centerTable = Table()
        val centerTableColumnWidth = stageWidth / if (wonder.quote.isEmpty()) 2 else 3
        if (wonder.quote.isNotEmpty()) {
            centerTable.add(wonder.quote.toLabel().apply { wrap = true })
                .width(centerTableColumnWidth)
                .pad(10f)
        }
        centerTable.add(wonder.getShortDescription().toLabel().apply { wrap = true })
            .width(centerTableColumnWidth)
            .pad(10f)
        add(centerTable).row()
        addCloseButton()
        music.chooseTrack(wonder.name, MusicMood.Wonder, MusicTrackChooserFlags.setSpecific)
    }

    //endregion
    //region Helpers

    private fun addLeaderName(civInfo: Civilization) {
        add(LeaderIntroTable(civInfo))
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
    }

    private fun addQuestionAboutTheCity(cityName: String) {
        addGoodSizedLabel("What would you like to do with the city of [$cityName]?",
            Constants.headingFontSize, hideIcons = true).padBottom(20f).row()
    }

    private fun addDestroyOption(city: City, destroyAction: () -> Unit) {
        val button = "Destroy".toTextButton()
        button.onActivation {
            if (!sendConquerChoice(city, "destroy")) destroyAction()
            close()
        }
        button.keyShortcuts.add('d')
        add(button).row()
        addGoodSizedLabel("Destroying the city instantly razes the city to the ground.").row()
    }

    private fun addAnnexOption(city: City, mayAnnex: Boolean, annexAction: () -> Unit) {
        val button = "Annex".toTextButton()
        button.apply {
            if (!mayAnnex) disable() else {
                button.onActivation {
                    // UncivSync: 同步模式只发 op（服务端做 puppetCity + annexCity）
                    if (!sendConquerChoice(city, "annex")) {
                        annexAction()
                        city.annexCity()
                    }
                    close()
                }
                button.keyShortcuts.add('a')
            }
        }
        add(button).row()
        if (mayAnnex) {
            addGoodSizedLabel("Annexed cities become part of your regular empire.").row()
            addGoodSizedLabel("Their citizens generate 2x the unhappiness, unless you build a courthouse.").row()
        } else {
            addGoodSizedLabel("Your civilization may not annex this city.").row()
        }

    }

    private fun addPuppetOption(city: City, mayAnnex: Boolean, puppetAction: () -> Unit) {
        val button = "Puppet".toTextButton()
        button.onActivation {
            if (!sendConquerChoice(city, "puppet")) puppetAction()
            close()
        }
        button.keyShortcuts.add('p')
        add(button).row()
        addGoodSizedLabel("Puppeted cities do not increase your tech or policy cost.").row()
        addGoodSizedLabel("You have no control over the the production of puppeted cities.").row()
        addGoodSizedLabel("Puppeted cities also generate 25% less Science and Culture.").row()
        if (mayAnnex) addGoodSizedLabel("A puppeted city can be annexed at any time.").row()
    }

    private fun addLiberateOption(city: City, conqueringCiv: Civilization) {
        val button = "Liberate (city returns to [originalOwner])".fillPlaceholders(city.foundingCivObject!!.civName).toTextButton()
        button.onActivation {
            if (!sendConquerChoice(city, "liberate")) city.liberateCity(conqueringCiv)
            close()
        }
        button.keyShortcuts.add('l')
        add(button).row()
        addGoodSizedLabel("Liberating a city returns it to its original owner, giving you a massive relationship boost with them!")
    }

    private fun addRazeOption(city: City, mayAnnex: Boolean, conqueringCiv: Civilization) {
        val canRaze = city.canBeDestroyed(justCaptured = true)
        val button = "Raze".toTextButton()
        button.apply {
            if (!canRaze) disable()
            else {
                onActivation {
                    if (!sendConquerChoice(city, "raze")) {
                        city.puppetCity(conqueringCiv)
                        if (mayAnnex) { city.annexCity() }
                        city.isBeingRazed = true
                    }
                    close()
                }
                keyShortcuts.add('r')
            }
        }
        add(button).row()
        if (canRaze) {
            if (mayAnnex) {
                addGoodSizedLabel("Razing the city annexes it, and starts burning the city to the ground.").row()
            } else {
                addGoodSizedLabel("Razing the city puppets it, and starts burning the city to the ground.").row()
            }
            addGoodSizedLabel("The population will gradually dwindle until the city is destroyed.").row()
        } else {
            addGoodSizedLabel("Original capitals and holy cities cannot be razed.").row()
        }
    }

    /** Returns if event was triggered correctly */
    private fun addEvent(): Boolean {
        // The event string is in the format "eventName" + (Constants.stringSplitCharacter + "unitId=1234")?
        // We explicitly specify that this is a unitId, to enable us to add other context info in the future - for example city id
        val splitString = popupAlert.value.split(Constants.stringSplitCharacter)
        val eventName = splitString[0]
        var unit: MapUnit? = null
        for (i in 1 until splitString.size) {
            if (splitString[i].startsWith("unitId=")){
                val unitId = splitString[i].substringAfter("unitId=").toInt()
                unit = viewingCiv.units.getUnitById(unitId)
            }
        }
        
        
        val event = gameInfo.ruleset.events[eventName] ?: return false
        val render = RenderEvent(event, worldScreen, unit) { close() }
        if (!render.isValid) return false
        add(render).pad(0f).row()
        return true
    }

    //endregion

    /**
     * UncivSync: 通知被消费 —— 本地移除 + 上报服务端同步删除。
     * 服务端权威存档里若不清掉，每次整档下发重建世界时它会再被加载回来，导致重复弹窗。
     */
    private fun dropAlert(alert: PopupAlert) {
        // 事件待办与奖励由服务端在同一操作内消费，不能抢先发送已读消息。
        if (FrameSync.isFsMode(gameInfo) && alert.type == AlertType.Event) return
        viewingCiv.popupAlerts.remove(alert)
        FrameSync.notifyAlertSeen(viewingCiv.civName, alert)
    }

    override fun close() {
        dropAlert(popupAlert)
        worldScreen.shouldUpdate = true
        super.close()
    }
}
