package com.parallax.launcher

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

// A single row of apps that scrolls sideways; the page itself never scrolls vertically.
private val TileWidth = 160.dp
private val RowHeight = 170.dp
private val BackgroundTop = Color(0xFF1E1E26)
private val BackgroundBottom = Color(0xFF0A0A0E)
private val Background = Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom))

@Composable
fun HomeScreen(
    apps: List<AppInfo>,
    userName: String?,
    homePresses: Int,
    onLaunch: (AppInfo) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onMoveFinished: () -> Unit,
) {
    val rowState = rememberLazyListState()
    val firstTile = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var lastDirection by remember { mutableStateOf(FocusDirection(0f, 0f)) }

    // The app being rearranged, if any. While set, the D-pad moves it instead of moving focus.
    var movingId by remember { mutableStateOf<String?>(null) }

    // Fetch featured titles up front so the Top Shelf can switch to them instantly.
    LaunchedEffect(apps.map { it.id }) {
        for (app in apps) {
            if (!Tmdb.supports(app.id)) continue
            val items = Tmdb.itemsFor(app.id)
            if (items.isNotEmpty()) Tmdb.backdrop(Tmdb.current(app.id, items).backdropUrl)
        }
    }

    // Home/Back while already home: jump back to the first app.
    LaunchedEffect(homePresses, apps.isNotEmpty()) {
        if (apps.isEmpty() || movingId != null) return@LaunchedEffect
        rowState.animateScrollToItem(0)
        runCatching { firstTile.requestFocus() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Background)
            .onPreviewKeyEvent { event ->
                val moving = movingId
                if (moving == null) {
                    if (event.type == KeyEventType.KeyDown) {
                        directionOf(event.key)?.let { lastDirection = it }
                    }
                    return@onPreviewKeyEvent false
                }

                // Rearrange mode swallows every key so nothing launches or moves focus by accident.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                // The OK button that started the long press is probably still held down; ignore its repeats.
                if (event.nativeKeyEvent.repeatCount > 0 && directionOf(event.key) == null) return@onPreviewKeyEvent true
                val from = apps.indexOfFirst { it.id == moving }
                val to = when (event.key) {
                    Key.DirectionLeft -> from - 1
                    Key.DirectionRight -> from + 1
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> {
                        movingId = null
                        onMoveFinished()
                        -1
                    }
                    else -> -1
                }
                if (from >= 0 && to in apps.indices && to != from) {
                    directionOf(event.key)?.let { lastDirection = it }
                    onMove(from, to)
                    focusedIndex = to
                }
                true
            },
    ) {
        val shelfApp = apps.getOrNull(focusedIndex.coerceAtMost(apps.lastIndex))
        TopShelf(app = shelfApp, modifier = Modifier.fillMaxSize())

        LazyRow(
            state = rowState,
            // Vertical padding leaves room for the focused tile to grow without being clipped.
            contentPadding = PaddingValues(horizontal = 72.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 36.dp)
                .fillMaxWidth()
                .height(RowHeight),
        ) {
            itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
                AppTile(
                    app = app,
                    index = index,
                    moving = app.id == movingId,
                    entryDirection = { lastDirection },
                    onClick = { onLaunch(app) },
                    onLongClick = { movingId = app.id },
                    onFocused = { focusedIndex = index },
                    modifier = Modifier
                        .width(TileWidth)
                        .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier),
                )
            }
        }

        Column(
            Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 72.dp),
        ) {
            if (movingId != null) {
                BasicText(
                    text = "Move with the arrows · press OK to drop",
                    style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp),
                )
            } else {
                Greeting(userName)
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 56.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Clock()
            WeatherLine()
        }
    }
}

private fun directionOf(key: Key): FocusDirection? = when (key) {
    Key.DirectionLeft -> FocusDirection(-1f, 0f)
    Key.DirectionRight -> FocusDirection(1f, 0f)
    Key.DirectionUp -> FocusDirection(0f, -1f)
    Key.DirectionDown -> FocusDirection(0f, 1f)
    else -> null
}

private sealed interface ShelfContent {
    /** Blurred app banner, for apps without featured titles. */
    data class Ambient(val app: AppInfo) : ShelfContent

    /** A popular show or film from the focused streaming service. */
    data class Feature(val app: AppInfo, val item: ShelfItem, val image: ImageBitmap) : ShelfContent
}

@Composable
private fun TopShelf(app: AppInfo?, modifier: Modifier = Modifier) {
    var content by remember { mutableStateOf<ShelfContent?>(null) }

    LaunchedEffect(app?.id) {
        if (app == null) return@LaunchedEffect
        if (!Tmdb.supports(app.id)) {
            content = ShelfContent.Ambient(app)
            return@LaunchedEffect
        }
        // Wait for focus to settle so flicking past apps doesn't fetch each one,
        // and fall back to the banner if TMDB is slow or unreachable.
        delay(250)
        val fallback = launch {
            delay(700)
            content = ShelfContent.Ambient(app)
        }
        val items = Tmdb.itemsFor(app.id)
        if (items.isEmpty()) return@LaunchedEffect
        while (true) {
            val item = Tmdb.current(app.id, items)
            Tmdb.backdrop(item.backdropUrl)?.let {
                fallback.cancel()
                content = ShelfContent.Feature(app, item, it)
            }
            // Move on now, so leaving and coming back shows a different title; warm its backdrop meanwhile.
            Tmdb.advance(app.id)
            launch { Tmdb.backdrop(Tmdb.current(app.id, items).backdropUrl) }
            delay(9_000)
        }
    }

    Box(modifier) {
        Crossfade(targetState = content, animationSpec = tween(600), label = "shelf") { shown ->
            when (shown) {
                is ShelfContent.Ambient -> AmbientShelf(shown.app)
                is ShelfContent.Feature -> FeatureShelf(shown)
                null -> Unit
            }
        }
        // Darken the top for the clock and melt the bottom into the wallpaper under the app row.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.3f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.82f to BackgroundBottom.copy(alpha = 0.92f),
                        1f to BackgroundBottom,
                    ),
                ),
        )
    }
}

@Composable
private fun AmbientShelf(app: AppInfo) {
    Box(Modifier.fillMaxSize()) {
        if (app.backdrop != null) {
            // The backdrop is a tiny pre-blurred bitmap; stretching it with smooth filtering keeps it soft and cheap.
            Image(
                bitmap = app.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                alpha = 0.8f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        BasicText(
            text = app.label,
            style = TextStyle(color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 72.dp, bottom = 120.dp),
        )
    }
}

@Composable
private fun FeatureShelf(feature: ShelfContent.Feature) {
    val item = feature.item
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = feature.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Shade the left side so the text reads on any artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.8f),
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        0.7f to Color.Transparent,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 72.dp, bottom = 232.dp)
                .fillMaxWidth(0.46f),
        ) {
            val provider = Tmdb.providerName(feature.app.id) ?: feature.app.label
            val details = listOfNotNull(if (item.isMovie) "Film" else "Series", item.year).joinToString(" · ")
            BasicText(
                text = "Popular on $provider  ·  $details".uppercase(),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                ),
            )
            BasicText(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 6.dp),
            )
            BasicText(
                text = item.overview,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Greeting(name: String?) {
    BasicText(
        text = if (name.isNullOrBlank()) "Hello!" else "Hello, $name!",
        style = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    )
    BasicText(
        text = "What are we watching today?",
        style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp),
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun WeatherLine() {
    var weather by remember { mutableStateOf<WeatherInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            Weather.load()?.let { weather = it }
            // Retry soon if the network wasn't up yet, otherwise refresh every half hour.
            delay(if (weather == null) 60_000 else 30 * 60_000)
        }
    }
    val w = weather ?: return
    BasicText(
        text = "${w.symbol}  ${w.temperature}°  ·  ${w.high}° / ${w.low}°  ·  ${w.city}",
        style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp),
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = format.format(Date())
            delay(15_000)
        }
    }
    BasicText(
        text = now,
        style = TextStyle(color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Medium),
        modifier = modifier,
    )
}
