package cn.com.omnimind.nativeui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import kotlin.random.Random

/** One quick prompt the greeting can offer (Dart `HomeQuickPrompt`, localized). */
@Immutable
data class ChatQuickPrompt(val id: String, val title: String, val prompt: String)

/** The empty page's greeting (5e-4). Null hides it (greeting turned off). */
@Immutable
data class ChatGreetingState(
    val agentName: String = "",
    val quickPrompts: List<ChatQuickPrompt> = emptyList(),
    val pinnedPromptIds: List<String> = emptyList(),
)

private val GREETING_WORDS_ZH = listOf("聊天", "执行", "构建", "探索", "规划", "总结", "检索", "记忆")
private val GREETING_WORDS_EN = listOf("chat", "execute", "build", "explore", "plan", "summarize", "search", "remember")

/**
 * Dart `_RandomQuickPromptPills._refreshSelection`: up to two pinned prompts
 * in pinned order; otherwise up to two prompts, a random pair when there are
 * more. Prompts without a title are never offered.
 */
fun selectGreetingPrompts(prompts: List<ChatQuickPrompt>, pinnedIds: List<String>, random: Random = Random.Default): List<ChatQuickPrompt> {
    val candidates = prompts.filter { it.title.isNotBlank() }
    val pinned = pinnedIds.map(String::trim).filter(String::isNotEmpty).take(2)
    if (pinned.isNotEmpty()) {
        val byId = candidates.associateBy { it.id }
        return pinned.mapNotNull(byId::get).take(2)
    }
    if (candidates.size <= 2) return candidates
    return candidates.shuffled(random).take(2)
}

/** Dart `_SlotWordRotator._advance`: a random word, never the current one. */
fun nextGreetingWord(current: Int, count: Int, random: Random = Random.Default): Int {
    if (count <= 1) return 0
    val next = random.nextInt(count)
    return if (next == current) (next + 1) % count else next
}

/**
 * Ported from `widgets/chat_empty_greeting.dart`: headline with the Agent
 * name, a rotating keyword every 1.8 s, and up to two quick prompts. A tap
 * only fills the composer ([onPrompt]); it never sends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatEmptyGreeting(state: ChatGreetingState, onPrompt: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalOmniPalette.current
    val english = LocalConfiguration.current.locales[0].language == "en"
    val name = state.agentName.trim().ifEmpty { if (english) "Omnibot" else "小万" }
    val headline = if (english) "Hi 👋, I'm $name" else "你好👋，我是$name"
    val prefix = if (english) "I can help you" else "我可以帮助你"
    val words = if (english) GREETING_WORDS_EN else GREETING_WORDS_ZH
    var wordIndex by rememberSaveable { mutableIntStateOf(Random.nextInt(words.size)) }
    LaunchedEffect(words) {
        while (true) {
            delay(1_800)
            wordIndex = nextGreetingWord(wordIndex, words.size)
        }
    }
    val prompts = remember(state.quickPrompts, state.pinnedPromptIds) {
        selectGreetingPrompts(state.quickPrompts, state.pinnedPromptIds)
    }
    val label = if (english) "$headline\n$prefix chat, execute, build, and explore." else "$headline\n$prefix 聊天、执行、构建和探索。"
    Column(modifier.widthIn(max = 520.dp).padding(horizontal = 28.dp).semantics(mergeDescendants = true) { contentDescription = label }) {
        Text(headline, fontSize = 19.sp, lineHeight = 24.7.sp, color = palette.text)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(prefix, fontSize = 19.sp, lineHeight = 24.7.sp, color = palette.secondaryText)
            AnimatedContent(
                targetState = wordIndex.coerceIn(words.indices),
                transitionSpec = {
                    (slideInVertically(tween(460)) { it } + fadeIn(tween(460))) togetherWith
                        (slideOutVertically(tween(460)) { -it } + fadeOut(tween(460)))
                },
                label = "greetingWord",
            ) { index ->
                Text(words[index], fontSize = 19.sp, lineHeight = 24.7.sp, color = palette.accent, fontFamily = FontFamily.Serif)
            }
        }
        if (prompts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompts.forEach { prompt ->
                    Text(
                        prompt.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (palette.dark) palette.accent else palette.text,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.accent.copy(alpha = if (palette.dark) .13f else .09f))
                            .clickable(role = Role.Button) { onPrompt(prompt.prompt) }
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}
