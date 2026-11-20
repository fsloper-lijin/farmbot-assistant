package com.farmbot.assistant.bot

/**
 * 游戏「作物列表」的固定排布：一行 4 个，按下面的顺序从左到右、从上到下排。
 * 第 i 个（从 1 起）→ 行 = (i-1)/4 + 1，列 = (i-1)%4 + 1。
 * 顺序由用户 2026-11-18 提供（小麦、胡萝卜、番茄…），后续游戏更新会续在末尾。
 * level 是作物解锁等级（用于「默认最高可种」推算），hour/high 来自游戏分组，
 * 16 小时分两批：香蕉打头 = 高经济（格子上标「高农场币」），草莓打头 = 普通。
 */
data class CropDef(val name: String, val level: Int, val hour: Int, val high: Boolean)

val CROPS: List<CropDef> = listOf(
    CropDef("小麦", 3, 1, false),
    CropDef("胡萝卜", 5, 1, false),
    CropDef("番茄", 5, 1, false),
    CropDef("蘑菇", 10, 1, false),
    CropDef("土豆", 10, 1, false),
    CropDef("玉米", 10, 8, false),
    CropDef("草莓", 20, 16, false),
    CropDef("向日葵", 20, 1, false),
    CropDef("青椒", 25, 8, false),
    CropDef("大蒜", 30, 16, false),
    CropDef("香蕉", 40, 16, true),
    CropDef("蓝莓", 20, 32, false),
    CropDef("黄瓜", 25, 1, false),
    CropDef("茄子", 15, 8, false),
    CropDef("南瓜", 10, 16, false),
    CropDef("柚子", 25, 16, true),
    CropDef("卷心菜", 20, 32, false),
    CropDef("白萝卜", 10, 1, false),
    CropDef("葡萄", 30, 8, false),
    CropDef("生菜", 20, 16, false),
    CropDef("西瓜", 20, 16, true),
    CropDef("辣椒", 20, 32, false),
    CropDef("洋葱", 30, 1, false),
    CropDef("花菜", 15, 8, false),
    CropDef("猕猴桃", 20, 32, false),
    CropDef("花生", 50, 16, false),
    CropDef("木瓜", 40, 16, true),
    CropDef("荞麦", 20, 32, false),
    CropDef("莴笋", 30, 1, false),
    CropDef("红枣", 50, 8, false),
    CropDef("棉花", 40, 16, false),
    CropDef("橘子", 60, 16, true),
    CropDef("菠萝", 20, 32, false),
    CropDef("四季豆", 100, 1, false),
    CropDef("冬瓜", 10, 8, false),
    CropDef("李子", 30, 16, false),
    CropDef("杨桃", 30, 16, true),
    CropDef("梨子", 20, 32, false),
    CropDef("甘蔗", 15, 1, false),
    CropDef("葫芦", 20, 8, false),
    CropDef("桃子", 50, 16, false),
    CropDef("柠檬", 40, 16, true),
    CropDef("樱桃", 20, 32, false),
    CropDef("桑葚", 125, 1, false),
    CropDef("杏子", 60, 8, false),
    CropDef("哈密瓜", 20, 16, false),
    CropDef("枇杷", 80, 16, true),
    CropDef("灯笼果", 20, 32, false),
    CropDef("树莓", 135, 1, false),
    CropDef("芒果", 30, 8, false),
    CropDef("山竹", 40, 16, false),
    CropDef("山楂", 60, 16, true),
    CropDef("莲花", 20, 32, false),
    CropDef("咖啡豆", 107, 1, false),
    CropDef("火龙果", 40, 8, false),
    CropDef("石榴", 40, 16, false),
    CropDef("牛油果", 60, 16, true),
    CropDef("龙胆花", 20, 32, false),
    CropDef("仙人掌果", 80, 1, false),
    CropDef("椰子", 40, 8, false),
    CropDef("荔枝", 40, 16, false),
    CropDef("百合", 80, 16, true),
    CropDef("笋", 20, 32, false),
    CropDef("丝瓜", 40, 1, false),
    CropDef("花椰菜", 40, 8, false),
    CropDef("蔓越莓", 40, 16, false),
    CropDef("洋桔梗", 120, 16, true),
    CropDef("生姜", 20, 32, false),
    CropDef("白菜", 80, 1, false),
    CropDef("番石榴", 80, 8, false),
    CropDef("柿子", 40, 16, false),
    CropDef("紫绣球", 60, 16, true),
    CropDef("昙花", 20, 32, false),
    CropDef("雪莲果", 80, 1, false),
    CropDef("羽衣甘蓝", 40, 8, false),
    CropDef("龙眼", 40, 16, false),
    CropDef("鸢尾花", 120, 16, true),
    CropDef("芍药", 20, 32, false),
    CropDef("秋葵", 60, 16, false),
    CropDef("风信子", 80, 16, true),
)

/** 作物在网格中的 0 基行列 */
fun cellOf(name: String): Pair<Int, Int>? {
    val i = CROPS.indexOfFirst { it.name == name }
    return if (i < 0) null else i / 4 to i % 4
}

fun cropOf(name: String): CropDef? = CROPS.firstOrNull { it.name == name }
