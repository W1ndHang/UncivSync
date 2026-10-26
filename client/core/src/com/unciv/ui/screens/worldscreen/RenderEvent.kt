package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

/** Renders an [Event] for [AlertPopup] or a floating tutorial task on [WorldScreen] */
class RenderEvent(
    private val event: Event,
    val worldScreen: WorldScreen,
    val unit: MapUnit? = null,
    val onChoice: (EventChoice) -> Unit
) : Table() {
    private val gameInfo get() = worldScreen.gameInfo
    private val stageWidth get() = worldScreen.stage.width

    val isValid: Boolean

    //todo check generated translations

    init {
        defaults().fillX().center().pad(5f)

        val gameContext = GameContext(gameInfo.currentPlayerCiv, unit = unit)
        val choices = event.getMatchingChoices(gameContext)
        isValid = choices != null
        if (isValid) {
            if (event.text.isNotEmpty()) {
                add(WrappableLabel(event.text, stageWidth * 0.5f).apply {
                    wrap = true
                    setAlignment(Align.center)
                    optimizePrefWidth()
                }).row()
            }
            if (event.civilopediaText.isNotEmpty()) {
                add(event.renderCivilopediaText(stageWidth * 0.5f, ::openCivilopedia)).row()
            }

            for (choice in choices!!) addChoice(choice)
        }
    }

    private fun addChoice(choice: EventChoice) {
        addSeparator()

        val button = choice.text.toTextButton()
        button.onActivation {
            // UncivSync: 服务端权威 —— 事件的 uniques 效果由服务端 `EventChoice.triggerChoice` 执行。
            // 事件只在 mod 定义了 Events.json 的选项时才会出现，所以这条平时走不到。
            if (FrameSync.isFsMode(gameInfo)) {
                val data = linkedMapOf<String, Any>(
                    "event" to event.name,
                    "choice" to choice.text
                )
                if (unit != null && unit.currentTile != null) {
                    data["unitId"] = unit.id
                    data["unitType"] = unit.baseUnit.name
                    data["fromX"] = unit.currentTile.position.x
                    data["fromY"] = unit.currentTile.position.y
                }
                FrameSync.sendCivOp("civ.eventChoice", data)
            } else {
                choice.triggerChoice(gameInfo.currentPlayerCiv, unit)
            }
            onChoice(choice)
        }
        val key = KeyCharAndCode.parse(choice.keyShortcut)
        if (key != KeyCharAndCode.UNKNOWN) {
            button.keyShortcuts.add(key)
            button.addTooltip(key)
        }
        add(button).row()

        val lines = (
            choice.civilopediaText.asSequence()
                + choice.uniqueObjects.filter { it.isTriggerable || it.type == UniqueType.Comment }
                    .filterNot { it.isHiddenToUsers() }
                    .map { FormattedLine(it) }
            ).asIterable()
        add(MarkupRenderer.render(lines, stageWidth * 0.5f, linkAction = ::openCivilopedia)).row()
    }

    private fun openCivilopedia(link: String) = worldScreen.openCivilopedia(link)
}
