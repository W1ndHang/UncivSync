package unciv.fs.local;

import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.managers.ReligionState;
import com.unciv.models.ruleset.Belief;
import com.unciv.models.ruleset.BeliefType;
import com.unciv.models.ruleset.unique.UniqueType;
import java.util.*;

/** 一次验证整组选项后调用核心，不能逐个信条提前推进宗教状态机。 */
final class FsReligionActions {
    static boolean choose(Civilization civ, Map<String, Object> data) {
        if (civ == null || !civ.getGameInfo().isReligionEnabled()) return false;
        var manager = civ.getReligionManager();
        var state = manager.getReligionState();
        var ruleset = civ.getGameInfo().getRuleset();
        Object names = data.get("beliefs");
        if (!(names instanceof List<?>)) {
            if (!(data.get("belief") instanceof String)) { System.err.println("[religion] reject: no beliefs"); return false; }
            names = List.of(data.get("belief"));
        }
        var beliefs = new ArrayList<Belief>();
        var seen = new HashSet<String>();
        for (Object name : (List<?>) names) {
            if (!(name instanceof String) || !seen.add((String) name)) return false;
            Belief belief = ruleset.getBeliefs().get(name);
            if (belief == null) { System.err.println("[religion] reject: unknown belief " + name); return false; }
            if (manager.getReligionWithBelief(belief) != null) { System.err.println("[religion] reject: belief taken " + name); return false; }
            for (var unique : belief.getUniqueObjects()) {
                if (unique.getType() == UniqueType.OnlyAvailable && !unique.conditionalsApply(civ.getState())) return false;
                if (unique.getType() == UniqueType.Unavailable && unique.conditionalsApply(civ.getState())) return false;
            }
            beliefs.add(belief);
        }
        if (beliefs.isEmpty()) { System.err.println("[religion] reject: empty"); return false; }
        var required = new EnumMap<BeliefType, Integer>(BeliefType.class);
        if (state == ReligionState.None || state == ReligionState.Pantheon) {
            if (!manager.canFoundOrExpandPantheon()) return false;
            required.put(BeliefType.Pantheon, 1);
        } else if (state == ReligionState.FoundingReligion) {
            required.putAll(manager.getBeliefsToChooseAtFounding());
        } else if (state == ReligionState.EnhancingReligion) {
            required.putAll(manager.getBeliefsToChooseAtEnhancing());
        } else {
            required.putAll(manager.freeBeliefsAsEnums());
        }
        if (required.values().stream().mapToInt(Integer::intValue).sum() != beliefs.size()) {
            System.err.println("[religion] reject: count mismatch required=" + required + " got=" + beliefs.size());
            return false;
        }
        for (var belief : beliefs) {
            BeliefType type = belief.getType();
            if (required.getOrDefault(type, 0) == 0) type = BeliefType.Any;
            int remaining = required.getOrDefault(type, 0);
            if (remaining <= 0) return false;
            required.put(type, remaining - 1);
        }
        boolean useFree = manager.usingFreeBeliefs();
        try {
            if (state == ReligionState.FoundingReligion) {
                String name = FsOpHandler.asStr(data.get("religionName"));
                String display = FsOpHandler.asStr(data.get("displayName"));
                if (name == null || display == null || display.isBlank() || display.length() > 100
                        || !ruleset.getReligions().contains(name) || civ.getGameInfo().getReligions().containsKey(name)) return false;
                // internal 方法的模块后缀由编译器生成，按签名和前缀定位。
                var method = Arrays.stream(manager.getClass().getMethods())
                        .filter(m -> m.getName().startsWith("foundReligion$")
                                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class, String.class}))
                        .findFirst().orElseThrow();
                method.invoke(manager, display, name);
            }
            manager.chooseBeliefs(beliefs, useFree);
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("核心创教入口执行失败", e);
        }
    }
}
