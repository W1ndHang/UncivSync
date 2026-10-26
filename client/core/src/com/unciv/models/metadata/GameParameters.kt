package com.unciv.models.metadata

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.Speed

class GameParameters : IsPartOfGameInfoSerialization { // Default values are the default new game
    var difficulty = "Prince"
    var speed: String = Speed.DEFAULT // Not an instance of class Speed

    var randomNumberOfPlayers = false
    var minNumberOfPlayers = 3
    var maxNumberOfPlayers = 3
    var players = ArrayList<Player>().apply {
        add(Player(playerType = PlayerType.Human))
        repeat(3) { add(Player()) }
    }
    var randomNumberOfCityStates = false
    var minNumberOfCityStates = 6
    var maxNumberOfCityStates = 6
    var numberOfCityStates = 6

    var enableRandomNationsPool = false
    var randomNationsPool = arrayListOf<String>()

    var noCityRazing = false
    var noBarbarians = false
    var ragingBarbarians = false
    var oneCityChallenge = false
    var godMode = false
    var nuclearWeaponsEnabled = true
    var espionageEnabled = false
    var noStartBias = false
    var shufflePlayerOrder = false

    var victoryTypes: ArrayList<String> = arrayListOf()
    var startingEra = "Ancient era"

    @Deprecated("Since 4.21.0, use showCivilizationStats")
    var showVictoryStats = true
    // TODO: remove nullable after migration
    var showCivilizationStats: Boolean? = null
        get() = field ?: showVictoryStats
        set(value) {
            field = value
            if (value != null)
                showVictoryStats = value
        }
    var showDemographics = false
    var showRankings = true
    var showCharts = true
    var hideOtherCivilizationStats = false
    var showAdditionalRankingTypes = false

    // Multiplayer parameters
    var isOnlineMultiplayer = false
    var multiplayerServerUrl: String? = null
    var anyoneCanSpectate = true
    /** After this amount of minutes, anyone can choose to 'skip turn' of the current player to keep the game going */
    var minutesUntilSkipTurn = 60 * 24
    /** Initial players' timer to play before they can be forced to resign permanently*/
    var minutesUntilForceResign = 3 * 24 * 60
    /** Time a player recover on their timer before they can be forced to resign. Time isn't added if the player get their turn skipped*/
    var minutesRecoveredPerTurn = 60 * 24

    // Simultaneous-turns (real-time FrameSync) parameters — UncivSync additions.
    /** Enables real-time simultaneous turns (FrameSync) instead of turn-by-turn async multiplayer. */
    var simultaneousTurns = false
    /** Whether the turn has a growing countdown (sqrt curve). False = no countdown, advance when everyone is ready. */
    var fsDynamicTurns = true
    /** Base/min turn duration in seconds for the dynamic countdown.（2026-09-30 起 90s，用户要求；服务端按此值直接排程，无额外加成） */
    var fsTurnTime = 90f
    /** Upper bound of turn duration in seconds when the dynamic countdown is enabled. */
    var fsMaxTurnTime = 180f
    /** Seconds the "settling" lock is shown when the turn rolls over (default 1). */
    var fsSettleLockSeconds = 1
    /** Number of teams in a team game (default 1 = free-for-all). */
    var fsTeamCount = 1
    /** Team member groups; index = team. */
    var fsTeams: Array<Array<String>>? = null
    /** True when the game was created through the lobby flow. */
    var viaLobby = false

    /**
     * Turn duration in seconds for a given turn number, or null when no countdown applies.
     *
     * - Dynamic turns ([fsDynamicTurns] = true): grows along a square-root curve from
     *   [fsTurnTime] (turn 0) toward [fsMaxTurnTime] (turn [maxTurns]) — growth is fast
     *   early on and flattens out, unlike a linear ramp which keeps ballooning late game.
     *   When the countdown expires the turn rolls over even if someone hasn't ended it.
     * - Otherwise: returns null — there is no countdown; the turn only advances once every
     *   human player has ended their turn (the default "everyone ready" flow).
     */
    fun turnDuration(turn: Int): Float? {
        if (!fsDynamicTurns) return null
        val t = turn.coerceAtLeast(0)
        val max = maxTurns.coerceAtLeast(1)
        val ratio = kotlin.math.sqrt(t.toDouble() / max)
        return (fsTurnTime + (fsMaxTurnTime - fsTurnTime) * ratio).toFloat()
    }

    var baseRuleset: String = BaseRuleset.Civ_V_GnK.fullName
    var mods = LinkedHashSet<String>()

    var maxTurns = 500

    var acceptedModCheckErrors = ""

    fun clone(): GameParameters {
        val parameters = GameParameters()
        parameters.difficulty = difficulty
        parameters.speed = speed
        parameters.randomNumberOfPlayers = randomNumberOfPlayers
        parameters.minNumberOfPlayers = minNumberOfPlayers
        parameters.maxNumberOfPlayers = maxNumberOfPlayers
        parameters.players = ArrayList(players)
        parameters.randomNumberOfCityStates = randomNumberOfCityStates
        parameters.minNumberOfCityStates = minNumberOfCityStates
        parameters.maxNumberOfCityStates = maxNumberOfCityStates
        parameters.numberOfCityStates = numberOfCityStates
        parameters.enableRandomNationsPool = enableRandomNationsPool
        parameters.randomNationsPool = ArrayList(randomNationsPool)
        parameters.noCityRazing = noCityRazing
        parameters.noBarbarians = noBarbarians
        parameters.ragingBarbarians = ragingBarbarians
        parameters.oneCityChallenge = oneCityChallenge
        // godMode intentionally reset on clone
        parameters.nuclearWeaponsEnabled = nuclearWeaponsEnabled
        parameters.espionageEnabled = espionageEnabled
        parameters.noStartBias = noStartBias
        parameters.shufflePlayerOrder = shufflePlayerOrder
        parameters.victoryTypes = ArrayList(victoryTypes)
        parameters.startingEra = startingEra
        parameters.showCivilizationStats = showCivilizationStats
        parameters.showDemographics = showDemographics
        parameters.showRankings = showRankings
        parameters.showCharts = showCharts
        parameters.hideOtherCivilizationStats = hideOtherCivilizationStats
        parameters.showAdditionalRankingTypes = showAdditionalRankingTypes
        parameters.isOnlineMultiplayer = isOnlineMultiplayer
        parameters.multiplayerServerUrl = multiplayerServerUrl
        parameters.anyoneCanSpectate = anyoneCanSpectate
        parameters.minutesUntilSkipTurn = minutesUntilSkipTurn
        parameters.minutesUntilForceResign = minutesUntilForceResign
        parameters.minutesRecoveredPerTurn = minutesRecoveredPerTurn
        parameters.simultaneousTurns = simultaneousTurns
        parameters.fsDynamicTurns = fsDynamicTurns
        parameters.fsTurnTime = fsTurnTime
        parameters.fsMaxTurnTime = fsMaxTurnTime
        parameters.fsSettleLockSeconds = fsSettleLockSeconds
        parameters.fsTeamCount = fsTeamCount
        parameters.fsTeams = fsTeams?.map { it.copyOf() }?.toTypedArray()
        parameters.viaLobby = viaLobby
        parameters.baseRuleset = baseRuleset
        parameters.mods = LinkedHashSet(mods)
        parameters.maxTurns = maxTurns
        parameters.acceptedModCheckErrors = acceptedModCheckErrors
        return parameters
    }

    // For debugging and GameStarter console output
    override fun toString() = sequence {
            yield("$difficulty $speed $startingEra")
            yield("${players.count { it.playerType == PlayerType.Human }} ${PlayerType.Human}")
            yield("${players.count { it.playerType == PlayerType.AI }} ${PlayerType.AI}")
            if (randomNumberOfPlayers) yield("Random number of Players: $minNumberOfPlayers..$maxNumberOfPlayers")
            if (randomNumberOfCityStates) yield("Random number of City-States: $minNumberOfCityStates..$maxNumberOfCityStates")
            else yield("$numberOfCityStates CS")
            if (isOnlineMultiplayer) yield("Online Multiplayer")
            if (noBarbarians) yield("No barbs")
            if (ragingBarbarians) yield("Raging barbs")
            if (oneCityChallenge) yield("OCC")
            if (!nuclearWeaponsEnabled) yield("No nukes")
            if (godMode) yield("God mode")
            yield("Enabled Victories: " + victoryTypes.joinToString())
            yield(baseRuleset)
            yield(if (mods.isEmpty()) "no mods" else mods.joinToString(",", "mods=(", ")", 6) )
        }.joinToString(prefix = "(", postfix = ")")

    /** Get all mods including base
     *
     *  The returned Set is ordered base first, then in the order they are stored in a save.
     *  This creates a fresh instance, and the caller is allowed to mutate it.
     */
    fun getModsAndBaseRuleset() =
        LinkedHashSet<String>(mods.size + 1).apply {
            add(baseRuleset)
            addAll(mods)
        }
}
