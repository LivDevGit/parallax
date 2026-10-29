package com.parallax.launcher

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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
// Matches the window background in res/values/colors.xml; the Top Shelf overlay fades into it.
private val PageColor = Color(0xFF0A0A0E)
private const val IdleTimeoutMillis = 5_000L

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

    // The home screen rests with the apps hidden and widgets showing. Any key brings the apps back,
    // and they tuck away again after a few seconds without input.
    var appsShown by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(0L) }
    LaunchedEffect(appsShown, lastInput, movingId) {
        if (appsShown && movingId == null) {
            delay(IdleTimeoutMillis)
            appsShown = false
        }
    }
    val reveal by animateFloatAsState(if (appsShown) 1f else 0f, tween(450), label = "reveal")

    // The resting home screen's background, which also shows through the glass widgets.
    val gradient = rememberAnimatedGradient(running = !appsShown)
    val now by rememberNow()
    val weather by rememberWeather()
    val match by rememberBarcelonaMatch()

    // Fetch every service's titles and its next backdrops up front, so the Top Shelf switches instantly.
    LaunchedEffect(apps.map { it.id }) {
        for (app in apps) {
            if (!Tmdb.supports(app.id)) continue
            val items = Tmdb.itemsFor(app.id)
            if (items.isNotEmpty()) Tmdb.preload(app.id, items)
        }
    }

    // Home/Back while already home: jump back to the first app.
    LaunchedEffect(homePresses, apps.isNotEmpty()) {
        if (apps.isEmpty() || movingId != null) return@LaunchedEffect
        appsShown = false
        rowState.animateScrollToItem(0)
        runCatching { firstTile.requestFocus() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) lastInput = event.nativeKeyEvent.eventTime
                if (!appsShown) {
                    // The first press only wakes the apps up, so it can't launch or move anything by accident.
                    if (event.type == KeyEventType.KeyDown) appsShown = true
                    return@onPreviewKeyEvent true
                }
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
        // The gradient rests underneath; the Top Shelf fades in over it with the apps. Each layer is skipped
        // entirely when fully hidden, so only one full-screen background is ever drawn at rest.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (reveal < 1f) 1f else 0f }
                .drawBehind {
                    gradient.frame
                    drawImage(
                        image = gradient.image,
                        srcSize = IntSize(gradient.image.width, gradient.image.height),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.Low,
                    )
                },
        )
        TopShelf(
            app = shelfApp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal; compositingStrategy = CompositingStrategy.ModulateAlpha },
        )
        val screen = with(LocalDensity.current) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }

        LazyRow(
            state = rowState,
            // Vertical padding leaves room for the focused tile to grow without being clipped.
            contentPadding = PaddingValues(horizontal = 72.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 36.dp)
                .fillMaxWidth()
                .height(RowHeight)
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 60.dp.toPx()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .graphicsLayer {
                    alpha = 1f - reveal
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            ClockWidget(now, weather, gradient, screen, Modifier.width(300.dp).height(156.dp))
            BarcelonaWidget(match, gradient, screen, Modifier.width(400.dp).height(156.dp))
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
        // The compact clock gives way to the clock widget while the apps are hidden.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 56.dp)
                .graphicsLayer { alpha = reveal; compositingStrategy = CompositingStrategy.ModulateAlpha },
            horizontalAlignment = Alignment.End,
        ) {
            Clock(now)
            weather?.let { WeatherLine(it) }
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
        // Change the shelf only once focus rests, so scrolling quickly along the row doesn't stack
        // several full-screen crossfades on top of each other.
        delay(150)
        if (!Tmdb.supports(app.id)) {
            content = ShelfContent.Ambient(app)
            return@LaunchedEffect
        }
        val cached = Tmdb.cachedItems(app.id)
        if (cached == null || Tmdb.cachedBackdrop(Tmdb.current(app.id, cached).backdropUrl) == null) {
            // Not preloaded yet: wait a little longer before fetching, so flicking past apps doesn't fetch
            // each one, and fall back to the banner if TMDB is slow or unreachable.
            delay(150)
        }
        val fallback = launch {
            delay(600)
            content = ShelfContent.Ambient(app)
        }
        val items = cached ?: Tmdb.itemsFor(app.id)
        if (items.isEmpty()) return@LaunchedEffect
        while (true) {
            val item = Tmdb.current(app.id, items)
            Tmdb.backdrop(item.backdropUrl)?.let {
                fallback.cancel()
                content = ShelfContent.Feature(app, item, it)
            }
            // Move on now, so leaving and coming back shows a different title; warm the next ones meanwhile.
            Tmdb.advance(app.id)
            launch { Tmdb.preload(app.id, items) }
            delay(9_000)
            // No frames are drawn while another app is in front, so this pauses the rotation (and its
            // downloads) until the launcher is back on screen.
            withFrameNanos { }
        }
    }

    // Artwork and text crossfade separately, with the shared overlay between them, so that during a
    // transition the GPU blends two images and one overlay rather than two full stacks of layers.
    // Everything draws straight to the screen: on this TV's GPU an offscreen layer for the shelf cost
    // more than it saved (measured: ~4% janky frames with it, ~1% without).
    Box(modifier.background(PageColor)) {
        LightCrossfade(content) { shown ->
            when (shown) {
                is ShelfContent.Ambient -> shown.app.backdrop?.let {
                    // A tiny pre-blurred bitmap; stretching it with smooth filtering keeps it soft and cheap.
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        alpha = 0.8f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is ShelfContent.Feature -> Image(
                    bitmap = shown.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                null -> Unit
            }
        }
        Image(
            bitmap = Precomputed.shelfOverlay,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        LightCrossfade(content) { shown ->
            when (shown) {
                is ShelfContent.Ambient -> AmbientText(shown.app)
                is ShelfContent.Feature -> FeatureText(shown)
                null -> Unit
            }
        }
    }
}

/**
 * A crossfade that's cheap on a weak GPU. Compose's Crossfade renders each fading child offscreen first;
 * this one applies alpha while drawing instead, and never has more than two children on screen, even when
 * the target changes again mid-fade.
 */
@Composable
private fun <T> LightCrossfade(target: T, durationMillis: Int = 350, content: @Composable (T) -> Unit) {
    // Boxed so a null target is still a distinct slot.
    class Slot(val value: T)
    var current by remember { mutableStateOf(Slot(target)) }
    var previous by remember { mutableStateOf<Slot?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == current.value) return@LaunchedEffect
        previous = current
        current = Slot(target)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis))
        previous = null
    }
    Box {
        previous?.let { slot ->
            key(slot) {
                Box(Modifier.graphicsLayer { alpha = 1f - progress.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
                    content(slot.value)
                }
            }
        }
        key(current) {
            Box(Modifier.graphicsLayer { alpha = progress.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
                content(current.value)
            }
        }
    }
}

@Composable
private fun AmbientText(app: AppInfo) {
    Box(Modifier.fillMaxSize()) {
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
private fun FeatureText(feature: ShelfContent.Feature) {
    val item = feature.item
    Box(Modifier.fillMaxSize()) {
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
private fun WeatherLine(w: WeatherInfo) {
    BasicText(
        text = "${w.symbol}  ${w.temperature}°  ·  ${w.high}° / ${w.low}°  ·  ${w.city}",
        style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp),
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun Clock(now: Date, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    BasicText(
        text = format.format(now),
        style = TextStyle(color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Medium),
        modifier = modifier,
    )
}
