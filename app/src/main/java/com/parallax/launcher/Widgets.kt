package com.parallax.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val GlassShape = RoundedCornerShape(28.dp)
private val GlassEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.08f)))
private val Romania: ZoneId = ZoneId.of("Europe/Bucharest")

/**
 * A frosted-glass panel. Real backdrop blur is too heavy for this TV's GPU, but the resting home screen's
 * background is already a soft gradient, so the panel draws that same gradient itself, aligned to where
 * it sits on screen, then a milky tint and a bright edge.
 */
@Composable
fun GlassCard(
    background: AnimatedGradient,
    screen: IntSize,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                origin = IntOffset(position.x.toInt(), position.y.toInt())
            }
            .clip(GlassShape)
            .drawBehind {
                // Reading the frame redraws the panel whenever the gradient moves.
                background.frame
                drawImage(
                    image = background.image,
                    srcSize = IntSize(background.image.width, background.image.height),
                    dstOffset = IntOffset(-origin.x, -origin.y),
                    dstSize = screen,
                    filterQuality = FilterQuality.Low,
                )
                drawRect(Color.White.copy(alpha = 0.10f))
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.12f), 0.6f to Color.Transparent))
            }
            .border(1.dp, GlassEdge, GlassShape)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        content = content,
    )
}

/** The current time, ticking over on each minute. */
@Composable
fun rememberNow(): State<Date> = produceState(Date()) {
    while (true) {
        value = Date()
        delay(60_000 - System.currentTimeMillis() % 60_000)
    }
}

@Composable
fun rememberWeather(): State<WeatherInfo?> = produceState<WeatherInfo?>(null) {
    while (true) {
        Weather.load()?.let { value = it }
        // Retry soon if the network wasn't up yet, otherwise refresh every half hour.
        delay(if (value == null) 60_000 else 30 * 60_000)
    }
}

@Composable
fun rememberBarcelonaMatch(): State<Match?> = produceState<Match?>(null) {
    while (true) {
        Football.nextBarcelonaMatch()?.let { value = it }
        // Follow live games and the run-up to kick-off closely; otherwise there's no hurry.
        val soon = value?.let { it.isLive || Duration.between(Instant.now(), it.kickoff) < Duration.ofHours(3) } ?: true
        delay(if (soon) 60_000 else 10 * 60_000)
    }
}

@Composable
fun ClockWidget(now: Date, weather: WeatherInfo?, background: AnimatedGradient, screen: IntSize, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time = remember { DateFormat.getTimeFormat(context) }
    val date = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH) }
    GlassCard(background, screen, modifier) {
        Column {
            BasicText(
                text = time.format(now),
                style = TextStyle(color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
            )
            BasicText(
                text = date.format(now.toInstant().atZone(Romania)),
                style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            if (weather != null) {
                BasicText(
                    text = "${weather.symbol}  ${weather.temperature}°   ↑${weather.high}°  ↓${weather.low}°   ${weather.city}",
                    style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
fun BarcelonaWidget(match: Match?, background: AnimatedGradient, screen: IntSize, modifier: Modifier = Modifier) {
    GlassCard(background, screen, modifier) {
        if (match == null) {
            BasicText(
                text = "FC Barcelona\nLooking for the next match…",
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, lineHeight = 22.sp),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            return@GlassCard
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (match.isLive) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF453A)))
                    Spacer(Modifier.width(8.dp))
                }
                BasicText(
                    text = ((if (match.isLive) "Live · " else "Next match · ") + match.competition).uppercase(),
                    style = Caption,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                TeamBadge(match.home, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.1f)) {
                    if (match.isLive) {
                        BasicText("${match.homeScore ?: 0} – ${match.awayScore ?: 0}", style = Headline)
                        BasicText(match.clock.orEmpty(), style = Detail)
                    } else {
                        val kickoff = match.kickoff.atZone(Romania)
                        BasicText(kickoff.format(DateTimeFormatter.ofPattern("HH:mm")), style = Headline)
                        BasicText(dayLabel(kickoff.toLocalDate()), style = Detail)
                    }
                }
                TeamBadge(match.away, Modifier.weight(1f))
            }
            match.venue?.let {
                BasicText(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TeamBadge(team: Team, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        if (team.logo != null) {
            Image(bitmap = team.logo, contentDescription = team.name, modifier = Modifier.size(48.dp))
        } else {
            Spacer(Modifier.height(48.dp))
        }
        BasicText(
            text = team.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now(Romania)
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
    }
}

private val Caption = TextStyle(
    color = Color.White.copy(alpha = 0.75f),
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp,
)
private val Headline = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
private val Detail = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
