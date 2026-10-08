package com.autoball.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * UI 组件门面。
 *
 * 原本 1237 行的单一 object 混了四类职责（半框 / 弹窗 / 浮层 / 小部件），
 * 改一处要在巨型文件里翻找。按常驻需求 R-001 已拆为四个按职责分离的库：
 *
 * - [UiSheets] 底部半框、帮助气泡、页标题、说明文字
 * - [UiDialogs] 通用弹窗、紧凑弹窗行、表单行
 * - [UiOverlays] 弹出菜单、变量提示、动作宫格、版本条、Toast、气泡
 * - [UiBits] 按钮、分组气泡、徽标、标签、分区标题、胶囊
 *
 * 本门面保留 `Ui.xxx(...)` 的调用方式——现有 148 处调用点无需改动，
 * 全部转发到对应实现，避免为纯重构承担大面积改动的回归风险。
 */
typealias SheetBuilder = UiSheets.SheetBuilder
typealias DialogBuilder = UiDialogs.DialogBuilder

object Ui {

    fun sheet(ctx: Context, title: String) : UiSheets.SheetBuilder = UiSheets.sheet(ctx, title)
    fun helpBubble(anchor: View, title: String, text: String) = UiSheets.helpBubble(anchor, title, text)
    fun pageTitle(ctx: Context, text: String) : TextView = UiSheets.pageTitle(ctx, text)
    fun note(ctx: Context, text: String) : TextView = UiSheets.note(ctx, text)
    fun sheetOption(ctx: Context, icon: String, iconColor: Int, title: String,
                    desc: String, onClick: () -> Unit) = UiSheets.sheetOption(ctx, icon, iconColor, title, desc, onClick)
    fun adRow(ctx: Context, label: String, value: String, set: Boolean,
              help: String? = null, onValue: () -> Unit) = UiDialogs.adRow(ctx, label, value, set, help, onValue)
    fun adSec(ctx: Context) : android.view.View = UiDialogs.adSec(ctx)
    fun adNumber(ctx: Context, value: String, unit: String,
                 hint: String = "") = UiDialogs.adNumber(ctx, value, unit, hint)
    fun adNumberValue(row: LinearLayout) : String = UiDialogs.adNumberValue(row)
    fun adText(ctx: Context, value: String, hint: String) : EditText = UiDialogs.adText(ctx, value, hint)
    fun dialog(ctx: Context, title: String) : UiDialogs.DialogBuilder = UiDialogs.dialog(ctx, title)
    fun row(ctx: Context, label: String, value: String, help: String? = null,
            onClick: (() -> Unit)? = null) = UiDialogs.row(ctx, label, value, help, onClick)
    fun switchRow(ctx: Context, label: String, init: Boolean, onChange: (Boolean) -> Unit) = UiDialogs.switchRow(ctx, label, init, onChange)
    fun numEdit(ctx: Context, value: String, hint: String = "") : EditText = UiDialogs.numEdit(ctx, value, hint)
    fun edit(ctx: Context, value: String, hint: String) : EditText = UiDialogs.edit(ctx, value, hint)
    fun popMenu(ctx: Context, anchor: View, items: List<String>,
                selectedIndex: Int, onPick: (Int) -> Unit) = UiOverlays.popMenu(ctx, anchor, items, selectedIndex, onPick)
    fun popMenu(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) = UiOverlays.popMenu(anchor, items, selected, onPick)
    fun varTip(ctx: Context, anchorView: View, target: EditText?,
               vars: List<Pair<String, String>>) = UiOverlays.varTip(ctx, anchorView, target, vars)
    fun menu(ctx: Context, anchorView: View, items: List<Pair<String, Boolean>>,
             onPick: (Int) -> Unit) = UiOverlays.menu(ctx, anchorView, items, onPick)
    fun actionGrid(ctx: Context, items: List<Pair<String, String>>,
                   onPick: (Int) -> Unit) = UiOverlays.actionGrid(ctx, items, onPick)
    fun versionBar(ctx: Context, version: String, desc: String,
                   onClick: () -> Unit) : LinearLayout = UiOverlays.versionBar(ctx, version, desc, onClick)
    fun toast(ctx: Context, msg: String) = UiOverlays.toast(ctx, msg)
    fun tip(anchor: View, title: String, text: String) = UiOverlays.tip(anchor, title, text)
    fun button(ctx: Context, text: String, primary: Boolean) : TextView = UiBits.button(ctx, text, primary)
    fun bubbleChip(ctx: Context, text: String, colorIdx: Int, active: Boolean) : TextView = UiBits.bubbleChip(ctx, text, colorIdx, active)
    fun badge(ctx: Context, text: String, rec: Boolean) : TextView = UiBits.badge(ctx, text, rec)
    fun tag(ctx: Context, text: String, kind: Int) : TextView = UiBits.tag(ctx, text, kind)
    fun section(ctx: Context, text: String) : TextView = UiBits.section(ctx, text)
    fun chip(ctx: Context, text: String, active: Boolean, onClick: () -> Unit) : TextView = UiBits.chip(ctx, text, active, onClick)
    fun runButton(ctx: Context, onClick: () -> Unit) : TextView = UiBits.runButton(ctx, onClick)
    fun check(ctx: Context, on: Boolean) : TextView = UiBits.check(ctx, on)
    fun hint(ctx: Context, text: String) : TextView = UiBits.hint(ctx, text)
}
