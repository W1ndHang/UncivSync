package com.unciv.logic

import kotlinx.serialization.json.JsonObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 反射字段搬运工具（第三十一批起为 per-entity 全字段包服务）。
 *
 * sim 的单位/城市快照是**官方序列化器整对象**。客户端按 id 找到现有实例后，把权威
 * 对象的字段搬进现有实例：
 * - 字段清单由反射穷举自动生成（排除 @Transient/static，含类自身与父类链）——
 *   核心加字段自动覆盖，不存在手写清单漏项；
 * - @Transient（currentTile 等）不被触碰 → 现有实例的瞬态引用与视图引用保持稳定，
 *   不会产生"影分身"；
 * - **嵌套的项目对象（cityConstructions / population / promotions…）递归搬运字段
 *   而不是整引用替换**（2026-09-30 第一次修复）：整替换会把一个"反序列化后从未接线"
 *   的对象塞进活实体 —— 它内部的 `lateinit city/unit` 反向引用是 null，CityScreen /
 *   CityStats 一读就 UninitializedPropertyAccessException（"生产队列加入/移除即崩"
 *   的根因）。递归搬运保住对象身份，反向引用与视图引用天然不受影响。
 * - **按"obj JSON 里实际在场的字段"搬运**（2026-09-30 第二次修复）：服务端快照
 *   序列化已关闭默认值省略（FsGameState.SNAPSHOT_JSON，usePrototypes=false），
 *   字段在场 = 权威**显式写出了这个值** —— 包括等于默认值的值（行动力 0、
 *   health 100、非傀儡 false…），必须照搬；字段缺席 = 权威未写（可空引用被官方
 *   序列化省略），不动本地现值。旧的"值 == 新实例默认值就跳过"恰恰把"权威把数值
 *   清零/复位"这类状态变更吞掉（升级后客户端还能动的根因）。
 * - 搬完由调用方重跑对应 setTransients（官方读档同款瞬态重链）。
 */
object FsStateSplice {

    private val fieldCache = HashMap<Pair<Class<*>, Class<*>>, List<Field>>()

    /**
     * 某类的序列化字段清单（排除 @Transient/static）。
     * 沿父类链上行，**包含 [inclusiveBase] 自身声明的字段**，不含其父类之上的部分。
     * 传 Any::class.java 即全链。
     */
    @Synchronized
    private fun fieldsOf(cls: Class<*>, inclusiveBase: Class<*>): List<Field> =
        fieldCache.getOrPut(cls to inclusiveBase) {
            val out = ArrayList<Field>()
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    val mods = f.modifiers
                    if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue
                    f.isAccessible = true
                    out.add(f)
                }
                if (c == inclusiveBase) break
                c = c.superclass
            }
            out
        }

    /**
     * 把 [src] 的序列化字段逐个搬进 [dst]（同类实例，引用拷贝）。
     * [inclusiveBase]：搬运字段声明所处的最上层类（含自身）。
     * @return 实际搬动的字段数
     */
    fun copyFields(dst: Any, src: Any, inclusiveBase: Class<*> = Any::class.java): Int =
        copyFieldsImpl(dst, src, inclusiveBase)

    /**
     * 按"obj JSON 里实际在场的字段"搬运（[element] 是服务端快照 obj 段解析出的
     * JSON 对象，与 [src] 是同一段文本的反序列化结果）。
     *
     * 语义：**在场 = 权威显式写出的值，照搬（哪怕等于默认值）；缺席 = 权威未写，
     * 不动本地现值。** 见对象级文档"第二次修复"。
     */
    fun copyJsonFields(dst: Any, src: Any, element: JsonObject, inclusiveBase: Class<*>): Int {
        require(dst.javaClass == src.javaClass) { "splice 类型不一致: ${dst.javaClass} vs ${src.javaClass}" }
        var moved = 0
        for (f in fieldsOf(dst.javaClass, inclusiveBase)) {
            val child = element[f.name] ?: continue
            val v = f.get(src) ?: continue
            // 嵌套项目对象：递归搬字段（保持 dst 侧对象身份），不整引用替换。
            // 递归的"在场"基准 = 该嵌套对象自己的 JSON 子树。
            if (child is JsonObject && isSpliceable(v)) {
                val dstV = f.get(dst)
                if (dstV != null && dstV.javaClass == v.javaClass) {
                    moved += copyJsonFields(dstV, v, child, Any::class.java)
                    continue
                }
            }
            f.set(dst, v)
            moved++
        }
        return moved
    }

    private fun copyFieldsImpl(dst: Any, src: Any, inclusiveBase: Class<*>): Int {
        require(dst.javaClass == src.javaClass) { "splice 类型不一致: ${dst.javaClass} vs ${src.javaClass}" }
        var moved = 0
        for (f in fieldsOf(dst.javaClass, inclusiveBase)) {
            val v = f.get(src) ?: continue
            f.set(dst, v)
            moved++
        }
        return moved
    }

    /**
     * 是否为"可递归搬运"的嵌套对象：只认项目自己的数据类。
     * 原始类型/包装类/字符串/枚举/数组/集合/Map 一律整值替换（它们是纯数据，
     * 元素没有反向引用；集合元素在官方存档里也是字符串/数字）。
     */
    private fun isSpliceable(v: Any): Boolean {
        val c = v.javaClass
        if (c == String::class.java || c.isEnum) return false
        if (v is Number || v is Boolean) return false
        if (c.isArray || v is Collection<*> || v is Map<*, *>) return false
        return c.name.startsWith("com.unciv.")
    }
}
