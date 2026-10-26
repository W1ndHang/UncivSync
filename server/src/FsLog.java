// FsLog.java - Chinese operational log for the server console.
// Translates internal op names into human-readable Chinese so an operator watching the
// server console can tell at a glance what each player is doing.
package unciv.fs.local;

import java.util.Map;

public final class FsLog {
    private FsLog() {}

    /** Human-readable Chinese label for a unit/city/civ op. Falls back to the raw op name. */
    public static String describeOp(String op) {
        if (op == null) return "未知操作";
        switch (op) {
            // 单位移动 / 状态
            case "unit.move": return "移动单位";
            case "unit.swap": return "交换单位";
            case "unit.gift": return "赠送单位";
            case "unit.attack": return "攻击";
            case "unit.fortify": return "驻扎";
            case "unit.fortifyUntilHealed": return "驻扎直到回血";
            case "unit.sleep": return "睡觉";
            case "unit.sleepUntilHealed": return "睡到回血";
            case "unit.explore": return "探索";
            case "unit.automate": return "自动探索";
            case "unit.stopAutomation": return "停止自动";
            case "unit.disband": return "解散单位";
            case "unit.foundCity": return "建立城市";
            case "unit.createImprovement": return "建造设施";
            case "unit.enhanceReligion": return "增强宗教";
            case "unit.foundReligion": return "创立宗教";
            case "unit.spreadReligion": return "传播宗教";
            case "unit.hurryResearch": return "加速科技";
            case "unit.hurryPolicy": return "加速政策";
            case "unit.hurryWonder": return "加速奇观";
            case "unit.hurryBuilding": return "加速建筑";
            case "unit.tradeMission": return "贸易任务";
            case "unit.setUp": return "架设";
            case "unit.guard": return "守卫";
            case "unit.repair": return "修复";
            case "unit.rename": return "重命名单位";
            case "unit.transform": return "转化单位";
            case "unit.promote": return "单位晋升";
            case "unit.upgrade": return "单位升级";
            // 城市操作
            case "city.addToQueue": return "加入生产队列";
            case "city.addToQueueWithTile": return "加入生产队列(选地块)";
            case "city.annex": return "吞并城市";
            case "city.assignSpecialist": return "分配专家";
            case "city.buyTile": return "购买地块";
            case "city.disableConstruction": return "禁用生产";
            case "city.disableManualSpecialists": return "禁用手动专家";
            case "city.enableManualSpecialists": return "启用手动专家";
            case "city.lockTile": return "锁定地块";
            case "city.unlockTile": return "解锁地块";
            case "city.lowerPriority": return "降低优先级";
            case "city.raisePriority": return "提高优先级";
            case "city.moveEntryToEnd": return "移到队尾";
            case "city.moveQueueEntry": return "调整队列";
            case "city.reassignPopulation": return "重新分配人口";
            case "city.removeFromQueue": return "移出队列";
            case "city.removeFromQueueByName": return "移出队列(按名)";
            case "city.rename": return "重命名城市";
            case "city.saveUnitPromotions": return "保存晋升偏好";
            case "city.sellBuilding": return "变卖建筑";
            case "city.setFocus": return "设置城市焦点";
            case "city.setProduction": return "设置生产";
            case "city.setRazing": return "设置焚城";
            case "city.setUnitSavedPromotion": return "设置单位晋升偏好";
            case "city.stopWorkTile": return "停止工作地块";
            case "city.toggleAvoidGrowth": return "切换抑制增长";
            case "city.workTile": return "工作地块";
            // 外交 / 文明
            case "civ.chooseBeliefs": return "选择信仰";
            case "civ.choosePolicy": return "选择政策";
            case "civ.chooseTech": return "选择科技";
            case "civ.foundReligion": return "创立宗教";
            case "civ.cityConquerChoice": return "处理沦陷城市";
            case "civ.declareWar": return "宣战";
            case "civ.demand": return "提出要求";
            case "civ.demandAccept": return "接受要求";
            case "civ.demandRefuse": return "拒绝要求";
            case "civ.denounce": return "谴责";
            case "civ.diplomaticVote": return "外交投票";
            case "civ.friendshipAccept": return "接受友谊";
            case "civ.friendshipDecline": return "拒绝友谊";
            case "civ.friendshipOffer": return "提议友谊";
            case "civ.makePeace": return "议和";
            case "civ.tribute": return "纳贡";
            case "civ.alertSeen": return "查看通知";
            case "civ.voteResultSeen": return "查看投票结果";
            // 交易
            case "trade.accept": return "接受交易";
            case "trade.offer": return "提出交易";
            case "trade.reject": return "拒绝交易";
            case "trade.retract": return "撤回交易";
            case "tradeRoute.acceptOffer": return "接受贸易路线";
            case "tradeRoute.disconnect": return "断开贸易路线";
            case "tradeRoute.offer": return "提议贸易路线";
            case "tradeRoute.rejectOffer": return "拒绝贸易路线";
            default: return op;
        }
    }

    /** Format a data map's key=value pairs compactly for logging. */
    public static String briefData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return " {" + sb + "}";
    }
}
