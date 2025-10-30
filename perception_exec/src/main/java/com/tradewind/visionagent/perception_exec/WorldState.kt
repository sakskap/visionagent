package com.tradewind.visionagent.perception_exec

data class Bounds(val x:Int, val y:Int, val w:Int, val h:Int)

data class A11yNode(
    val id:String, val cls:String?, val text:String?, val desc:String?,
    val resId:String?, val b:Bounds, val clickable:Boolean, val enabled:Boolean, val focused:Boolean
)

data class AccessibilitySnapshot(
    val ts: Long, val app:String?, val nodes: List<A11yNode>
)

data class Screenshot(
    val id:String, val ts: Long, val w:Int, val h:Int, val jpeg: ByteArray, val roi: List<Bounds> = emptyList()
)

data class RecentAction(val ts:Long, val type:String, val target:String?, val status:String, val note:String?=null)

data class WorldState(
    val screenshot: Screenshot?,
    val a11y: AccessibilitySnapshot,
    val recent: List<RecentAction> = emptyList(),
    val fpsHint: Int = 2
)
