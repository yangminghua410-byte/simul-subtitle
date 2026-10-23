package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private val prefs get() = Prefs.prefs
private const val REPO = "https://github.com/KorenKrita/simul-subtitle"

/** 界面文字 helper: returns 中文 or 日文 for the current interface language ([Prefs.lang]). */
private fun T(zh: String, ja: String) = if (prefs.lang == Prefs.LANG_JA) ja else zh

private fun open(url: String) = runCatching { Desktop.getDesktop().browse(URI(url)) }
private fun reveal(f: File) = runCatching {
    if (isWindows) ProcessBuilder("explorer.exe", "/select,", f.path).start()
    else ProcessBuilder("/usr/bin/open", "-R", f.path).start()
}
private fun sizeText(b: Long) = if (b >= 1e6) "%.1f MB".format(b / 1e6) else "%.0f KB".format(b / 1e3)

/** Starts captions from the home screen, the menu bar or the toolbar; null when it cannot, with the reason. */
private fun startCaptions(): String? {
    if (!prefs.byokReady) return T("LLM API 还没填完整：请在设置「翻译」里填好服务地址、模型 ID 和 API Key 并保存。", "LLM API が未設定です：設定「翻訳」でサービスURL・モデルID・API Key を入力して保存してください。")
    if (!sysaudioFile.canExecute()) return T("找不到系统声音采集程序（", "システム音声キャプチャプログラムが見つかりません（") + sysaudioFile.path + "）"
    // a 测试连接 still running would probe the same service the session probes on first use (billed twice)
    byokTest.value?.cancel(); byokTest.value = null
    CaptionService.start(sysaudioFile)
    return null
}

fun app() = application {
    var mainOpen by remember { mutableStateOf(true) }
    var startError by remember { mutableStateOf<String?>(null) }
    val running by CaptionService.runningState
    val dark = when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    // Windows: the taskbar's own theme (it can be dark while apps are light), checked again every few seconds
    var trayDark by remember { mutableStateOf(taskbarDark()) }
    if (isWindows) LaunchedEffect(Unit) { while (true) { delay(5_000); trayDark = taskbarDark() } }

    Tray(icon = TrayIcon(running, trayDark), tooltip = T("同传字幕", "同伝字幕"), menu = {
        if (running) {
            if (!CaptionService.endedState.value) Item(if (CaptionService.pausedState.value) T("继续字幕", "字幕を再開") else T("暂停字幕", "字幕を一時停止"), onClick = { CaptionService.togglePause() })
            Item(T("停止字幕", "字幕を停止"), onClick = { CaptionService.stop() })
        }
        else Item(T("开始字幕", "字幕を開始"), onClick = { startError = startCaptions(); if (startError != null) mainOpen = true })
        Item(T("打开同传字幕", "同伝字幕を開く"), onClick = { mainOpen = true })
        Separator()
        Item(T("退出", "終了"), onClick = { CaptionService.stop(); exitApplication() })
    })

    // the caption window lives with the session, not with the main window
    CaptionService.current?.takeIf { running }?.let { s ->
        Themed(true) { OverlayWindow(s.overlay, prefs, onStop = { CaptionService.stop() }) }
    }

    if (mainOpen) {
        val state = rememberWindowState(size = DpSize(520.dp, 820.dp))
        // closing the window keeps the app (and its captions) in the menu bar
        Window(onCloseRequest = { mainOpen = false }, state = state, title = T("同传字幕", "同伝字幕"), icon = painterResource("icon.png")) {
            // the title bar is the system's: follow the page colour, not just macOS's own setting
            if (!isWindows) LaunchedEffect(dark) { window.rootPane.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua") }
            Themed(dark) {
                Box(Modifier.fillMaxSize().background(C.bg)) { MainScreen(startError) { startError = it } }
            }
        }
    }
    AutoUpdateCheck()
}

/**
 * Windows: true when the taskbar is dark (the default), from the registry; on macOS false (the icon is a template
 * image there, which the menu bar colours itself).
 */
private fun taskbarDark(): Boolean = isWindows && runCatching {
    com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") == 0
}.getOrDefault(true)

/**
 * The menu bar / notification area icon: a caption card, filled while captions run. Drawn, so it follows no file.
 * Black on macOS (a template image: the menu bar draws it white when it is dark), white on a dark Windows taskbar.
 */
private class TrayIcon(val on: Boolean, val dark: Boolean) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.width; val h = size.height
        val c = if (dark) Color.White else Color.Black
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.14f)
        val tl = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.2f)
        val sz = Size(w * 0.84f, h * 0.6f)
        if (on) drawRoundRect(c, tl, sz, r)
        else drawRoundRect(c, tl, sz, r, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.07f))
        // the lines on a filled card are cut out (transparent), so a template image keeps them
        val blend = if (on) androidx.compose.ui.graphics.BlendMode.Clear else androidx.compose.ui.graphics.BlendMode.SrcOver
        drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.42f), Size(w * 0.52f, h * 0.07f), blendMode = blend)
        drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.56f), Size(w * 0.36f, h * 0.07f), blendMode = blend)
    }
    override fun equals(other: Any?) = other is TrayIcon && other.on == on && other.dark == dark
    override fun hashCode() = on.hashCode() * 31 + dark.hashCode()
}

@Composable
private fun MainScreen(startError: String?, setStartError: (String?) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    // 设置 › 翻译, where the LLM API is filled in (the home card's service row and its 去填写)
    val openTranslate = { tab = 1; settings = true }
    Column(Modifier.fillMaxSize()) {
        if (!settings) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(T("同传字幕", "同伝字幕"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Grow()
                TextBtn(T("设置", "設定")) { settings = true }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) { Home(startError, setStartError, openTranslate) }
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
                TextBtn(T("‹ 返回", "‹ 戻る")) { settings = false }
                Text(T("设置", "設定"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.padding(horizontal = 18.dp)) {
                Seg(listOf(0 to T("字幕", "字幕"), 1 to T("翻译", "翻訳"), 2 to T("页面", "表示"), 3 to T("记录", "履歴"), 4 to T("关于", "情報")), tab) { tab = it }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
                when (tab) { 0 -> CaptionTab(); 1 -> TranslateTab(); 2 -> PageTab(); 3 -> LogsTab(); else -> AboutTab() }
            }
        }
    }
}

// ---- home --------------------------------------------------------------------------------------------------

@Composable
private fun Home(startError: String?, setStartError: (String?) -> Unit, openTranslate: () -> Unit) {
    val running by CaptionService.runningState
    val ended by CaptionService.endedState
    val paused by CaptionService.pausedState
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) {
        Text(T("界面语言", "表示言語"), color = C.text2, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Dropdown(listOf("中文", "日本語"), if (prefs.lang == Prefs.LANG_JA) 1 else 0, modifier = Modifier.width(120.dp)) {
            prefs.lang = if (it == 0) Prefs.LANG_ZH else Prefs.LANG_JA
        }
    }
    Text(T("把视频、直播里的日语和英语，实时变成字幕", "動画やライブ配信の日本語・英語をリアルタイムに字幕化します"), color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
    /** the language name being typed for 其他; null while that editor is closed */
    var typing by remember { mutableStateOf<String?>(null) }
    // one card: status, the two languages, the one action, and the LLM API it translates with
    Card {
        @Composable fun status(color: Color, title: String, body: String) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color); Spacer(Modifier.width(10.dp)); Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(body, color = C.text2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 19.dp, top = 6.dp, bottom = 12.dp))
        }
        @Composable fun bigButton(fill: Color, onClick: () -> Unit, content: @Composable () -> Unit) =
            Box(Modifier.fillMaxWidth().height(44.dp).background(fill, RoundedCornerShape(10.dp)).clickable { onClick() },
                contentAlignment = Alignment.Center) { content() }
        val ready = prefs.byokReady
        when {
            running && ended -> status(C.warn, T("字幕已经停了", "字幕は停止しました"), T("原因写在字幕框里。点下面的按钮关掉它，然后可以重新开始。", "理由は字幕欄に書いてあります。下のボタンで閉じてから、やり直せます。"))
            running && paused -> status(C.warn, T("字幕已暂停", "字幕を一時停止中"), T("声音不再识别，字幕窗口保留。点「继续字幕」接着出字幕。", "音声は認識しなくなり、字幕ウィンドウは残ります。「字幕を再開」で字幕を続けます。"))
            running -> status(C.ok, T("字幕正在显示", "字幕を表示中"), T("回到你要看的视频或直播就行。\n点一下字幕，可以调大小、暂停或停止；关掉这个窗口字幕也会继续，", "見たい動画やライブに戻ればOKです。\n字幕をクリックするとサイズ調整・一時停止・停止ができます。このウィンドウを閉じても字幕は続き、") + (if (isWindows) T("任务栏右下角", "タスクバー右下") else T("菜单栏", "メニューバー")) + T("的图标里可以停止。", "のアイコンから停止できます。"))
            !ready -> status(C.warn, T("先填翻译用的 LLM API", "先に翻訳用の LLM API を設定"), T("填好服务地址、模型 ID 和 API Key，点「测试连接」，就能开始。声音识别已经装在 App 里，不用下载。", "サービスURL・モデルID・APIキーを入力し「テスト接続」を押せば開始できます。音声認識はアプリ内蔵で、ダウンロード不要です。"))
            startError != null -> status(C.err, T("还不能开始", "まだ開始できません"), startError)
            else -> status(C.brand, T("可以开始了", "開始できます"), T("点下面的按钮开始。", "下のボタンを押して開始。") + if (isWindows) "" else T("第一次会问你是否允许同传字幕录制系统声音，请选「允许」。", "初回は同伝字幕がシステム音声を録音する許可を求められます。「許可」を選んでください。"))
        }
        // what the captions listen to: system/app audio, or the microphone (a running session already opened its source)
        if (!running) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(T("字幕听", "字幕を聞く"), color = C.text2, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Dropdown(listOf(T("播放的声音", "再生音"), T("麦克风", "マイク")), if (prefs.audioSource == Prefs.SOURCE_MIC) 1 else 0, modifier = Modifier.weight(1f)) {
                    prefs.audioSource = if (it == 0) Prefs.SOURCE_PLAYBACK else Prefs.SOURCE_MIC
                }
            }
        }
        // what is spoken → what the captions are in (a running session: from the next sentence)
        val sources = Lang.SOURCES
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dropdown(sources.map { it?.zh ?: T("不确定（日语或英语）", "不明（日本語または英語）") }, sources.indexOf(prefs.sourceLang).coerceAtLeast(0), modifier = Modifier.weight(1f)) {
                prefs.sourceLang = sources[it]
            }
            Text("→", color = C.muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp))
            // the presets, the typed language in use (if any), and 其他 to type one below
            val presets = Target.PRESETS
            val current = prefs.targetLang
            val targets = if (current in presets) presets else presets + current
            Dropdown(targets.map { it.zh } + T("自己填…", "自分で入力…"), targets.indexOf(current), modifier = Modifier.weight(1f)) {
                if (it == targets.size) typing = current.takeIf { c -> c.lang == null }?.name ?: ""
                else { typing = null; prefs.targetLang = targets[it] }
            }
        }
        // 其他: a language name typed in, saved with 确定; 取消 or a blank name keeps the language in use. Shown again by
        // picking 其他 once more (a typed language in use is in the list, so 其他 is never the selected item).
        typing?.let { t ->
            Spacer(Modifier.height(10.dp))
            Field(t, { v -> typing = v.take(Target.MAX_NAME) }, T("例如 繁体中文、粤语、文言文", "例：繁体中文、広東語、文語体"))
            Hint(T("写上想要的语言，会原样交给你填的 LLM 去翻译。", "希望の言語を入力してください。そのままLLMに渡して翻訳します。"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn(T("取消", "キャンセル")) { typing = null }
                Btn(T("确定", "確定"), primary = true, enabled = Target.custom(t) != null) {
                    Target.custom(t)?.let { prefs.targetLang = it }; typing = null
                }
            }
        }
        Hint(if (running) T("视频里说的语言 → 字幕的语言。字幕运行中，改了从下一句开始生效。", "動画で話している言語 → 字幕の言語。字幕表示中は次文から反映されます。") else T("视频里说的语言 → 字幕的语言。知道是哪种语言就选上，翻译会更准。", "動画で話している言語 → 字幕の言語。分かるなら選ぶと翻訳が正確になります。"))
        Spacer(Modifier.height(12.dp))
        when {
            running && ended -> Btn(T("关掉字幕", "字幕を閉じる"), modifier = Modifier.fillMaxWidth()) { CaptionService.stop() }
            running -> {
                bigButton(C.errSoft, { CaptionService.stop() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(C.err, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp)); Text(T("停止字幕", "字幕を停止"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Btn(if (paused) T("▶  继续字幕", "▶  字幕を再開") else T("⏸  暂停字幕", "⏸  字幕を一時停止"), modifier = Modifier.fillMaxWidth()) { CaptionService.togglePause() }
            }
            !ready -> bigButton(C.brand, openTranslate) { Text(T("去填写", "設定へ"), color = C.onBrand, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            else -> bigButton(C.brand, { setStartError(startCaptions()) }) {
                Text(T("▶  开始字幕", "▶  字幕を開始"), color = C.onBrand, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp)); Divider()
        // the LLM API in use; opens 设置 › 翻译
        val host = ByokTranslator.chatUrl(prefs.byokUrl).second?.let { java.net.URI(it).host }
        Row(Modifier.fillMaxWidth().clickable { openTranslate() }.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (ready) {
                val p = prefs.byokProfile.name.takeIf { prefs.byokProfiles.size > 1 }
                T("翻译：", "翻訳：") + (if (p != null) "$p · " else "") + "${prefs.byokModel} · $host"
            } else T("翻译：还没填 LLM API", "翻訳：LLM API が未設定"), color = C.text2, fontSize = 14.sp,
                modifier = Modifier.weight(1f), maxLines = 1)
            Text("›", color = C.muted, fontSize = 18.sp)
        }
    }
    Hint(T("声音只在电脑上识别，不会上传；翻译时只把识别出的文字发给你填的 LLM API。", "音声はPC上でのみ認識され、アップロードされません。翻訳時は認識した文字だけを設定したLLM APIに送信します。"))
}

// ---- 字幕 --------------------------------------------------------------------------------------------------

@Composable
private fun CaptionTab() {
    val sample = remember { OverlayState() }
    sample.lines.value = listOf(CaptionLine(T("这一句已经说完，是正式字幕", "この文は言い終わって正式な字幕です"), if (prefs.showJa) T("（这里显示原文）", "（原文を表示）") else null, true),
        CaptionLine(T("正在说的这一句…", "今言っているこの文…"), if (prefs.showJa) T("（原文）", "（原文）") else null, false))
    sample.diag.value = if (prefs.showStats) T("这里显示识别和翻译用了多久", "ここに認識・翻訳にかかった時間を表示") else null
    Group(T("外观", "外観")) {
        Box(Modifier.fillMaxWidth().background(Color(0xFF3A3A40), RoundedCornerShape(10.dp)).padding(vertical = 16.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            CaptionCard(sample, prefs, modifier = Modifier.fillMaxWidth(prefs.widthFrac.coerceIn(Prefs.MIN_WIDTH, 1f)))
        }
        Label(T("字幕配色", "字幕の配色"))
        // wraps onto more lines: a sideways scroll needs a trackpad (a mouse wheel scrolls the page)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in PALETTES) {
                val sel = p.id == palette(prefs.palette).id
                Row(Modifier.height(40.dp).background(C.card, RoundedCornerShape(20.dp))
                    .border(if (sel) 2.dp else 1.dp, if (sel) C.text else C.line, RoundedCornerShape(20.dp))
                    .clickable { prefs.palette = p.id }.padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(Color(p.bg or 0xFF000000), CircleShape).border(1.dp, Color(0x33888888), CircleShape),
                        contentAlignment = Alignment.Center) { Text("字", color = Color(p.zh), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    Text(T(p.label, paletteLabelJa(p.id)), fontSize = 14.sp, color = if (sel) C.text else C.text2)
                }
            }
        }
        Label(T("文字大小", "文字サイズ") + "  ${Math.round(prefs.textSp.coerceIn(MIN_SP, MAX_SP))}")
        Slider2(Math.round(prefs.textSp.coerceIn(MIN_SP, MAX_SP)).toFloat(), MIN_SP..MAX_SP, (MAX_SP - MIN_SP).toInt() - 1) { prefs.textSp = Math.round(it).toFloat() }
        Label(T("背景浓淡", "背景の濃さ") + "  ${Math.round(prefs.bgAlpha * 100)}%")
        Slider2(prefs.bgAlpha * 100, 0f..100f, 19) { prefs.bgAlpha = Math.round(it / 5) * 5 / 100f }
    }
    Group(T("显示", "表示")) {
        SwitchRow(T("同时显示原文", "原文も同時に表示"), null, prefs.showJa) { prefs.showJa = it }
        Divider()
        SwitchRow(T("显示识别和翻译用时", "認識・翻訳の所用時間を表示"), null, prefs.showStats) { prefs.showStats = it }
    }
    Group(T("字幕框大小和保留几句", "字幕枠のサイズと残す文数")) {
        Label(T("字幕宽度", "字幕の幅") + "  ${Math.round(prefs.widthFrac * 100)}%")
        Slider2((Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f), 40f..100f, 11) { prefs.widthFrac = Math.round(it / 5) * 5 / 100f }
        Label(if (prefs.heightFrac <= 0f) T("字幕高度  自动（跟着内容变）", "字幕の高さ  自動（内容に合わせる）") else T("字幕高度", "字幕の高さ") + "  画面の ${Math.round(prefs.heightFrac * 100)}%")
        Slider2(if (prefs.heightFrac <= 0f) 0f else (Math.round(prefs.heightFrac * 20) * 5f).coerceIn(5f, 40f), 0f..40f, 7) { prefs.heightFrac = Math.round(it / 5) * 5 / 100f }
        Label(T("屏幕上至少留", "画面上に最低でも残す") + "  ${prefs.keepSentences.coerceIn(1, 6)} " + T("句", "文"))
        Slider2(prefs.keepSentences.coerceIn(1, 6).toFloat(), 1f..6f, 4) { prefs.keepSentences = Math.round(it) }
        Label(T("字数超过", "文字数が") + "  ${(Math.round(prefs.keepChars / 10f) * 10).coerceIn(0, 300)} " + T("字后，收起最早的句子", "字を超えたら、最も古い文を畳む"))
        Slider2((Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f), 0f..300f, 29) { prefs.keepChars = Math.round(it / 10) * 10 }
        Hint(T("屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。字幕框也可以直接拖动：点一下字幕，拖四个角上的小箭头。", "画面には言い終わった数文が残り、振り返りやすいです。文数と文字数の両方が上の数字を超えたとき、最も古い文が畳まれます。字幕枠に収まらないときは最新の内容だけ表示。字幕枠はドラッグで移動もできます：字幕をクリックし、四隅の矢印をドラッグ。"))
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Grow()
        TextBtn(T("恢复默认", "デフォルトに戻す")) { prefs.resetStyle(); prefs.setOverlay(null, null, null, Prefs.DEFAULT_WIDTH, 0f) }
    }
}

// ---- 翻译 --------------------------------------------------------------------------------------------------

/** The scene being edited in 设置 › 翻译 › 内容 (the editor's text), null while closed. [old] null = 新建. */
private class SceneDraft(val old: Scene?) {
    var label by mutableStateOf(old?.label ?: "")
    var background by mutableStateOf(old?.background ?: "")
    /** the term rows as typed (原文, 译文); a new scene starts with one empty row */
    var terms by mutableStateOf(old?.terms?.ifEmpty { null } ?: listOf("" to ""))
    /** the language the 译文 are in (a new scene: the captions' language at the time) */
    var termsLang by mutableStateOf(old?.termsLang ?: prefs.targetLang)
    var count by mutableStateOf("20")
    var status by mutableStateOf<String?>(null)
    var generating by mutableStateOf(false)
    var confirm by mutableStateOf(false)
}
private val sceneDraft = mutableStateOf<SceneDraft?>(null)

/**
 * 内容类型: pick a scene, 编辑 it or 新建 one. The editor: name, background, terms one per line; 生成术语 asks the LLM
 * API for up to the typed number of terms and only appends them (lines already there, saved or typed, stay as they
 * are); 保存 checks the text; 删除 a user's scene, 恢复默认 an edited built-in (each confirmed once).
 */
@Composable
private fun Scenes() {
    val scenes = prefs.scenes
    val scene = prefs.scene
    val d = sceneDraft.value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dropdown(scenes.map { it.label }, scenes.indexOfFirst { it.id == scene.id }.coerceAtLeast(0), enabled = d == null,
            modifier = Modifier.weight(1f)) { prefs.scene = scenes[it] }
        Spacer(Modifier.width(8.dp))
        Btn(T("编辑", "編集"), enabled = d == null) { sceneDraft.value = SceneDraft(scene) }
        Spacer(Modifier.width(8.dp))
        Btn(T("新建", "新規作成"), enabled = d == null) { sceneDraft.value = SceneDraft(null) }
    }
    if (d == null) {
        Hint(scene.summary(prefs.targetLang))
        return
    }
    Spacer(Modifier.height(10.dp))
    Text(if (d.old == null) T("新建内容类型", "新しい内容タイプ") else T("编辑「", "「") + d.old.label + T("」", "」を編集"), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Label(T("名字", "名前"))
    Field(d.label, { d.label = it.take(Scene.MAX_LABEL) }, T("例如 游戏实况、某主播的直播", "例：ゲーム実況、ある配信者のライブ"))
    Label(T("背景说明", "背景説明"))
    Area(d.background, { d.background = it }, T("这是什么内容、谁在说话、在聊什么，翻译会参考", "どんな内容か、誰が話しているか、何を話しているか。翻訳が参考にします"), 2)
    Label(T("术语", "用語"))
    // the language the 译文 are in: the terms are used only when the captions are in it
    val captions = prefs.targetLang
    val langs = (Target.PRESETS + d.termsLang + captions).distinct()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(T("译文是", "訳文は"), color = C.text, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
        Dropdown(langs.map { it.zh }, langs.indexOf(d.termsLang), modifier = Modifier.weight(1f)) { d.termsLang = langs[it] }
    }
    Hint(if (d.termsLang == captions) T("现在字幕翻成", "いま字幕は") + captions.zh + T("，这些术语会用上。", "に翻訳中。この用語が使われます。")
        else T("现在字幕翻成", "いま字幕は") + captions.zh + T("，这些", "に翻訳中。この") + d.termsLang.zh + T("术语不会用上；字幕换成", "用語は使われません。字幕を") + d.termsLang.zh + T("才用。", "に変えたときだけ使われます。"))
    Hint(T("左边写视频里说的词，右边写想要的译法，例如 ボス 和 头目。", "左に動画で話している単語、右に希望する訳し方を書きます。例：ボス と 頭目。"))
    // one row of two boxes each; the arrow between them is ours
    d.terms.forEachIndexed { i, (src, dst) ->
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(src, { v -> d.terms = d.terms.toMutableList().also { it[i] = v.take(Scene.MAX_TERM) to it[i].second } }, T("原文", "原文")) }
            Text(Scene.TERM_SEP, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.weight(1f)) { Field(dst, { v -> d.terms = d.terms.toMutableList().also { it[i] = it[i].first to v.take(Scene.MAX_TERM) } }, T("译文", "訳文")) }
            TextBtn("×") { d.terms = d.terms.toMutableList().also { it.removeAt(i) } }
        }
    }
    TextBtn(T("+ 加一条", "+ 追加")) { if (d.terms.size < Scene.MAX_TERMS) d.terms = d.terms + ("" to "") }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Btn(if (d.generating) T("生成中…", "生成中…") else T("生成术语", "用語を生成"), enabled = !d.generating) {
            val service = prefs.byokService()
            val chat = service?.url
            val n = d.count.trim().toIntOrNull()
            d.status = when {
                service == null || service.model.isEmpty() -> T("先在上面「LLM API」里填好服务并保存。", "先に上にある「LLM API」でサービスを設定して保存してください。")
                d.label.isBlank() && d.background.isBlank() -> T("先写名字或背景说明，LLM 才知道要哪类术语。", "名前か背景説明を書いてください。LLM がどのような用語が必要か分かります。")
                n == null || n < 1 || n > MAX_GENERATE -> T("生成条数填 1 到 ", "生成数は 1 から ") + MAX_GENERATE + T("。", " の間で入力してください。")
                d.terms.count { it.first.isNotBlank() || it.second.isNotBlank() } >= Scene.MAX_TERMS -> T("术语已经有 ", "用語は既に ") + Scene.MAX_TERMS + T(" 条，满了。", " 件で上限です。")
                else -> null
            }
            if (d.status != null) return@Btn
            d.generating = true; d.status = T("正在生成…（最多一分钟）", "生成中…（最大1分）")
            val model = prefs.byokModel; val stored = prefs.byokVariant(chat!!, model)
            val label = d.label.trim(); val bg = d.background; val src = prefs.sourceLang; val tgt = d.termsLang
            val have = Scene.sources(d.terms)
            val room = Scene.MAX_TERMS - d.terms.count { it.first.isNotBlank() || it.second.isNotBlank() }
            Thread {
                val (got, err) = generateTerms(service!!, stored, { prefs.setByokVariant(chat, model, it) }, label, bg, have, minOf(n!!, room), src, tgt)
                java.awt.EventQueue.invokeLater {
                    d.generating = false
                    if (sceneDraft.value !== d) return@invokeLater // closed meanwhile
                    // appended to what is in the box now (the user may have typed meanwhile), skipping 原文 now there
                    // a lone empty row (the one a new scene starts with) gives way to the generated ones
                    val now = d.terms.takeUnless { it.size == 1 && it[0].first.isBlank() && it[0].second.isBlank() }.orEmpty()
                    val (text, added) = appendTerms(now, got.orEmpty())
                    d.status = when {
                        err != null -> "⚠ " + T("没能生成：", "生成できませんでした：") + err
                        added == 0 -> T("没有生成新的术语（可能都已经有了，或者满了），可以把背景说明写详细些再试。", "新しい用語は生成されませんでした（既にあるか、上限かも）。背景説明をもっと詳しく書いて再試行してください。")
                        else -> { d.terms = text; T("加了 ", "追加しました ") + added + T(" 条，看一下有没有不对的，再点「保存」。", " 件。間違いがないか確認し、「保存」を押してください。") }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        Text(T("最多", "最大"), color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp, end = 6.dp))
        Box(Modifier.width(64.dp)) { Field(d.count, { d.count = it.filter(Char::isDigit).take(3) }, "20") }
        Text(T("条", "件"), color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
    d.status?.let { Hint(it, color = if (it.startsWith("⚠")) C.err else C.text2) }
    Hint(T("「生成术语」用你填的 LLM API，按名字和背景说明想出这类内容的常见词，只往下面加，不改也不删已有的术语。", "「用語を生成」は設定した LLM API を使い、名前と背景説明からこの内容のよく出る単語を考え、下に追加するだけです。既存の用語は変更・削除しません。"))
    Spacer(Modifier.height(8.dp))
    val old = d.old
    if (d.confirm && old != null) {
        Note(if (old.builtin) T("把「", "「") + old.label + T("」恢复成自带的说明和术语？你改过的内容会丢掉。", "」をデフォルトの説明と用語に戻しますか？変更内容は失われます。") else T("删除「", "「") + old.label + T("」？", "」を削除しますか？"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grow()
            TextBtn(T("取消", "キャンセル")) { d.confirm = false }
            Btn(if (old.builtin) T("恢复默认", "デフォルトに戻す") else T("删除", "削除"), danger = true) {
                val shipped = Scene.DEFAULTS.firstOrNull { it.id == old.id }
                val inUse = prefs.scene.id == old.id
                prefs.scenes = if (shipped != null) prefs.scenes.map { if (it.id == old.id) shipped else it }
                    else prefs.scenes.filter { it.id != old.id }
                if (shipped == null && inUse) prefs.scene = prefs.scenes.first()
                sceneDraft.value = null
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            old != null && !old.builtin -> TextBtn(T("删除", "削除"), danger = true) { d.confirm = true }
            old != null && !old.shipped -> TextBtn(T("恢复默认", "デフォルトに戻す")) { d.confirm = true }
        }
        Grow()
        TextBtn(T("取消", "キャンセル")) { sceneDraft.value = null }
        Btn(T("保存", "保存"), primary = true) {
            val (s, err) = Scene.edit(old?.id ?: "u${System.currentTimeMillis()}", d.label, d.background, d.terms, d.termsLang)
            if (s == null) { d.status = "⚠ $err"; return@Btn }
            val list = prefs.scenes
            prefs.scenes = if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
            prefs.scene = s
            sceneDraft.value = null
        }
    }
}

/** the running BYOK connection test, so a second click or a new screen does not show a stale result */
private val byokTest = mutableStateOf<ByokTranslator?>(null)
private val byokResult = mutableStateOf<String?>(null)

@Composable
private fun TranslateTab() {
    val running by CaptionService.runningState
    if (running) Note(T("字幕正在运行：内容类型、结合上文、去掉重复的字、LLM API（切换配置或点保存后）和 Jev 在线模型服务、key 改了从下一句开始生效；智能断句和检查译文从下次开始字幕时生效；显示方式马上生效。", "字幕実行中：内容タイプ・前文参照・重複除去・LLM API（設定切替えまたは保存後）・Jev オンラインモデル・key の変更は次文から反映；スマート区切りと訳文チェックは次回の字幕開始時に反映；表示方法は即時反映。"))
    Group(T("LLM API", "LLM API")) { Byok(running); ByokAdvanced() }
    Group(T("内容", "内容")) {
        Label(T("内容类型", "内容タイプ"))
        Scenes()
        SwitchRow(T("结合上文来翻译", "前文を踏まえて翻訳"), T("翻译每句话时，把前面刚说过的几句一起交给翻译参考，帮助它理解人称、指代和省略的内容。参考的句子越多，翻译要处理的文字越多，可能更慢。", "各文を翻訳するとき、直前の数文も一緒に翻訳に渡し、人称・指示・省略を理解する助けにします。参照する文が多いほど翻訳対象が増え、遅くなる可能性があります。"),
            prefs.contextOn) { prefs.contextOn = it }
        if (prefs.contextOn) {
            Label(T("参考前面", "直前の") + " ${prefs.contextSentences} " + T("句", "文"))
            Slider2(prefs.contextSentences.toFloat(), 1f..Pipeline.MAX_PREVIOUS_SENTENCES.toFloat(), Pipeline.MAX_PREVIOUS_SENTENCES - 2) {
                prefs.contextSentences = Math.round(it)
            }
        }
    }
    Group(T("字幕怎么出现", "字幕の表示方法")) {
        Seg(listOf(false to T("边听边出", "聞きながら出す"), true to T("说完再出", "言い終わってから出す")), prefs.finalOnly) { prefs.finalOnly = it }
        Hint(T("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。", "「聞きながら出す」：話しながら表示し、グレーの文字は修正されて、言い終わった文は正式字幕になります。「言い終わってから出す」：各文が終わってから表示され、画面はきれいですが少し遅れます。字幕のツールバーでもいつでも切替えられます。"))
        SwitchRow(T("去掉重复的字", "重複する文字を除く"), T("一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。", "一文を分けて認識するとき、つなぎ目で同じ文を二回認識することがあります。オンにすると余分な一回を自動で除きます。話者が本当に繰り返した場合は除かれることもあります。"),
            prefs.dedupeJoin) { prefs.dedupeJoin = it }
    }
    Group(T("在线 Decisions 模型辅助（可选，需要联网）", "オンライン Decisions モデル補助（任意・要ネット接続）")) {
        SwitchRow(T("智能断句", "スマートな区切り"), T("由 Jev 模型判断一句话是否真的说完，减少把一句话切成两半的情况。", "Jev モデルが一文が本当に言い終わったか判断し、一文を二つに切るのを減らします。"), prefs.jevOn) { prefs.jevOn = it }
        SwitchRow(T("检查译文", "訳文チェック"), T("由 Jev 模型检查每句译文是否把意思译反、漏了否定等，发现问题就重新翻译这一句。打开后每句字幕会稍晚出现。", "Jev モデルが各訳文で意味の反転や否定の見落としがないか確認し、問題があればその文を翻訳し直します。オンにすると各字幕が少し遅れて表示されます。"), prefs.jevCheck) { prefs.jevCheck = it }
        if (prefs.jevOn || prefs.jevCheck) {
            val p = prefs.jevProvider
            Label(T("Jev 在线模型服务", "Jev オンラインモデルサービス"))
            Dropdown(JevProvider.entries.map { "${it.label}（${it.note}）" }, p.ordinal) { prefs.jevProvider = JevProvider.entries[it] }
            if (p == JevProvider.CUSTOM) JevCustomBox()
            else if (!p.needsKey) Hint(T("不用填 key，打开就能用。这是免费的公共服务，可能随时限速或停用；服务不可用时，字幕照常显示，只是不做辅助。只发送识别出的文字和译文，不发送声音。", "key 不要でオンにすれば使えます。無料の公共サービスで、いつでも制限・停止される可能性があります。サービスが使えないときは字幕は通常通り表示され、補助だけが行われません。認識した文字と訳文だけを送信し、音声は送信しません。"))
            else {
                val saved = prefs.jevKey(p)
                Label(if (saved != null) p.label + T(" 的 key：已保存（…", " の key：保存済み（…") + saved.takeLast(4) + "）" else p.label + T(" 的 key：还没填", " の key：未入力"))
                var input by remember(p) { mutableStateOf("") }
                var err by remember(p) { mutableStateOf<String?>(null) }
                Field(input, { input = it; err = null }, if (saved != null) T("输入新的 key 来替换", "新しい key を入力して置き換え") else T("在这里粘贴 key", "ここに key を貼り付け"), password = true)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    err?.let { Text(it, color = C.err, fontSize = 13.sp) }
                    Grow()
                    TextBtn(T("清除", "クリア"), enabled = saved != null) { if (!prefs.setJevKey(p, null)) err = T("没能从钥匙串删掉 key，请再试一次", "キーチェーンから key を削除できませんでした。もう一度お試しください") }
                    Btn(T("保存", "保存"), primary = true) {
                        val k = input.trim()
                        when {
                            k.isEmpty() -> {}
                            // a key with spaces/newlines/non-ASCII would be rejected by HTTP (and echoed in its error)
                            !Jev.validKey(k) -> err = T("key 里有空格、换行或特殊字符，请重新复制", "key に空白・改行・特殊文字があります。もう一度コピーしてください")
                            !prefs.setJevKey(p, k) -> err = T("key 没能存进钥匙串，请再试一次", "key をキーチェーンに保存できませんでした。もう一度お試しください")
                            else -> input = ""
                        }
                    }
                }
                Hint(T("只发送识别出的文字和译文，不发送声音。key ", "認識した文字と訳文だけを送信し、音声は送信しません。key は") + (if (isWindows) T("加密保存在这台电脑上，只有你的 Windows 账户能读出", "このPCに暗号化して保存され、あなたのWindowsアカウントだけが読めます") else T("保存在这台 Mac 的钥匙串里", "この Mac のキーチェーンに保存されます")) + T("，不会写进使用记录。", "。使用記録には書き込まれません。"))
            }
        }
    }
}

/** The 自定义 Jev boxes as typed; outside the composable so switching tabs keeps them. */
private class JevDraft {
    private val c = prefs.jevCustom
    var protocol by mutableStateOf(c?.protocol ?: JevProtocol.SYSTEMONE)
    var url by mutableStateOf(c?.url.orEmpty())
    var model by mutableStateOf(c?.model.orEmpty())
    var key by mutableStateOf("")
}
private val jevDraft = mutableStateOf<JevDraft?>(null)
/** the 测试连接 in flight (a newer one drops its answer) and the last save or test result */
private val jevTest = mutableStateOf<Any?>(null)
private val jevResult = mutableStateOf<String?>(null)

/** 自定义 Jev: protocol, address, model, an optional key, 保存 and 测试连接 (which saves first). */
@Composable
private fun JevCustomBox() {
    val p = JevProvider.CUSTOM
    val d = jevDraft.value ?: JevDraft().also { jevDraft.value = it }
    val testing = jevTest.value != null
    Label(T("协议", "プロトコル"))
    Dropdown(JevProtocol.entries.map { it.label }, d.protocol.ordinal, enabled = !testing) { d.protocol = JevProtocol.entries[it] }
    Label(T("服务地址", "サービスURL"))
    Field(d.url, { d.url = it; jevResult.value = null }, T("例如 https://openrouter.ai/api", "例：https://openrouter.ai/api"), enabled = !testing)
    val (bad, u) = JevCustom(d.protocol, d.url, d.model).endpoint()
    if (u != null) Hint(T("实际请求地址：", "実際のリクエスト先：") + u) else if (d.url.isNotBlank()) Hint(bad.orEmpty(), C.err)
    Label(T("模型", "モデル"))
    Field(d.model, { d.model = it; jevResult.value = null }, T("例如 typesafe/jev-1.14", "例：typesafe/jev-1.14"), enabled = !testing)
    val saved = prefs.jevKey(p)
    Label(if (saved != null) T("key：已保存（…", "key：保存済み（…") + saved.takeLast(4) + "）" else T("key：没填（不需要 key 的服务可以空着）", "key：未入力（key 不要のサービスは空欄でOK）"))
    Field(d.key, { d.key = it; jevResult.value = null }, if (saved != null) T("输入新的 key 来替换", "新しい key を入力して置き換え") else T("在这里粘贴 key", "ここに key を貼り付け"), password = true, enabled = !testing)
    /** Saves the draft; null (and says why) when it cannot be used. */
    fun save(): JevCustom? {
        val n = JevCustom(d.protocol, d.url.trim(), d.model.trim())
        val k = d.key.trim()
        val why = n.endpoint().first ?: if (n.model.isEmpty()) T("还没填模型", "モデルが未入力") else if (k.isNotEmpty() && !Jev.validKey(k)) T("key 里有空格、换行或特殊字符，请重新复制", "key に空白・改行・特殊文字があります。もう一度コピーしてください") else null
        if (why != null) { jevResult.value = "✗ $why"; return null }
        if (!prefs.saveJevCustom(n, k.ifEmpty { null })) { jevResult.value = "✗ " + T("key 没能存进钥匙串，请再试一次", "key をキーチェーンに保存できませんでした。もう一度お試しください"); return null }
        d.key = ""
        return n
    }
    jevResult.value?.let { Hint(it, if (it.startsWith("✗")) C.err else C.text2) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Grow()
        TextBtn(T("清除 key", "key をクリア"), enabled = saved != null && !testing) { if (!prefs.setJevKey(p, null)) jevResult.value = "✗ " + T("没能从钥匙串删掉 key，请再试一次", "キーチェーンから key を削除できませんでした。もう一度お試しください") }
        Btn(T("保存", "保存"), enabled = !testing) { if (save() != null) jevResult.value = T("已保存。字幕运行中的话，从下一句开始用。", "保存しました。字幕実行中なら次文から使用します。") }
        Btn(if (testing) T("测试中…", "テスト中…") else T("测试连接", "接続テスト"), primary = true, enabled = !testing) {
            val n = save() ?: return@Btn
            val key = prefs.jevKey(p).orEmpty()
            val token = Any(); jevTest.value = token; jevResult.value = null
            Thread {
                val (ok, line) = Jev.test(n, key)
                java.awt.EventQueue.invokeLater {
                    if (jevTest.value !== token) return@invokeLater
                    jevTest.value = null
                    jevResult.value = (if (ok) "✓ " else "✗ ") + line
                }
            }.apply { isDaemon = true }.start()
        }
    }
    Hint(T("填一个 TypeSafe System One 或 OpenRouter Decisions 接口的 Jev 服务，例如 CLIProxyAPI 之类的本地代理。地址结尾已经是 /systemone 或 /decisions 时原样使用，否则自动加上协议的路径。只发送识别出的文字和译文，不发送声音；用 http:// 地址时 key 是明文传输。key ", "TypeSafe System One または OpenRouter Decisions の Jev サービスを入力します（例：CLIProxyAPI などのローカルプロキシ）。URL の末尾が既に /systemone か /decisions の場合はそのまま使用し、そうでなければプロトコルのパスを自動で付けます。認識した文字と訳文だけを送信し、音声は送信しません；http:// の URL では key が平文で送信されます。key は") + (if (isWindows) T("加密保存在这台电脑上", "このPCに暗号化して保存され") else T("保存在这台 Mac 的钥匙串里", "この Mac のキーチェーンに保存され")) + T("，不会写进使用记录。", "、使用記録には書き込まれません。"))
}

/**
 * What the LLM API boxes hold but 保存 has not saved yet: address, model, a typed key, "clear the saved key", and the
 * 高级设置 (null: unchanged). All of it is saved by one 保存, so a running session never pairs one service's headers or
 * key with another's address. Outside the composable so switching tabs keeps it.
 */
private class ByokDraft {
    var url by mutableStateOf(prefs.byokUrl)
    var model by mutableStateOf(prefs.byokModel)
    var key by mutableStateOf("")
    var clearKey by mutableStateOf(false)
    var userAgent by mutableStateOf<String?>(null)
    var headers by mutableStateOf<List<Pair<String, String>>?>(null)
}
private val byokDraft = mutableStateOf(ByokDraft())

/** The 高级设置 being edited (null: closed): the User-Agent and the extra headers, as typed. */
private class ByokAdvancedDraft(d: ByokDraft) {
    var userAgent by mutableStateOf(d.userAgent ?: prefs.byokUserAgent)
    var headers by mutableStateOf((d.headers ?: prefs.byokHeaders).ifEmpty { listOf("" to "") })
    var status by mutableStateOf<String?>(null)
}
private val byokAdvanced = mutableStateOf<ByokAdvancedDraft?>(null)

/**
 * 高级设置 of the LLM API: the User-Agent (empty = ours) and extra request headers, one row of two boxes each. 好
 * checks them and puts them in the draft; the LLM API's 保存 saves them with the rest of the service (the headers kept
 * like the key), and a running session takes them from the next sentence.
 */
@Composable
private fun ByokAdvanced() {
    val d = byokAdvanced.value ?: return
    Spacer(Modifier.height(10.dp))
    Text(T("高级设置", "詳細設定"), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Label("User-Agent")
    Field(d.userAgent, { d.userAgent = it }, ByokTranslator.defaultUserAgent)
    Hint(T("空着就用 ", "空欄なら ") + ByokTranslator.defaultUserAgent + T("。有的服务会拦下不认识的 User-Agent，例如 B 站 Index 的免费接口要求填 Index-Translate-Client/1.0。", " を使います。一部のサービスは知らない User-Agent をブロックします。例：B站 Index の無料APIは Index-Translate-Client/1.0 の入力が必要です。"))
    Label(T("自定义请求头", "カスタムリクエストヘッダー"))
    Hint(T("左边写名字，右边写值，每个请求都会带上。值和 key 一样保存在", "左に名前、右に値を書きます。各リクエストに付与されます。値は key と同様に") + (if (isWindows) T("加密文件", "暗号化ファイル") else T("钥匙串", "キーチェーン")) + T("里，使用记录里只记名字。", "に保存され、使用記録には名前だけが記録されます。"))
    d.headers.forEachIndexed { i, (k, v) ->
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(k, { n -> d.headers = d.headers.toMutableList().also { it[i] = n to it[i].second } }, T("名字", "名前")) }
            Text(":", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.weight(1.4f)) { Field(v, { n -> d.headers = d.headers.toMutableList().also { it[i] = it[i].first to n } }, T("值", "値")) }
            TextBtn("×") { d.headers = d.headers.toMutableList().also { it.removeAt(i) } }
        }
    }
    TextBtn(T("+ 加一个", "+ 追加")) { if (d.headers.size < ByokService.MAX_HEADERS) d.headers = d.headers + ("" to "") }
    d.status?.let { Hint(it, C.err) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Grow()
        TextBtn(T("取消", "キャンセル")) { byokAdvanced.value = null }
        Btn(T("好", "OK"), primary = true) {
            val agent = d.userAgent.trim()
            val headers = d.headers.map { it.first.trim() to it.second.trim() }.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
            val err = ByokService.badAdvanced(agent, headers)
            if (err != null) { d.status = "⚠ $err"; return@Btn }
            byokDraft.value.userAgent = agent; byokDraft.value.headers = headers
            byokAdvanced.value = null
            byokResult.value = T("高级设置改好了，点「保存」才会生效。", "詳細設定はOKです。「保存」を押すと反映されます。")
        }
    }
}

@Composable
private fun Byok(running: Boolean) {
    val profiles = prefs.byokProfiles
    val active = prefs.byokProfile
    var naming by remember { mutableStateOf<Boolean?>(null) }
    var nameText by remember { mutableStateOf("") }
    var nameErr by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    val busy = byokTest.value != null || naming != null || confirmDel
    Label(T("配置", "設定"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dropdown(profiles.map { it.name }, profiles.indexOfFirst { it.id == active.id }.coerceAtLeast(0), enabled = !busy,
            modifier = Modifier.weight(1f)) {
            if (profiles[it].id != active.id) {
                prefs.useByokProfile(profiles[it])
                byokDraft.value = ByokDraft(); byokAdvanced.value = null
                byokResult.value = (T("已切换到「", "「") + profiles[it].name + T("」，字幕从下一句开始用这个配置。", "」に切替。字幕は次文からこの設定を使用します。")).takeIf { running && prefs.byokReady }
            }
        }
        Spacer(Modifier.width(8.dp))
        Btn(T("重命名", "名前を変更"), enabled = !busy) { byokAdvanced.value = null; naming = true; nameText = active.name; nameErr = null }
        Spacer(Modifier.width(8.dp))
        Btn(T("新建", "新規作成"), enabled = !busy && profiles.size < LlmProfile.MAX_PROFILES) { byokAdvanced.value = null; naming = false; nameText = ""; nameErr = null }
        if (profiles.size > 1) {
            Spacer(Modifier.width(8.dp))
            Btn(T("删除", "削除"), danger = true, enabled = !busy) { byokAdvanced.value = null; confirmDel = true }
        }
    }
    val testing = byokTest.value != null
    naming?.let { rename ->
        Label(if (rename) T("重命名配置", "設定名を変更") else T("新建配置", "新しい設定"))
        Field(nameText, { nameText = it.take(LlmProfile.MAX_NAME); nameErr = null }, T("例如 千问、DeepSeek", "例：千问、DeepSeek"), enabled = !testing)
        nameErr?.let { Hint(it, C.err) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Grow()
            TextBtn(T("取消", "キャンセル"), enabled = !testing) { naming = null }
            Btn(T("确定", "OK"), primary = true, enabled = !testing) {
                val (ok, e) = LlmProfile.name(nameText, profiles, self = active.id.takeIf { rename })
                if (ok == null) { nameErr = "⚠ $e"; return@Btn }
                if (rename) prefs.renameByokProfile(active, ok) else {
                    prefs.addByokProfile(ok)
                    byokDraft.value = ByokDraft(); byokAdvanced.value = null; byokResult.value = null
                }
                naming = null
            }
        }
    }
    if (confirmDel) {
        Spacer(Modifier.height(8.dp))
        Note(T("删除「", "「") + active.name + T("」？这个配置里的服务地址、模型 ID、API Key 和高级设置都会删掉。", "」を削除しますか？この設定のサービスURL・モデルID・API Key・詳細設定はすべて削除されます。"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grow()
            TextBtn(T("取消", "キャンセル"), enabled = !testing) { confirmDel = false }
            Btn(T("删除", "削除"), danger = true, enabled = !testing) {
                confirmDel = false
                if (!prefs.deleteByokProfile(active)) {
                    byokResult.value = "⚠ " + T("key 或请求头没能从", "key またはリクエストヘッダーを") + (if (isWindows) T("加密文件", "暗号化ファイル") else T("钥匙串", "キーチェーン")) + T("删掉，请再试一次；钥匙串要求授权时请选「允许」。", "から削除できませんでした。もう一度お試しください；認証を求められたら「許可」を選んでください。")
                    return@Btn
                }
                byokDraft.value = ByokDraft(); byokAdvanced.value = null
                byokResult.value = (T("已切换到「", "「") + prefs.byokProfile.name + T("」，字幕从下一句开始用这个配置。", "」に切替。字幕は次文からこの設定を使用します。")).takeIf { running && prefs.byokReady }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Hint(T("填一个 OpenAI 兼容接口的 LLM 服务来翻译字幕，只发送识别出的文字，不发送声音。\n模型要求：必须能关闭思考（深度思考 / reasoning），否则每句都要等很久；尽量选回复速度快的模型，字幕才跟得上。", "字幕を翻訳する OpenAI 互換 API の LLM サービスを設定します。認識した文字だけを送信し、音声は送信しません。\nモデルの要件：思考（深度思考 / reasoning）をオフにできること。できないと毎文の待ち時間が長くなります。応答の速いモデルを選ぶと字幕が追いつきます。"))
    val d = byokDraft.value
    val saved = prefs.byokKey?.takeUnless { d.clearKey }
    var msg by byokResult
    Label(T("服务地址（OpenAI 兼容）", "サービスURL（OpenAI 互換）"))
    Field(d.url, { d.url = it }, T("例如 https://api.deepseek.com，结尾有没有 /v1 都可以", "例：https://api.deepseek.com。末尾の /v1 の有無は問いません"), enabled = !busy)
    Label(T("模型 ID", "モデルID"))
    Field(d.model, { d.model = it }, T("例如 deepseek-chat", "例：deepseek-chat"), enabled = !busy)
    Label(when {
        saved != null -> T("API Key：已保存（…", "API Key：保存済み（…") + saved.takeLast(4) + "）"
        d.clearKey -> T("API Key：保存后清除（不需要 key 的服务可以空着）", "API Key：保存後にクリア（key 不要のサービスは空欄でOK）")
        else -> T("API Key：没填（不需要 key 的服务可以空着）", "API Key：未入力（key 不要のサービスは空欄でOK）")
    })
    Field(d.key, { d.key = it }, if (saved != null) T("输入新的 key 来替换", "新しい key を入力して置き換え") else T("在这里粘贴 key", "ここに key を貼り付け"), password = true, enabled = !busy)
    // save what is in the boxes; null when something is wrong (and says what)
    fun save(): String? {
        val u = d.url.trim(); val m = d.model.trim(); val k = d.key.trim()
        val (bad, _) = ByokTranslator.chatUrl(u)
        when {
            bad != null -> { msg = "⚠ $bad"; return null }
            m.isEmpty() -> { msg = "⚠ " + T("还没填模型 ID", "モデルIDが未入力"); return null }
            m.any { it.isWhitespace() } -> { msg = "⚠ " + T("模型 ID 里不要有空格", "モデルIDに空白を含めないでください"); return null }
            k.isNotEmpty() && !Jev.validKey(k) -> { msg = "⚠ " + T("key 里有空格、换行或特殊字符，请重新复制", "key に空白・改行・特殊文字があります。もう一度コピーしてください"); return null }
        }
        // one edit; a running session switches to the saved service from the next sentence, as one change
        if (!prefs.saveByok(u, m, k.ifEmpty { if (d.clearKey) "" else null }, d.userAgent, d.headers)) {
            msg = "⚠ " + T("key 或请求头没能存进", "key またはリクエストヘッダーを") + (if (isWindows) T("加密文件", "暗号化ファイル") else T("钥匙串", "キーチェーン")) + T("，请再试一次；钥匙串要求授权时请选「允许」。", "に保存できませんでした。もう一度お試しください；認証を求められたら「許可」を選んでください。"); return null
        }
        byokDraft.value = ByokDraft()
        return ByokTranslator.chatUrl(u).second
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val extra = listOfNotNull((d.userAgent ?: prefs.byokUserAgent).ifEmpty { null }?.let { T("自定义 User-Agent", "カスタム User-Agent") },
            (d.headers ?: prefs.byokHeaders).size.takeIf { it > 0 }?.let { it.toString() + T(" 个请求头", " 個のリクエストヘッダー") },
            T("未保存", "未保存").takeIf { d.userAgent != null || d.headers != null }).joinToString(T("、", "・"))
        TextBtn(T("高级设置", "詳細設定") + if (extra.isEmpty()) "" else "（$extra）", enabled = !busy && byokAdvanced.value == null) { byokAdvanced.value = ByokAdvancedDraft(d) }
        Grow()
        // running: "保存" switches the session from the next sentence (it tests a new model itself), so no test
        // (two probes would bill twice); clearing the key is a draft change like the rest, saved by 保存
        TextBtn(T("清除 key", "key をクリア"), enabled = saved != null && !busy) { d.clearKey = true; d.key = "" }
        Btn(T("保存", "保存"), enabled = !busy) {
            if (save() != null) msg = if (running) T("已保存，字幕从下一句开始用新的设置；第一次用这个模型时会先自动测试怎样关闭思考。", "保存しました。字幕は次文から新しい設定を使用；このモデルを初めて使うときは思考のオフ方法を自動テストします。") else null
        }
        Btn(if (testing) T("测试中…", "テスト中…") else T("测试连接", "接続テスト"), primary = true, enabled = !running && !busy) {
            val chatUrl = save() ?: return@Btn
            val m = prefs.byokModel; val service = prefs.byokService() ?: return@Btn
            val t = ByokTranslator(service, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null,
                maxTokens = mtMaxTokens(prefs.targetLang))
            byokTest.value = t; msg = null
            Thread {
                val steps = mutableListOf<ByokTranslator.Step>()
                val v = t.probe(steps)
                java.awt.EventQueue.invokeLater {
                    if (byokTest.value !== t) return@invokeLater // cancelled (captions started)
                    byokTest.value = null
                    val ok = steps.firstOrNull { it.variant === v }?.result
                    val none = v == null && t.exhausted(steps)
                    when {
                        v != null -> prefs.setByokVariant(chatUrl, m, v.name)
                        none -> prefs.setByokVariant(chatUrl, m, ByokTranslator.NONE_WORKED)
                    }
                    byokResult.value = when {
                        ok != null -> T("✓ 可以用。参数：", "✓ 使用可能。パラメータ：") + v!!.label + T("（这次没检测到思考，已记住）；收到第一个字 ", "（今回は思考を検出せず、記憶済み）；最初の文字を ") + (ok.firstContentMs ?: "?") + " ms" +
                            (if ((ok.firstContentMs ?: 0) > FIRST_CONTENT_MS) T("（超过 ", "（") + (FIRST_CONTENT_MS / 1000) + T(" 秒，翻译时这句会判为失败）", " 秒超で翻訳時に失敗と判定）") else "") + T("。\n测试译文：", "。\nテスト訳文：") + ok.text.take(60)
                        none -> T("⚠ 连得上，但试过的 ", "⚠ 接続できますが、試した ") + steps.size + T(" 种参数都没能关掉思考，翻译可能很慢或经常失败。仍然可以使用。", " 種類のパラメータで思考をオフにできませんでした。翻訳が遅い・失敗することがありますが、使用は可能です。")
                        else -> T("✗ 不能用：", "✗ 使用不可：") + (steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: T("没有回应", "応答なし"))
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }
    val chat = ByokTranslator.chatUrl(prefs.byokUrl).second
    val known = chat?.let { prefs.byokVariant(it, prefs.byokModel) }
    Hint(msg ?: when {
        testing -> T("正在依次尝试关闭思考的参数（最多 ", "思考をオフにするパラメータを順に試しています（最大 ") + ByokTranslator.THINKING_OFF.size + T(" 次，每次最多 8 秒，每次都会计费）…", " 回、各最大8秒、毎回課金）…")
        chat == null || prefs.byokModel.isEmpty() -> T("填好地址、模型 ID 和 key 后点「测试连接」。第一次使用时会依次试最多 ", "アドレス・モデルID・key を入力し「接続テスト」を押します。初回使用時は最大 ") + ByokTranslator.THINKING_OFF.size + T(" 种关闭思考的参数（每种是一次计费请求），找到没检测到思考的就记住。", " 種類の思考オフのパラメータを順に試し（各1回課金）、思考を検出しないものを記憶します。")
        known == ByokTranslator.NONE_WORKED -> T("⚠ 上次测试：已知的参数都关不掉这个模型的思考，翻译可能很慢。仍然可以使用。", "⚠ 前回のテスト：既知のパラメータではこのモデルの思考をオフにできません。翻訳が遅い可能性がありますが、使用は可能です。")
        known != null -> T("上次测试可用（用的参数：", "前回のテストは使用可能（パラメータ：") + (ByokTranslator.variant(known)?.label ?: "") + T("，那次没检测到思考）。实际请求地址：", "、そのとき思考は検出されず）。実際のリクエスト先：") + chat
        else -> T("还没测试，第一次开始字幕时会自动测。实际请求地址：", "まだテストしていません。初回の字幕開始時に自動テストします。実際のリクエスト先：") + chat
    })
}

// ---- 页面 --------------------------------------------------------------------------------------------------

@Composable
private fun PageTab() {
    Group(T("页面颜色", "ウィンドウの色")) {
        Hint(T("这个窗口用白天还是夜间颜色。不影响悬浮字幕，字幕颜色在「字幕」里选。", "このウィンドウはライトかダークの配色を使います。浮遊字幕には影響せず、字幕の色は「字幕」で選びます。"))
        Seg(listOf("system" to T("跟随系统", "システムに従う"), "light" to T("白天", "ライト"), "dark" to T("夜间", "ダーク")), prefs.theme) { prefs.theme = it }
    }
}

// ---- 记录 --------------------------------------------------------------------------------------------------

@Composable
private fun LogsTab() {
    var tick by remember { mutableIntStateOf(0) } // relists after a change, and every few seconds (a session may end)
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    Group(T("字幕文本", "字幕テキスト")) {
        SwitchRow(T("保存字幕文本", "字幕テキストを保存"), T("每句说完定稿的原文和译文，按时间存成一份纯文本，可以打开查看、另存或发给别人。和下面的「记录每次使用」无关。从下次开始字幕起生效。", "言い終わって確定した原文と訳文を、時間順に1つのテキストファイルとして保存します。開いて閲覧・別名保存・他人に送信できます。下の「毎回の使用を記録」とは無関係です。次回の字幕開始から有効。"),
            prefs.keepTranscript) { prefs.keepTranscript = it }
        tick.let { }
        val files = Transcript.list(dataDir)
        var confirm by remember { mutableStateOf<File?>(null) }
        var confirmAll by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        if (files.isEmpty()) Hint(if (prefs.keepTranscript) T("还没有字幕文本。", "字幕テキストはまだありません。") else T("还没有字幕文本。现在不保存，打开上面的开关后才会保存。", "字幕テキストはまだありません。現在は保存していません。上のスイッチをオンにすると保存されます。"))
        for (f in files) {
            Divider()
            val writing = Transcript.isWriting(f)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name.removeSuffix(".txt"), fontSize = 14.sp)
                    Text(sizeText(f.length()) + if (writing) T(" · 正在保存", " · 保存中") else "", fontSize = 12.sp, color = C.muted)
                }
                if (confirm == f) {
                    TextBtn(T("取消", "キャンセル")) { confirm = null }
                    TextBtn(T("删除", "削除"), danger = true) { confirm = null; if (!Transcript.delete(f)) msg = T("删除失败：正在保存或文件被占用", "削除失敗：保存中またはファイルが使用中"); tick++ }
                } else {
                    TextBtn(T("删除", "削除"), enabled = !writing) { confirm = f }
                    TextBtn(if (isWindows) T("在文件夹中显示", "フォルダに表示") else T("在访达中显示", "Finder に表示")) { reveal(f) }
                    TextBtn(T("打开", "開く")) { if (runCatching { Desktop.getDesktop().open(f) }.isFailure) msg = T("打不开：文件已经不在了，或系统没有能打开 .txt 的程序", "開けません：ファイルが既にないか、.txt を開けるプログラムがありません") }
                }
            }
        }
        msg?.let { Hint(it, C.err) }
        if (files.isNotEmpty()) {
            Divider()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(T("共 ", "合計 ") + files.size + T(" 份，", " 件、") + sizeText(files.sumOf { it.length() }), fontSize = 13.sp, color = C.muted)
                Grow()
                if (confirmAll) {
                    TextBtn(T("取消", "キャンセル")) { confirmAll = false }
                    TextBtn(T("删除全部（正在保存的不删）", "すべて削除（保存中のものは除く）"), danger = true) {
                        confirmAll = false
                        val left = Transcript.deleteAll(dataDir)
                        msg = if (left > 0) T("还剩 ", "残り ") + left + T(" 份（正在保存或无法删除）", " 件（保存中または削除不可）") else null; tick++
                    }
                } else TextBtn(T("全部清空", "すべて削除"), danger = true) { confirmAll = true }
            }
        }
    }
    Group(T("使用记录", "使用記録")) {
        SwitchRow(T("记录每次使用", "毎回の使用を記録"), T("打开后，每次开始字幕会在电脑上留一份文字记录：识别结果、翻译的提示词和译文、在线 Decisions 模型辅助的请求和回答，不含声音和 key。只有你自己发出去才会离开电脑，出问题时可以发给开发者帮助排查。从下次开始字幕起生效。", "オンにすると、字幕開始のたびにPCにテキスト記録を残します：認識結果・翻訳のプロンプトと訳文・オンライン Decisions モデル補助のリクエストと回答。音声と key は含みません。自分で送信しない限りPCの外には出ず、問題時は開発者に送って調査を手伝えます。次回の字幕開始から有効。"),
            prefs.keepLogs) { prefs.keepLogs = it }
        tick.let { }
        val files = SessionLog.list(dataDir)
        var confirm by remember { mutableStateOf<File?>(null) }
        var confirmAll by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        if (files.isEmpty()) Hint(if (prefs.keepLogs) T("还没有记录。", "記録はまだありません。") else T("还没有记录。现在不记录，打开上面的开关后才会记录。", "記録はまだありません。現在は記録していません。上のスイッチをオンにすると記録されます。"))
        for (f in files) {
            Divider()
            val active = SessionLog.isActive(f)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name.removeSuffix(".part").removeSuffix(".jsonl"), fontSize = 14.sp)
                    Text(sizeText(f.length()) + when { active -> T(" · 正在记录，结束后可以发送", " · 記録中、終了後に送信可"); !SessionLog.complete(f) -> T(" · 没有正常结束", " · 正常に終了していない"); else -> "" },
                        fontSize = 12.sp, color = C.muted)
                }
                if (confirm == f) {
                    TextBtn(T("取消", "キャンセル")) { confirm = null }
                    TextBtn(T("删除", "削除"), danger = true) { confirm = null; if (!SessionLog.delete(f)) msg = T("删除失败：记录正在写入或文件被占用", "削除失敗：記録を書き込み中またはファイルが使用中"); tick++ }
                } else {
                    TextBtn(T("删除", "削除"), enabled = !active) { confirm = f }
                    TextBtn(if (isWindows) T("在文件夹中显示", "フォルダに表示") else T("在访达中显示", "Finder に表示"), enabled = !active) { reveal(f) }
                }
            }
        }
        msg?.let { Hint(it, C.err) }
        if (files.isNotEmpty()) {
            Divider()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(T("共 ", "合計 ") + files.size + T(" 个，", " 件、") + sizeText(files.sumOf { it.length() }), fontSize = 13.sp, color = C.muted)
                Grow()
                if (confirmAll) {
                    TextBtn(T("取消", "キャンセル")) { confirmAll = false }
                    TextBtn(T("删除全部（正在记录的不删）", "すべて削除（記録中のものは除く）"), danger = true) {
                        confirmAll = false
                        val left = SessionLog.deleteAll(dataDir)
                        msg = if (left > 0) T("还剩 ", "残り ") + left + T(" 个（正在记录或无法删除）", " 件（記録中または削除不可）") else null; tick++
                    }
                } else TextBtn(T("全部清空", "すべて削除"), danger = true) { confirmAll = true }
            }
        }
    }
}


// ---- 关于 --------------------------------------------------------------------------------------------------

@Composable
private fun AboutTab() {
    Group(T("使用说明", "使い方")) {
        Text(T("0. 第一次用：在「设置 › 翻译 › LLM API」里填好你自己的 LLM 服务（OpenAI 兼容接口的地址、模型 ID 和 API Key），点「测试连接」。模型要能关闭思考，回复越快越好。\n" +
            "1. 点首页的「开始字幕」。" + (if (isWindows) "" else "第一次会问你是否允许录制系统声音，请选「允许」（以后可以在 系统设置 › 隐私与安全性 › 屏幕与系统录音 里改）。") + "\n" +
            "2. 字幕浮在所有窗口上面：按住拖动可以换位置；点一下字幕，四个角会出现小箭头，拖它可以改大小。\n" +
            "3. 点一下字幕会出现工具栏：调字号、调背景、切换显示方式、开关原文和用时、锁定，或者停止。锁定后位置和大小都固定，点字幕不再出工具栏，只在右下角出一个解锁按钮，点它就解锁。\n" +
            "4. 字幕听的是${thisComputer}播放出来的所有声音，不用麦克风；同时有别的 App 在放声音时，它的声音也会出现在字幕里。\n" +
            "5. 关掉这个窗口字幕也会继续，${if (isWindows) "任务栏右下角" else "菜单栏"}的同传字幕图标里可以开始、停止，或重新打开这个窗口。\n" +
            "6. 在「记录」里打开「记录每次使用」后，每次使用会留下一份文字记录（不含声音和 key），可以发给开发者帮助反馈问题。默认不记录。",
            "0. 初回使用：「設定 › 翻訳 › LLM API」で自分の LLM サービス（OpenAI 互換の URL・モデルID・API Key）を入力し、「接続テスト」を押します。モデルは思考をオフにでき、応答が速いほど良いです。\n" +
            "1. ホームの「字幕開始」を押します。" + (if (isWindows) "" else "初回はシステム音声の録音を許可するか確認されるので「許可」を選びます（後で システム設定 › プライバシーとセキュリティ › 画面とシステム音声の録音 で変更可）。") + "\n" +
            "2. 字幕は全ウィンドウの上に浮きます：ドラッグで移動、字幕をクリックすると四隅に小さい矢印が出てドラッグでサイズ変更。\n" +
            "3. 字幕をクリックするとツールバーが出ます：文字サイズ・背景・表示方法・原文と所用時間のON/OFF・ロック・停止。ロックすると位置とサイズが固定され、字幕クリックでツールバーは出ず、右下にロック解除ボタンだけが出ます。\n" +
            "4. 字幕は${thisComputer}から再生されるすべての音声を聞きます。マイクは不要です。他のアプリが音を鳴らしているとき、その音も字幕に現れます。\n" +
            "5. このウィンドウを閉じても字幕は続き、${if (isWindows) "タスクバー右下" else "メニューバー"}の同伝字幕アイコンから開始・停止・ウィンドウ再表示ができます。\n" +
            "6. 「記録」で「毎回の使用を記録」をオンにすると、毎回の使用でテキスト記録（音声と key を除く）が残り、開発者に送ってフィードバックできます。既定では記録しません。"),
            fontSize = 14.sp, lineHeight = 22.sp, color = C.text)
    }
    Group(T("关于", "このソフトについて")) {
        Text(T("同传字幕", "同伝字幕") + "  版本 " + APP_VERSION, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
        Divider()
        SwitchRow(T("自动检查更新", "更新を自動チェック"), null, prefs.autoUpdate) { prefs.autoUpdate = it }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (update.value is Update.Checking) T("检查中…", "確認中…") else T("检查更新", "更新を確認"), enabled = update.value !is Update.Checking,
                modifier = Modifier.weight(1f)) { checkNow(manual = true) }
            Btn(T("GitHub 项目主页", "GitHub プロジェクト"), modifier = Modifier.weight(1f)) { open(REPO) }
        }
        when (val u = update.value) {
            is Update.Found -> UpdateBox(u.release)
            is Update.Latest -> Hint(T("已经是最新版本。", "すでに最新バージョンです。"))
            is Update.Failed -> Hint(T("检查失败：", "確認に失敗：") + u.why, C.err)
            else -> {}
        }
        Hint(REPO)
        Hint(T("语音识别用的是 ReazonSpeech 模型和 Silero VAD（通过 sherpa-onnx 在电脑上运行，模型已经装在 App 里）。感谢这些项目。", "音声認識には ReazonSpeech モデルと Silero VAD を使用しています（sherpa-onnx 経由でPC上で実行され、モデルは App に同梱）。これらのプロジェクトに感謝します。"))
    }
}

// ---- updates -----------------------------------------------------------------------------------------------

/** The latest release as update.json describes it; the desktop only needs its version and notes. */
private class Release(val code: Int, val name: String, val notes: String)

private sealed class Update {
    object Idle : Update(); object Checking : Update(); object Latest : Update()
    class Found(val release: Release) : Update(); class Failed(val why: String) : Update()
}
private val update = mutableStateOf<Update>(Update.Idle)
/** a newer release found by the automatic check, shown as a window until the user decides */
private val offered = mutableStateOf<Release?>(null)

private val UPDATE_CLIENT = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

/** update.json from GitHub, then the download proxies (same order as the phone); null with the last error otherwise */
private fun fetchRelease(): Pair<Release?, String?> {
    var why: String? = null
    val latest = "$REPO/releases/latest/download/update.json"
    for (u in listOf(latest, "https://gh-proxy.com/$latest", "https://gh.llkk.cc/$latest")) {
        try {
            UPDATE_CLIENT.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) { why = T("服务器返回 ", "サーバー応答 ") + r.code.toString(); return@use }
                val text = r.body!!.byteStream().use { it.readNBytes(128 * 1024 + 1) }
                require(text.size <= 128 * 1024)
                val j = JSONObject(String(text))
                val code = j.get("versionCode").toString().toInt()
                val name = j.getString("versionName")
                require(code > 0 && name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")))
                return Release(code, name, j.optString("notes").take(8_000)) to null
            }
        } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName }
    }
    return null to (why ?: T("没有回应", "応答なし"))
}

private fun checkNow(manual: Boolean) {
    if (update.value is Update.Checking) return
    update.value = Update.Checking
    Thread {
        val (r, why) = fetchRelease()
        java.awt.EventQueue.invokeLater {
            update.value = when {
                r == null -> if (manual) Update.Failed(why ?: "") else Update.Idle
                r.code > APP_VERSION_CODE -> Update.Found(r)
                else -> if (manual) Update.Latest else Update.Idle
            }
            if (!manual && r != null && r.code > APP_VERSION_CODE && r.code != prefs.skippedUpdate) offered.value = r
        }
    }.apply { isDaemon = true }.start()
}

@Composable
private fun UpdateBox(r: Release) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(C.brandSoft, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(T("有新版本 ", "新しいバージョン ") + r.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (r.notes.isNotBlank()) Text(r.notes, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(T("打开下载页", "ダウンロードページを開く"), primary = true) { open("$REPO/releases/tag/v${r.name}") }
        }
        Hint(if (isWindows) T("下载页里的 windows-x64.zip 是 Windows 版。先退出同传字幕，解压后用新的文件夹替换旧的即可，设置会保留。", "ダウンロードページの windows-x64.zip が Windows 版です。同伝字幕を終了してから解凍し、新しいフォルダで旧フォルダを置き換えれば設定は保持されます。")
            else T("下载页里的 .dmg 是 Mac 版。打开后把同传字幕拖进「应用程序」替换旧版即可，设置会保留。", "ダウンロードページの .dmg が Mac 版です。開いて同伝字幕を「アプリケーション」にドラッグして旧版を置き換えれば設定は保持されます。"))
    }
}

/** Checks once at launch when automatic checks are on; a newer release opens a small window. */
@Composable
private fun ApplicationScope.AutoUpdateCheck() {
    LaunchedEffect(Unit) { if (prefs.autoUpdate && APP_VERSION_CODE > 0) { delay(3_000); checkNow(manual = false) } }
    val r = offered.value ?: return
    val dark = when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    Window(onCloseRequest = { offered.value = null }, title = T("同传字幕 有新版本", "同伝字幕 新しいバージョン"), state = rememberWindowState(size = DpSize(440.dp, 360.dp))) {
        Themed(dark) {
            Column(Modifier.fillMaxSize().background(C.bg).padding(18.dp).verticalScroll(rememberScrollState())) {
                UpdateBox(r)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Grow()
                    TextBtn(T("跳过这个版本", "このバージョンをスキップ")) { prefs.skippedUpdate = r.code; offered.value = null }
                    TextBtn(T("以后再说", "後で")) { offered.value = null }
                }
            }
        }
    }
}
