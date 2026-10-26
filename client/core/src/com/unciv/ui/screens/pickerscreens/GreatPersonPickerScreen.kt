package com.unciv.ui.screens.pickerscreens

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.screens.worldscreen.FrameSync
import com.unciv.ui.screens.worldscreen.WorldScreen

class GreatPersonPickerScreen(val worldScreen: WorldScreen, val civInfo: Civilization) : PickerScreen() {
    private var theChosenOne: BaseUnit? = null

    init {
        worldScreen.autoPlay.stopAutoPlay()
        closeButton.isVisible = false
        rightSideButton.setText("Choose a free great person".tr())

        val greatPersonUnits = civInfo.greatPeople.getGreatPeople()
        val availableGreatPeople = civInfo.greatPeople.getFreeGreatPersonOptions()
        if (availableGreatPeople.isEmpty()) {
            descriptionLabel.setText("Unavailable".tr())
            allowClose()
        }

        for (unit in greatPersonUnits) {
            val button =
                PickerPane.getPickerOptionButton(ImageGetter.getUnitIcon(unit), unit.name)
            button.pack()
            button.isEnabled = unit in availableGreatPeople
            if (button.isEnabled) {
                button.onClick {
                    theChosenOne = unit
                    pick("Get [${unit.name}]".tr())
                    descriptionLabel.setText(unit.getShortDescription())
                }

                button.onDoubleClick(UncivSound.Choir) { confirmAction() }
            }
            topTable.add(button).pad(10f).row()
        }

        rightSideButton.onClick(UncivSound.Choir) {
            confirmAction()
        }

        descriptionLabel.onActivation {
            openCivilopedia(theChosenOne?.makeLink().orEmpty())
        }
        descriptionLabel.keyShortcuts.add(KeyboardBinding.Civilopedia)
    }

    private fun confirmAction() {
        val chosenUnit = theChosenOne ?: return
        val currentOptions = civInfo.greatPeople.getFreeGreatPersonOptions()
        if (currentOptions.none { it.name == chosenUnit.name }) {
            descriptionLabel.setText("Unavailable".tr())
            if (currentOptions.isEmpty()) {
                worldScreen.deferFreeGreatPersonPicker = worldScreen.hasPendingFreeGreatPerson()
                UncivGame.Current.popScreen()
            } else {
                theChosenOne = null
                setRightSideButtonEnabled(false)
                // Reopening rebuilds buttons if the set of available choices changed.
                allowClose()
            }
            return
        }
        // UncivSync: 服务端权威 —— 只发 op。放哪个单位、扣几次名额都由服务端做，
        // 结果随 sim 回推（名额减到 0 之后这个屏自然不会再弹）。
        if (FrameSync.isFsMode(civInfo.gameInfo)) {
            FrameSync.sendCivOp("civ.chooseFreeGreatPerson", mapOf("unit" to chosenUnit.name))
            // 等服务端把名额改掉之前，别再自动弹一次
            worldScreen.deferFreeGreatPersonPicker = true
            UncivGame.Current.popScreen()
            return
        }
        if (civInfo.greatPeople.chooseFreeGreatPerson(chosenUnit.name) == null) {
            descriptionLabel.setText("No space to place this unit".tr())
            allowClose()
            return
        }
        worldScreen.deferFreeGreatPersonPicker = false
        UncivGame.Current.popScreen()
    }

    private fun allowClose() {
        if (closeButton.isVisible) return
        worldScreen.deferFreeGreatPersonPicker = true
        closeButton.isVisible = true
        setDefaultCloseAction()
    }
}
