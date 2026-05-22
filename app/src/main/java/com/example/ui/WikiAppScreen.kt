package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import com.example.data.*
import com.example.viewmodel.*
import com.example.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiAppScreen(
    viewModel: WikiViewModel,
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val selectedPage by viewModel.selectedPage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var activeEditorialNote by remember { mutableStateOf<RawSource?>(null) }
    var activeIsCreatingNote by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importWikiFromZip(context, uri) { resultMessage ->
                Toast.makeText(context, resultMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_trans")
    val alphaPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val userName by viewModel.userName.collectAsStateWithLifecycle()
    var isSettingsOpen by remember { mutableStateOf(false) }

    // First-launch gate. Until the user supplies an API key, picks a model, and taps the
    // "Get Started" button, the main UI is hidden. This avoids the empty-wiki "what does this
    // even do" cold start.
    val onboardingDone by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    if (!onboardingDone) {
        OnboardingScreen(viewModel = viewModel, onDone = { viewModel.completeOnboarding() })
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isSettingsOpen) {
            UserProfileSettingsScreen(
                viewModel = viewModel,
                onClose = { isSettingsOpen = false },
                importLauncher = importLauncher
            )
        } else if (activeEditorialNote != null || activeIsCreatingNote) {
            FullScreenWysiwygEditor(
                note = activeEditorialNote,
                isNew = activeIsCreatingNote,
                viewModel = viewModel,
                onClose = {
                    activeEditorialNote = null
                    activeIsCreatingNote = false
                }
            )
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
            Column {
                TopAppBar(
                    title = {
                        // Title reflects current context: open page > current tab name. Each tab
                        // has its own eyebrow so identity is clear even on the slim screens.
                        val tabTitles = listOf("Wiki", "Notes", "Ask", "Events", "Linked")
                        val tabEyebrows = listOf("LIBRARY", "CAPTURE", "INQUIRY", "AGENDA", "AUDIT")
                        val safeIdx = currentTab.coerceIn(0, tabTitles.lastIndex)
                        val resolvedTitle = selectedPage?.title ?: tabTitles[safeIdx]
                        val resolvedEyebrow = if (selectedPage != null) "FROM YOUR WIKI" else tabEyebrows[safeIdx]
                        Column {
                            Text(
                                text = resolvedTitle,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = resolvedEyebrow,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontSize = 9.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    },
                    actions = {
                        val initials = remember(userName) {
                            val parts = userName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                            when {
                                parts.isEmpty() -> "??"
                                parts.size == 1 -> parts[0].take(2).uppercase()
                                else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape)
                                .clickable { isSettingsOpen = true }
                                .testTag("profile_avatar_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), thickness = 0.5.dp)
            }
        },
        bottomBar = {
            // Custom magazine-style bottom nav: text labels in uppercase, active state is a
            // thin underline rule. No pill backgrounds, no icons. Looks like a contents footer.
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("bottom_navigation_bar")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf("Wiki", "Notes", "Search", "Events", "Linked")
                    tabs.forEachIndexed { index, label ->
                        val isSelected = currentTab == index
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clickable { viewModel.switchTab(index) }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    letterSpacing = 1.5.sp,
                                    fontSize = 11.sp
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                            Box(
                                modifier = Modifier
                                    .height(2.dp)
                                    .width(if (isSelected) 22.dp else 0.dp)
                                    .background(MaterialTheme.colorScheme.onBackground)
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Tab content is now a HorizontalPager — swipe left/right to navigate between tabs.
            // The bottom nav and the pager stay in sync: tapping a nav item animates the pager,
            // and swiping updates the ViewModel's currentTab.
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                initialPage = currentTab.coerceIn(0, 4),
                pageCount = { 5 }
            )
            // Pager → ViewModel: only sync once the pager has *settled* on a page. Using
            // currentPage instead would fire mid-animation and cancel the animation triggered
            // by a bottom-nav tap. settledPage is stable until the gesture/animation completes.
            androidx.compose.runtime.LaunchedEffect(pagerState) {
                androidx.compose.runtime.snapshotFlow { pagerState.settledPage }
                    .collect { settled ->
                        if (settled != currentTab) viewModel.switchTab(settled)
                    }
            }
            // ViewModel → Pager: when the bottom nav taps a tab, animate the pager.
            androidx.compose.runtime.LaunchedEffect(currentTab) {
                if (pagerState.currentPage != currentTab) {
                    pagerState.animateScrollToPage(currentTab)
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Allow user input swipes; otherwise pager just renders the content.
                userScrollEnabled = true
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> WikiIndexScreen(viewModel)
                    1 -> NotesScreen(
                        viewModel = viewModel,
                        onEditNote = { note -> activeEditorialNote = note },
                        onCreateNote = { activeIsCreatingNote = true }
                    )
                    2 -> QueryScreen(viewModel)
                    3 -> EventsScreen(viewModel)
                    4 -> LintScreen(viewModel)
                }
            }
        }
    }
        }
    }
}

@Composable
fun SwipeDismissContainer(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(dampingRatio = Modifier.Companion.let { Spring.DampingRatioMediumBouncy }, stiffness = Spring.StiffnessLow),
        label = "swipe_offset"
    )
    val alpha by animateFloatAsState(
        targetValue = if (offsetX != 0f) (1f - (kotlin.math.abs(offsetX) / 400f)).coerceIn(0.1f, 1f) else 1f,
        label = "swipe_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(kotlin.math.round(animatedOffsetX).toInt(), 0) }
            .graphicsLayer(alpha = alpha)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(offsetX) > 150f) {
                            onDismiss()
                        }
                        offsetX = 0f
                    },
                    onDragCancel = {
                        offsetX = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount
                    }
                )
            }
    ) {
        content()
    }
}

// ==================== TAB 0: WIKI INDEX & GRAPH VIEW ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiIndexScreen(viewModel: WikiViewModel) {
    val pages by viewModel.wikiPages.collectAsStateWithLifecycle()
    val selectedPage by viewModel.selectedPage.collectAsStateWithLifecycle()
    val activeReminders by viewModel.activeReminders.collectAsStateWithLifecycle()
    val ingestState by viewModel.ingestState.collectAsStateWithLifecycle()
    val logs by viewModel.activityLogs.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var isGraphMode by remember { mutableStateOf(false) }
    var isRemindersOpen by remember { mutableStateOf(false) }
    val dismissedReminderId by viewModel.dismissedReminderId.collectAsStateWithLifecycle()
    var isReminderCollapsed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_trans")
    val alphaPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Derive list of all existing unique tags
    val tagsList = remember(pages) {
        pages.flatMap { page ->
            page.tags.split(",").map { it.trim() }
        }.filter { it.isNotEmpty() }.distinct()
    }

    // Filtered list
    val filteredPages = remember(pages, searchQuery, selectedTag) {
        pages.filter { page ->
            val titleMatches = page.title.contains(searchQuery, ignoreCase = true)
            val contentMatches = page.content.contains(searchQuery, ignoreCase = true)
            val tagMatches = selectedTag == null || page.tags.split(",").any { it.trim().equals(selectedTag, ignoreCase = true) }
            (titleMatches || contentMatches) && tagMatches
        }
    }

    if (selectedPage != null) {
        PageDetailView(
            page = selectedPage!!,
            allPages = pages,
            onClose = { viewModel.selectPage(null) },
            onLinkClicked = { title -> viewModel.selectPageByTitle(title) }
        )
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth > 680.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Left Column: Wiki index workspace content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp)
                ) {
                    // === MAGAZINE MASTHEAD ===
                    // While the agent is actively streaming, the masthead is hijacked by a live
                    // "AGENT" view showing the in-flight thinking text. Once it finishes, the
                    // normal Library masthead returns.
                    val agentActivityNow by viewModel.agentActivity.collectAsStateWithLifecycle()
                    if (agentActivityNow.isActive) {
                        LibraryAgentTakeover(activity = agentActivityNow)
                    } else {
                    val today = remember {
                        val cal = java.util.Calendar.getInstance()
                        val month = listOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")[cal.get(java.util.Calendar.MONTH)]
                        "$month ${cal.get(java.util.Calendar.DAY_OF_MONTH)}, ${cal.get(java.util.Calendar.YEAR)}"
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "VOL. I",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = today,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        // Wordmark — single editorial word at scale, sized to dominate the screen.
                        Text(
                            text = if (isGraphMode) "Graph" else "Library",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 64.sp,
                                lineHeight = 64.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // Thick hairline beneath the wordmark — magazine masthead rule.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.5.dp)
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Byline row: page count + utility links.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val pageCount = filteredPages.size
                            Text(
                                text = if (pageCount == 0) "No entries" else "${pageCount} ${if (pageCount == 1) "entry" else "entries"}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                listOf("Index" to false, "Graph" to true).forEach { (label, isGraph) ->
                                    val active = isGraph == isGraphMode
                                    Text(
                                        text = label.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.5.sp,
                                            textDecoration = if (active) androidx.compose.ui.text.style.TextDecoration.Underline else null
                                        ),
                                        color = if (active) MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.clickable { isGraphMode = isGraph }
                                    )
                                }
                                IconButton(
                                    onClick = { isRemindersOpen = !isRemindersOpen },
                                    modifier = Modifier.size(24.dp).testTag("toggle_reminders_bell")
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (activeReminders.isNotEmpty()) {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                ) {
                                                    Text(activeReminders.size.toString(), style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isRemindersOpen) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                            contentDescription = "Schedule",
                                            tint = if (isRemindersOpen) MaterialTheme.colorScheme.onBackground
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    } // end of `else` for agent-takeover gate

                    if (isGraphMode) {
                        WikiRelationshipGraph(
                            pages = pages,
                            onNodeSelected = { page -> viewModel.selectPage(page) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = 16.dp)
                        )
                    } else {
                        // All elements scroll together as items in a single LazyColumn
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("wiki_items_lazy_column"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            // 0. LIVE AGENT BANNER — appears when an ingest is in flight or when
                            //    the most recent run still has thinking/answer text to inspect.
                            item(key = "live_agent_banner") {
                                LiveAgentBanner(viewModel = viewModel)
                            }

                            // 1. ACTIVE REMINDERS (COLLAPSIBLE / SWIPABLE)
                            // Only show when there's actually a reminder AND it isn't the one
                            // the user already dismissed. A new reminder (different id) brings
                            // the banner back; dismissals never silently reappear.
                            val topReminder = activeReminders.firstOrNull()
                            val showReminderBanner = topReminder != null && topReminder.id != dismissedReminderId
                            if (showReminderBanner) {
                                item(key = "active_reminder_banner_${topReminder!!.id}") {
                                    SwipeDismissContainer(
                                        onDismiss = { viewModel.dismissReminderBanner(topReminder.id) }
                                    ) {
                                        val activeRem = topReminder
                                        if (isReminderCollapsed) {
                                            // COLLAPSED ULTRA-MINOR SLIM PILL/CARD
                                            Card(
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)
                                                ),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { isReminderCollapsed = false }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.NotificationsActive,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.secondary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Text(
                                                            text = "Active Reminder: ${activeRem.title}",
                                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = alphaPulse))
                                                        )
                                                        Icon(
                                                            imageVector = Icons.Default.ExpandMore,
                                                            contentDescription = "Expand",
                                                            tint = MaterialTheme.colorScheme.secondary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // EXPANDED GORGEOUS HERO CARD
                                            Card(
                                                shape = RoundedCornerShape(topStart = 24.dp, bottomEnd = 24.dp, topEnd = 6.dp, bottomStart = 6.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        brush = Brush.linearGradient(
                                                            colors = listOf(
                                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                                            )
                                                        ),
                                                        shape = RoundedCornerShape(topStart = 24.dp, bottomEnd = 24.dp, topEnd = 6.dp, bottomStart = 6.dp)
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        brush = Brush.linearGradient(
                                                            colors = listOf(
                                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                                            )
                                                        ),
                                                        shape = RoundedCornerShape(topStart = 24.dp, bottomEnd = 24.dp, topEnd = 6.dp, bottomStart = 6.dp)
                                                    ),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(18.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(
                                                                text = "🕒 ACTIVE REMINDER",
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    fontWeight = FontWeight.Bold,
                                                                    letterSpacing = 1.2.sp
                                                                ),
                                                                color = MaterialTheme.colorScheme.secondary
                                                            )
                                                            IconButton(
                                                                onClick = { isReminderCollapsed = true },
                                                                modifier = Modifier.size(18.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.ExpandLess,
                                                                    contentDescription = "Collapse",
                                                                    tint = MaterialTheme.colorScheme.secondary,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                        }
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = alphaPulse))
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(8.dp))

                                                    Text(
                                                        text = activeRem.title,
                                                        style = MaterialTheme.typography.headlineSmall.copy(
                                                            fontFamily = FontFamily.Serif,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 18.sp,
                                                            lineHeight = 24.sp
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (activeRem.description.isNotBlank()) {
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = activeRem.description,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(12.dp))

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(100.dp))
                                                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                                                    .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), RoundedCornerShape(100.dp))
                                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                                            ) {
                                                                Text(
                                                                    text = "🕒 " + activeRem.dateText,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                                    fontSize = 10.sp
                                                                )
                                                            }

                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(100.dp))
                                                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                                                    .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f), RoundedCornerShape(100.dp))
                                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                                            ) {
                                                                Text(
                                                                    text = activeRem.category,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                                    fontSize = 10.sp
                                                                )
                                                            }
                                                        }

                                                        TextButton(
                                                            onClick = { viewModel.toggleReminder(activeRem.id, true) }
                                                        ) {
                                                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Mark Completed", style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // SEARCH BAR (integrated into scrollable items)
                            item(key = "search_field_bar") {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp)
                                        .testTag("search_field"),
                                    placeholder = {
                                        Text(
                                            "Search...",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(
                                                    Icons.Default.Clear,
                                                    contentDescription = "Clear",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    },
                                    // No outlined box — quieter underlined search field, transparent container.
                                    shape = RoundedCornerShape(0.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                                )
                            }

                            // 4. CHIPS ROW
                            if (tagsList.isNotEmpty()) {
                                item(key = "tags_filter_chips_row") {
                                    LazyRow(
                                        modifier = Modifier.padding(bottom = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = selectedTag == null,
                                                onClick = { selectedTag = null },
                                                label = { Text("All Tags") },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                                                    selectedLabelColor = MaterialTheme.colorScheme.secondary
                                                )
                                            )
                                        }
                                        items(tagsList) { tag ->
                                            val isChipSelected = selectedTag == tag
                                            FilterChip(
                                                selected = isChipSelected,
                                                onClick = { selectedTag = if (isChipSelected) null else tag },
                                                label = { Text("#$tag") },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                                                    selectedLabelColor = MaterialTheme.colorScheme.secondary
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            // 5. PAGES LIST OR EMPTY VIEW
                            if (filteredPages.isEmpty()) {
                                item(key = "empty_library_view") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(14.dp),
                                            modifier = Modifier.padding(top = 32.dp)
                                        ) {
                                            // Editorial empty state — concentric arc mark (same
                                            // language as the onboarding hero), serif headline,
                                            // single quiet helper line.
                                            Canvas(modifier = Modifier.size(96.dp)) {
                                                val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                                                repeat(4) { i ->
                                                    drawCircle(
                                                        color = Color.White.copy(alpha = 0.06f + i * 0.04f),
                                                        radius = (size.minDimension / 2) - (i * 10f),
                                                        center = center,
                                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                                                    )
                                                }
                                                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 4f, center = center)
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Nothing yet.",
                                                style = MaterialTheme.typography.headlineMedium.copy(
                                                    fontFamily = FontFamily.Serif,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = "Drop a note, voice memo, or URL\nfrom the Notes tab and it appears here.",
                                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Magazine TOC — every entry is numbered (01, 02, ...), with the
                                // number sitting in a tabular left gutter. Date stamp + serif title
                                // + excerpt + tag track sit to the right. Hairline rule beneath.
                                itemsIndexed(filteredPages, key = { _, p -> p.title }) { idx, page ->
                                    val number = String.format("%02d", idx + 1)
                                    val dateStr = remember(page.lastUpdated) {
                                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = page.lastUpdated }
                                        val m = listOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")[cal.get(java.util.Calendar.MONTH)]
                                        "$m ${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectPage(page) }
                                            .padding(top = 18.dp, bottom = 18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        // Left gutter — big tabular numeral acting as a TOC index.
                                        Column(
                                            modifier = Modifier.width(40.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = number,
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontFamily = FontFamily.Serif,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 22.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(18.dp)
                                                    .height(1.dp)
                                                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                            )
                                            Text(
                                                text = dateStr,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 1.2.sp,
                                                    fontSize = 9.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = page.title,
                                                style = MaterialTheme.typography.headlineSmall.copy(
                                                    fontFamily = FontFamily.Serif,
                                                    fontWeight = FontWeight.Bold,
                                                    lineHeight = 28.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = stripWikiLinks(page.content),
                                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (page.tags.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = page.tags.split(",")
                                                        .map { it.trim() }
                                                        .filter { it.isNotBlank() }
                                                        .joinToString("  ·  ") { it.uppercase() },
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 1.5.sp,
                                                        fontSize = 9.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(0.5.dp)
                                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                    )
                                }
                            }
                        }
                    }
                }

                // Sliding card reminders pane shown on the right (referencing situations)
                AnimatedVisibility(
                    visible = isRemindersOpen || isWideScreen,
                    enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .padding(start = 8.dp, top = 8.dp, bottom = 16.dp, end = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Tasks & Schedule",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!isWideScreen) {
                                    IconButton(onClick = { isRemindersOpen = false }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close Schedule Pane", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            Text(
                                text = "Auto-extracted with AI from your notes, image snaps, voice logs and briefings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (activeReminders.isEmpty()) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircleOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "All Cooked & Done!",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "No reminders pending.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(activeReminders) { rem ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.Top,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = rem.title,
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "🕒 " + rem.dateText,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.secondary,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Checkbox(
                                                        checked = rem.isCompleted,
                                                        onCheckedChange = { isChecked ->
                                                            viewModel.toggleReminder(rem.id, isChecked)
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                                if (rem.description.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = rem.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(100.dp))
                                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = rem.category,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = { viewModel.deleteReminder(rem.id) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Reminder", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 1: JOURNAL INGESTION ====================

@Composable
fun FormatToolItem(
    icon: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            CompositionLocalProvider(
                LocalContentColor provides if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            ) {
                icon()
            }
        } else if (content != null) {
            CompositionLocalProvider(
                LocalContentColor provides if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            ) {
                content()
            }
        }
    }
}

@Composable
fun NotesScreen(
    viewModel: WikiViewModel,
    onEditNote: (RawSource) -> Unit,
    onCreateNote: () -> Unit
) {
    val context = LocalContext.current
    val rawSources by viewModel.rawSources.collectAsStateWithLifecycle()
    val allReminders by viewModel.allReminders.collectAsStateWithLifecycle()
    var noteSearchQuery by remember { mutableStateOf("") }

    val filteredNotes = remember(rawSources, noteSearchQuery) {
        rawSources.filter { note ->
            noteSearchQuery.isEmpty() || note.content.contains(noteSearchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Slim header row — count on the left, + NEW on the right. No big masthead block;
        // the tab name lives in the top app bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${rawSources.size} ${if (rawSources.size == 1) "draft" else "drafts"}".uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "+ NEW",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .clickable { onCreateNote() }
                    .testTag("create_note_button")
            )
        }

        OutlinedTextField(
            value = noteSearchQuery,
            onValueChange = { noteSearchQuery = it },
            placeholder = { Text("Search drafts inside inbox...", style = MaterialTheme.typography.bodyMedium) },
            prefix = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 8.dp).size(20.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag("notes_search_field"),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            ),
            singleLine = true
        )

        if (filteredNotes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.StickyNote2,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(bottom = 4.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Text(
                        text = if (noteSearchQuery.isEmpty()) "Your inbox is completely empty." else "No drafts match this search.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Manually write some raw sources or load mock seeds.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredNotes, key = { it.id }) { note ->
                    val plainSnippet = stripWikiLinks(note.content)
                    val words = note.content.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    val timeString = formatEpochTime(note.timestamp)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                            .clickable { onEditNote(note) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = "Draft Note #${note.id}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { onEditNote(note) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Draft",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteRawSource(note.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Draft",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            val noteReminders = remember(allReminders, note.id) { allReminders.filter { it.noteId == note.id } }
                            if (noteReminders.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                noteReminders.forEach { reminder ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = when(reminder.category.lowercase()) {
                                                        "event" -> Icons.Default.Event
                                                        "task" -> Icons.Default.TaskAlt
                                                        else -> Icons.Default.Notifications
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        text = reminder.title,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                    Text(
                                                        text = "Schedule: ${reminder.dateText} (${reminder.category})",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                                    )
                                                }
                                            }
                                            Checkbox(
                                                checked = reminder.isCompleted,
                                                onCheckedChange = { isChecked ->
                                                    viewModel.toggleReminder(reminder.id, isChecked)
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = plainSnippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            val noteImages = note.imageList()
                            if (noteImages.isNotEmpty()) {
                                // Show first image as the preview; if more exist, badge with count.
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                ) {
                                    TappableImage(
                                        imagePath = noteImages.first(),
                                        contentDescription = "Note image preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    if (noteImages.size > 1) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "+${noteImages.size - 1}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }

                            if (note.audioPath != null) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Voice Attachment",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        var isLocalPlaying by remember { mutableStateOf(false) }
                                        var localPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
                                        DisposableEffect(note.id) {
                                            onDispose {
                                                localPlayer?.release()
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                if (isLocalPlaying) {
                                                    localPlayer?.pause()
                                                    isLocalPlaying = false
                                                } else {
                                                    try {
                                                        if (localPlayer == null) {
                                                            localPlayer = MediaPlayer().apply {
                                                                setDataSource(note.audioPath)
                                                                prepare()
                                                                setOnCompletionListener {
                                                                    isLocalPlaying = false
                                                                }
                                                            }
                                                        }
                                                        localPlayer?.start()
                                                        isLocalPlaying = true
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        Toast.makeText(context, "Playback error: ${e.localizedMessage ?: "Preparation failed"}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isLocalPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play voice memo",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), thickness = 0.5.dp)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = timeString,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(100.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$words words",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullScreenWysiwygEditor(
    note: RawSource?,
    isNew: Boolean,
    viewModel: WikiViewModel,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ingestState by viewModel.ingestState.collectAsStateWithLifecycle()
    var noteContentState by remember { mutableStateOf(TextFieldValue(note?.content ?: "")) }
    var editorMode by remember { mutableStateOf("Write") }

    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    fun updateContent(newValue: TextFieldValue) {
        if (newValue.text != noteContentState.text) {
            undoStack.add(noteContentState)
            redoStack.clear()
            if (undoStack.size > 25) {
                undoStack.removeAt(0)
            }
        }
        noteContentState = newValue
    }

    fun applyFormat(prefix: String, suffix: String = "") {
        val text = noteContentState.text
        val selection = noteContentState.selection
        val start = selection.start
        val end = selection.end

        val selectedText = text.substring(start, end)
        val newText = text.substring(0, start) + prefix + selectedText + suffix + text.substring(end)

        val newSelectionStart = start + prefix.length
        val newSelectionEnd = if (start == end) {
            newSelectionStart
        } else {
            newSelectionStart + selectedText.length
        }

        val newValue = TextFieldValue(
            text = newText,
            selection = TextRange(newSelectionStart, newSelectionEnd)
        )
        undoStack.add(noteContentState)
        redoStack.clear()
        if (undoStack.size > 25) {
            undoStack.removeAt(0)
        }
        noteContentState = newValue
    }

    var currentAudioPath by remember { mutableStateOf(note?.audioPath) }
    // Multi-image support: list of local file paths attached to the draft.
    val currentImagePaths = remember { mutableStateListOf<String>().apply { addAll(note?.imageList() ?: emptyList()) } }

    fun handleSave() {
        if (isNew) {
            viewModel.createRawSource(noteContentState.text, currentAudioPath, currentImagePaths.toList())
        } else if (note != null) {
            viewModel.saveRawSource(note.id, noteContentState.text, currentAudioPath, currentImagePaths.toList())
        }
        onClose()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (isNew) Icons.Default.NoteAdd else Icons.Default.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isNew) "New Draft" else "Edit Note #${note?.id}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Slim text-only actions — no chunky filled buttons. SAVE = quiet,
                        // COMPILE = underlined emphasis. Keep them tiny so the editor canvas
                        // gets the real estate.
                        val canAct = noteContentState.text.isNotBlank() || currentAudioPath != null || currentImagePaths.isNotEmpty()
                        Text(
                            text = "SAVE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            ),
                            color = if (canAct) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier
                                .clickable(enabled = canAct) { handleSave() }
                                .padding(horizontal = 10.dp, vertical = 12.dp)
                        )
                        Text(
                            text = "COMPILE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            ),
                            color = if (canAct) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier
                                .clickable(enabled = canAct) {
                                    val textToIngest = noteContentState.text
                                    val audioToIngest = currentAudioPath
                                    val imagesToIngest = currentImagePaths.toList()
                                    val draftId = note?.id
                                    viewModel.ingestNote(textToIngest, audioToIngest, imagesToIngest) {
                                        if (!isNew && draftId != null) {
                                            viewModel.deleteRawSource(draftId)
                                        }
                                    }
                                    Toast.makeText(context, "Compiling…", Toast.LENGTH_SHORT).show()
                                    onClose()
                                }
                                .padding(start = 6.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), thickness = 0.5.dp)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Write (Markdown)", "Live WYSIWYG Preview").forEach { modeName ->
                        val mSimple = if (modeName.contains("Write")) "Write" else "Preview"
                        val isSelected = editorMode == mSimple
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { editorMode = mSimple }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = modeName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (editorMode == "Write") {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.FormatBold, contentDescription = "Bold", modifier = Modifier.size(18.dp)) },
                                onClick = { applyFormat("**", "**") }
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.FormatItalic, contentDescription = "Italic", modifier = Modifier.size(18.dp)) },
                                onClick = { applyFormat("*", "*") }
                            )
                        }
                        item {
                            FormatToolItem(
                                content = { Text("H1", fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelSmall) },
                                onClick = { applyFormat("# ") }
                            )
                        }
                        item {
                            FormatToolItem(
                                content = { Text("H2", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) },
                                onClick = { applyFormat("## ") }
                            )
                        }
                        item {
                            FormatToolItem(
                                content = { Text("Highlight", fontWeight = FontWeight.Bold, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall) },
                                onClick = { applyFormat("==", "==") }
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.Link, contentDescription = "Wiki Link", modifier = Modifier.size(18.dp)) },
                                onClick = { applyFormat("[[", "]]") }
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.FormatListBulleted, contentDescription = "Bullet List", modifier = Modifier.size(18.dp)) },
                                onClick = { applyFormat("- ") }
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.Checklist, contentDescription = "Checkbox", modifier = Modifier.size(18.dp)) },
                                onClick = { applyFormat("- [ ] ") }
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.GridOn, contentDescription = "Insert Table", modifier = Modifier.size(18.dp)) },
                                onClick = { applyFormat("\n| Header 1 | Header 2 |\n|---|---|\n| Cell 1 | Cell 2 |\n") }
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    if (undoStack.isNotEmpty()) {
                                        val last = undoStack.removeAt(undoStack.size - 1)
                                        redoStack.add(noteContentState)
                                        noteContentState = last
                                    }
                                },
                                enabled = undoStack.isNotEmpty()
                            )
                        }
                        item {
                            FormatToolItem(
                                icon = { Icon(Icons.Default.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    if (redoStack.isNotEmpty()) {
                                        val next = redoStack.removeAt(redoStack.size - 1)
                                        undoStack.add(noteContentState)
                                        noteContentState = next
                                    }
                                },
                                enabled = redoStack.isNotEmpty()
                            )
                        }
                    }
                }

                // Single 40dp-tall attachments row — audio pill + multi-image picker side by side.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioRecorderPlayerSection(
                        audioPath = currentAudioPath,
                        onAudioRecorded = { currentAudioPath = it },
                        onAudioDeleted = { currentAudioPath = null },
                        modifier = Modifier.weight(1f)
                    )
                    MultiImageAttachmentSection(
                        imagePaths = currentImagePaths,
                        onAddImages = { newPaths -> currentImagePaths.addAll(newPaths) },
                        onRemoveImage = { path -> currentImagePaths.remove(path) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    if (editorMode == "Write") {
                        OutlinedTextField(
                            value = noteContentState,
                            onValueChange = { updateContent(it) },
                            placeholder = { Text("Pour down your ideas here. Write tables, checklists, links, headings...", style = MaterialTheme.typography.bodyMedium) },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("wysiwyg_input_field"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    } else {
                        if (noteContentState.text.isBlank()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Your canvas is currently blank.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                item {
                                    RichWikiRenderer(
                                        content = noteContentState.text,
                                        onLinkClicked = { title ->
                                            viewModel.selectPageByTitle(title)
                                            onClose()
                                        },
                                        onContentChange = { updated ->
                                            noteContentState = TextFieldValue(updated, selection = noteContentState.selection)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val words = noteContentState.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    val chars = noteContentState.text.length
                    Text(
                        text = "Markdown Engine v3.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "$words words  |  $chars chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

        }
    }
}

@Composable
fun RichWikiTable(
    lines: List<String>,
    onLinkClicked: (String) -> Unit
) {
    val cleanRows = lines.map { it.trim() }.filter {
        it.startsWith("|") && !it.contains("---")
    }.map { row ->
        val cells = row.split("|").map { it.trim() }
        val cleanCells = if (cells.size > 2 && cells.first().isEmpty() && cells.last().isEmpty()) {
            cells.subList(1, cells.size - 1)
        } else {
            cells.filter { it.isNotEmpty() }
        }
        cleanCells
    }

    if (cleanRows.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            cleanRows.forEachIndexed { rowIndex, cells ->
                val isHeader = rowIndex == 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isHeader) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else if (rowIndex % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    cells.forEach { cell ->
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isHeader) {
                                Text(
                                    text = cell,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                MarkdownWikiText(
                                    text = cell,
                                    onLinkClicked = onLinkClicked
                                )
                            }
                        }
                    }
                }
                if (rowIndex < cleanRows.size - 1) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), thickness = 0.5.dp)
                }
            }
        }
    }
}

fun formatEpochTime(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}

// ==================== TAB 2: EXPLORE CHAT ====================

@Composable
fun QueryScreen(viewModel: WikiViewModel) {
    val queryState by viewModel.queryState.collectAsStateWithLifecycle()
    var questionInput by remember { mutableStateOf("") }
    val pages by viewModel.wikiPages.collectAsStateWithLifecycle()

    val suggestedQuestions = listOf(
        "Where does Charlie study?",
        "What concepts connect Brooklyn?",
        "Do we have any neurology notes?"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pages.size} ${if (pages.size == 1) "page" else "pages"} indexed".uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Suggestion Chips
        LazyRow(
            modifier = Modifier.padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(suggestedQuestions) { prompt ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .clickable {
                            questionInput = prompt
                            viewModel.queryWiki(prompt)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        OutlinedTextField(
            value = questionInput,
            onValueChange = { questionInput = it },
            placeholder = { Text("What would you like to know about your Wiki?") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
        )

        Button(
            onClick = { viewModel.queryWiki(questionInput) },
            enabled = questionInput.isNotBlank() && queryState !is QueryState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("chat_query_button")
        ) {
            if (queryState is QueryState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.background,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Analyze Wiki Knowledge")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Chat Result Display
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    when (queryState) {
                        is QueryState.Success -> {
                            val answer = (queryState as QueryState.Success).answer
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Hub,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "WIKISCOUT SYNTHESIS",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                RichWikiRenderer(
                                    content = answer,
                                    onLinkClicked = { title -> viewModel.selectPageByTitle(title) }
                                )
                            }
                        }
                        is QueryState.Error -> {
                            val err = (queryState as QueryState.Error).message
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        is QueryState.Loading -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Consulting and synthesizing wiki records...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    "No Active Query",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Ask a question above or select on suggestion chips to run queries.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 3: DIAGNOSTICS LINT ====================

@Composable
fun LintScreen(viewModel: WikiViewModel) {
    val pages by viewModel.wikiPages.collectAsStateWithLifecycle()
    val lintState by viewModel.lintState.collectAsStateWithLifecycle()

    var totalLinks = 0
    pages.forEach { p ->
        totalLinks += Regex("\\[\\[(.*?)\\]\\]").findAll(p.content).count()
    }
    val density = if (pages.isNotEmpty()) (totalLinks.toFloat() / pages.size) else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$totalLinks ${if (totalLinks == 1) "link" else "links"} · ${pages.size} ${if (pages.size == 1) "page" else "pages"}".uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Metrics Card Group
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Compiled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${pages.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text("Pages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Relations", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$totalLinks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
                    Text("Links", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Density", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.1f", density), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.tertiary)
                    Text("Links/Page", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Two actions: AUDIT runs the lint pass, FIX applies the most recent audit's suggestions.
        // FIX is only enabled when there's a successful report with issues.
        val agentActivityNow by viewModel.agentActivity.collectAsStateWithLifecycle()
        val hasIssues = (lintState as? LintState.Success)?.report?.issues?.isNotEmpty() == true
        val isWorking = agentActivityNow.isActive || lintState is LintState.Loading

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.runLint() },
                enabled = !isWorking,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("lint_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Audit", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { viewModel.applyLintFixes() },
                enabled = !isWorking && hasIssues,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("fix_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fix", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnostic Issues Report
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
            when (lintState) {
                is LintState.Success -> {
                    val report = (lintState as LintState.Success).report
                    if (report.issues.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Database 100% Healthy", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                            Text("No structural conflicts, broken references or orphan links found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Text(
                                    "DIAGNOSTIC ALERTS (${report.issues.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            items(report.issues) { issue ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val badgeColor = when (issue.severity) {
                                                    "high" -> SoftNeonRed
                                                    "medium" -> SoftNeonYellow
                                                    else -> NeonCyan
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(badgeColor)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = issue.summary.orEmpty(),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(issue.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Suggested: ${issue.suggestedAction.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is LintState.Loading -> {
                    // Live streaming thinking/output — markdown-rendered, scroll-as-it-grows.
                    AgentStreamingPanel(activity = agentActivityNow, fallback = "Auditing…")
                }
                is LintState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failing audit scan: ${(lintState as LintState.Error).message}", color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    // Idle state — if the agent is mid-fix (post-audit), show the live stream.
                    if (agentActivityNow.isActive) {
                        AgentStreamingPanel(activity = agentActivityNow, fallback = "Fixing…")
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                        ) {
                            Icon(Icons.Outlined.Analytics, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Text("No Audit Staged", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Initiate audit diagnostics to check database coherence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStreamingPanel(activity: AgentActivity, fallback: String) {
    val scroll = rememberScrollState()
    // Auto-scroll to the bottom as new text streams in.
    androidx.compose.runtime.LaunchedEffect(activity.thinking.length, activity.answer.length) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Text(
                text = "${activity.operation.ifBlank { fallback }} · ${activity.elapsedSec}s",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (activity.thinking.isNotBlank()) {
            Text(
                "THINKING",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.secondary
            )
            // Markdown render of the thinking text — supports [[links]], headings, lists.
            RichWikiRenderer(
                content = activity.thinking,
                onLinkClicked = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (activity.answer.isNotBlank()) {
            // Don't dump the raw JSON — once the model is in answer-mode the structured result UI
            // will take over after streaming finishes. Just acknowledge it's compiling.
            Text(
                "Compiling…",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        if (activity.thinking.isBlank() && activity.answer.isBlank()) {
            Text(
                text = "Waiting for the model to start streaming…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== TAB 3: DAILY SCHEDULE & EVENTS SCREEN ====================

@Composable
fun EventsScreen(viewModel: WikiViewModel) {
    val allReminders by viewModel.allReminders.collectAsStateWithLifecycle()
    var filterMode by remember { mutableStateOf("Pending") } // "All", "Pending", "Completed"
    // Tap a reminder card to edit it. Holds the currently-being-edited reminder; null = dialog closed.
    var editingReminder by remember { mutableStateOf<Reminder?>(null) }

    val displayedReminders = remember(allReminders, filterMode) {
        when (filterMode) {
            "Pending" -> allReminders.filter { !it.isCompleted }
            "Completed" -> allReminders.filter { it.isCompleted }
            else -> allReminders
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${displayedReminders.size} ${if (displayedReminders.size == 1) "item" else "items"}".uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("Pending", "Completed", "All").forEach { mode ->
                    val active = filterMode == mode
                    Text(
                        text = mode.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            textDecoration = if (active) androidx.compose.ui.text.style.TextDecoration.Underline else null
                        ),
                        color = if (active) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { filterMode = mode }
                    )
                }
            }
        }

        // List container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
        ) {
            if (displayedReminders.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (filterMode == "Completed") Icons.Default.CheckCircle else Icons.Default.EventNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (filterMode) {
                            "Completed" -> "No completed events yet."
                            "Pending" -> "All caught up! No pending events."
                            else -> "Your calendar is clean of events."
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Compile sources or audio notes with dates or tasks to auto-extract agenda points.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayedReminders) { rem ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingReminder = rem },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (rem.isCompleted) MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Checkbox(
                                            checked = rem.isCompleted,
                                            onCheckedChange = { isChecked ->
                                                viewModel.toggleReminder(rem.id, isChecked)
                                            }
                                        )
                                        Column {
                                            Text(
                                                text = rem.title,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    textDecoration = if (rem.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                                ),
                                                color = if (rem.isCompleted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = rem.category,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Text(
                                                    text = "📅 " + rem.dateText,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    IconButton(onClick = { viewModel.deleteReminder(rem.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Reminder",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                if (rem.description.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = rem.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (rem.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 36.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit dialog — opens when a reminder card is tapped. Saving overwrites the row by id
    // (Reminder DAO uses REPLACE strategy on the primary key).
    editingReminder?.let { rem ->
        EditReminderDialog(
            initial = rem,
            onDismiss = { editingReminder = null },
            onSave = { updated ->
                viewModel.updateReminder(updated)
                editingReminder = null
            }
        )
    }
}

@Composable
fun EditReminderDialog(
    initial: Reminder,
    onDismiss: () -> Unit,
    onSave: (Reminder) -> Unit
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var dateText by remember(initial.id) { mutableStateOf(initial.dateText) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Edit ${initial.category.lowercase().replaceFirstChar { it.uppercase() }}",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Category as a row of pill toggles — tight, no dropdown needed for 3 options.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Reminder", "Task", "Event").forEach { c ->
                        val selected = category == c
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable { category = c }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = c,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("When") },
                    placeholder = { Text("Tomorrow / Friday 9am / 2026-05-23") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        title = title.trim().ifBlank { initial.title },
                        description = description.trim(),
                        dateText = dateText.trim().ifBlank { initial.dateText },
                        category = category
                    )
                )
            }) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ==================== WIKI DETAILS MODAL OVERLAY ====================

@Composable
fun PageDetailView(
    page: WikiPage,
    allPages: List<WikiPage>,
    onClose: () -> Unit,
    onLinkClicked: (String) -> Unit
) {
    // Compute Inbound Backlinks targeting this page title
    val backlinks = remember(page, allPages) {
        allPages.filter { otherPage ->
            otherPage.title != page.title &&
                otherPage.content.contains("[[${page.title}]]", ignoreCase = true)
        }
    }

    // Title is already shown in the top app bar; system back gesture / button closes the page.
    // No redundant in-screen back button or duplicate title.
    androidx.activity.compose.BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        // Content lives directly on the surface — no wrapping card. Cards inside cards
        // are visual noise. Backlinks below get their own divider for rhythm.
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
                // Tags chips
                if (page.tags.isNotBlank()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            page.tags.split(",").map { it.trim() }.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Markdown Content Renderer
                item {
                    RichWikiRenderer(
                        content = page.content,
                        onLinkClicked = onLinkClicked,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Divider line
                item {
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                // Backreferences Bi-directional links Panel
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "BACKLINKS (${backlinks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (backlinks.isEmpty()) {
                            Text(
                                "No active inbound pathways trace back to this node yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            backlinks.forEach { backPage ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onLinkClicked(backPage.title) }
                                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                                ) {
                                    Text(
                                        text = backPage.title,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

// ==================== ASTRO GRAPH DRAWING CANVAS ====================

@Composable
fun WikiRelationshipGraph(
    pages: List<WikiPage>,
    onNodeSelected: (WikiPage) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "No compiled nodes found. Ingest or seed resources to populate graph connections.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    // Nodes: Unique list of pages
    val nodes = pages.map { it.title }

    // Edges: Bi-directional link relationships
    val edges = remember(pages) {
        val list = mutableListOf<Pair<String, String>>()
        pages.forEach { p ->
            val regex = Regex("\\[\\[(.*?)\\]\\]")
            regex.findAll(p.content).forEach { match ->
                val targetTitle = match.groupValues[1].trim()
                if (nodes.contains(targetTitle)) {
                    list.add(Pair(p.title, targetTitle))
                }
            }
        }
        list
    }

    val graphTransition = rememberInfiniteTransition(label = "graph_pulse")
    val heartbeat by graphTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartbeat"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        // Distribute coordinates in a stable seeded circular path to preserve consistent spatial representations
        val nodePositions = remember(pages, width, height) {
            val positions = mutableMapOf<String, Offset>()
            val radius = minOf(centerX, centerY) * 0.62f
            pages.forEachIndexed { index, page ->
                val angle = (2f * Math.PI * index / pages.size).toFloat()
                val x = centerX + radius * kotlin.math.cos(angle)
                val y = centerY + radius * kotlin.math.sin(angle)
                positions[page.title] = Offset(x, y)
            }
            positions
        }

        val primaryColor = MaterialTheme.colorScheme.primary
        val secondaryColor = MaterialTheme.colorScheme.secondary
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pages, nodePositions) {
                    detectTapGestures { tapOffset ->
                        val radiusPx = 80f // tap tolerance threshold
                        val clickedEntry = nodePositions.entries.find { entry ->
                            val distance = (entry.value - tapOffset).getDistance()
                            distance <= radiusPx
                        }
                        clickedEntry?.let { entry ->
                            pages.find { it.title == entry.key }?.let { matchedPage ->
                                onNodeSelected(matchedPage)
                            }
                        }
                    }
                }
        ) {
            // Draw background celestial concentric guide rings representing semantic spaces
            val maxRadius = minOf(centerX, centerY)
            drawCircle(
                color = primaryColor.copy(alpha = 0.05f),
                radius = maxRadius * 0.35f,
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 15f), 0f)
                )
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.05f),
                radius = maxRadius * 0.62f,
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 15f), 0f)
                )
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.05f),
                radius = maxRadius * 0.9f,
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 15f), 0f)
                )
            )

            // Draw pathway edges (Dashed links)
            edges.forEach { edge ->
                val start = nodePositions[edge.first]
                val end = nodePositions[edge.second]
                if (start != null && end != null) {
                    drawLine(
                        color = primaryColor.copy(alpha = 0.35f),
                        start = start,
                        end = end,
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                    )
                }
            }

            // Draw compiled node orbits
            nodePositions.forEach { (_, offset) ->
                // Orbit aura glow with dynamic heartbeat
                drawCircle(
                    color = primaryColor.copy(alpha = 0.12f * heartbeat),
                    radius = 45f * heartbeat,
                    center = offset
                )
                // Outer ring
                drawCircle(
                    color = secondaryColor.copy(alpha = 0.6f),
                    radius = 22f,
                    center = offset
                )
                // Center core pin
                drawCircle(
                    color = primaryColor,
                    radius = 11f,
                    center = offset
                )
            }
        }

        // Draw node title labels as overlays
        nodePositions.forEach { (title, offset) ->
            val densityPx = LocalDensity.current
            val xDp = with(densityPx) { offset.x.toDp() }
            val yDp = with(densityPx) { offset.y.toDp() }

            Box(
                modifier = Modifier
                    .offset(xDp - 60.dp, yDp + 14.dp)
                    .width(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ==================== RENDERING PARSER HELPERS ====================

fun parseInlineMarkdownToAnnotatedString(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    val len = text.length

    while (i < len) {
        // 1. Double bracket links: [[Page Title]] or [[Page Title|Display Name]]
        if (i + 1 < len && text[i] == '[' && text[i + 1] == '[') {
            val closeIndex = text.indexOf("]]", i + 2)
            if (closeIndex != -1) {
                val rawLink = text.substring(i + 2, closeIndex)
                val parts = rawLink.split('|')
                val linkTarget = parts[0].trim()
                val display = if (parts.size > 1) parts[1].trim() else linkTarget

                builder.pushStringAnnotation(tag = "WIKI_LINK", annotation = linkTarget)
                builder.pushStyle(
                    SpanStyle(
                        color = Color(0xFF00E6FF),
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline
                    )
                )
                builder.append(display)
                builder.pop()
                builder.pop()
                i = closeIndex + 2
                continue
            }
        }

        // 1.5. Standard links: [Display Text](URL or link)
        if (text[i] == '[') {
            val closeBracketIndex = text.indexOf(']', i + 1)
            if (closeBracketIndex != -1 && closeBracketIndex + 1 < len && text[closeBracketIndex + 1] == '(') {
                val closeParenIndex = text.indexOf(')', closeBracketIndex + 2)
                if (closeParenIndex != -1) {
                    val linkText = text.substring(i + 1, closeBracketIndex)
                    val linkUrl = text.substring(closeBracketIndex + 2, closeParenIndex).trim()

                    if (linkUrl.startsWith("http://") || linkUrl.startsWith("https://")) {
                        builder.pushStringAnnotation(tag = "URL_LINK", annotation = linkUrl)
                    } else {
                        builder.pushStringAnnotation(tag = "WIKI_LINK", annotation = linkUrl)
                    }
                    builder.pushStyle(
                        SpanStyle(
                            color = Color(0xFF00E6FF),
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                    builder.append(linkText)
                    builder.pop()
                    builder.pop()
                    i = closeParenIndex + 1
                    continue
                }
            }
        }

        // 2. Inline Code: `code`
        if (text[i] == '`') {
            val closeIndex = text.indexOf('`', i + 1)
            if (closeIndex != -1) {
                val codeText = text.substring(i + 1, closeIndex)
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.LightGray.copy(alpha = 0.2f),
                        color = Color(0xFFFF7B72)
                    )
                )
                builder.append(codeText)
                builder.pop()
                i = closeIndex + 1
                continue
            }
        }

        // 3. Bold-Italic: ***text***
        if (i + 2 < len && text[i] == '*' && text[i + 1] == '*' && text[i + 2] == '*') {
            val closeIndex = text.indexOf("***", i + 3)
            if (closeIndex != -1) {
                val subContent = text.substring(i + 3, closeIndex)
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                builder.append(parseInlineMarkdownToAnnotatedString(subContent))
                builder.pop()
                i = closeIndex + 3
                continue
            }
        }

        // 4. Bold: **text** or __text__
        if (i + 1 < len && (
            (text[i] == '*' && text[i + 1] == '*') ||
            (text[i] == '_' && text[i + 1] == '_')
        )) {
            val marker = text.substring(i, i + 2)
            val closeIndex = text.indexOf(marker, i + 2)
            if (closeIndex != -1) {
                val boldContent = text.substring(i + 2, closeIndex)
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(parseInlineMarkdownToAnnotatedString(boldContent))
                builder.pop()
                i = closeIndex + 2
                continue
            }
        }

        // 5. Italic: *text* or _text_
        if (text[i] == '*' || text[i] == '_') {
            val marker = text[i].toString()
            val closeIndex = text.indexOf(marker, i + 1)
            if (closeIndex != -1) {
                val italicContent = text.substring(i + 1, closeIndex)
                builder.pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                builder.append(parseInlineMarkdownToAnnotatedString(italicContent))
                builder.pop()
                i = closeIndex + 1
                continue
            }
        }

        // 6. Highlight: ==text==
        if (i + 1 < len && text[i] == '=' && text[i + 1] == '=') {
            val closeIndex = text.indexOf("==", i + 2)
            if (closeIndex != -1) {
                val highlightContent = text.substring(i + 2, closeIndex)
                builder.pushStyle(SpanStyle(background = Color(0xFFFFEB3B).copy(alpha = 0.4f), color = Color.Black))
                builder.append(parseInlineMarkdownToAnnotatedString(highlightContent))
                builder.pop()
                i = closeIndex + 2
                continue
            }
        }

        // 7. Strikethrough: ~~text~~
        if (i + 1 < len && text[i] == '~' && text[i + 1] == '~') {
            val closeIndex = text.indexOf("~~", i + 2)
            if (closeIndex != -1) {
                val strikethroughContent = text.substring(i + 2, closeIndex)
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                builder.append(parseInlineMarkdownToAnnotatedString(strikethroughContent))
                builder.pop()
                i = closeIndex + 2
                continue
            }
        }

        // Standard fallback text
        builder.append(text[i].toString())
        i++
    }
    return builder.toAnnotatedString()
}

@Composable
fun MarkdownWikiText(
    text: String,
    onLinkClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
) {
    val context = LocalContext.current
    val annotatedString = remember(text) {
        parseInlineMarkdownToAnnotatedString(text)
    }

    ClickableText(
        text = annotatedString,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "WIKI_LINK", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    onLinkClicked(annotation.item)
                }
            annotatedString.getStringAnnotations(tag = "URL_LINK", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(annotation.item))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        },
        style = style,
        modifier = modifier
    )
}

@Composable
fun RichWikiRenderer(
    content: String,
    onLinkClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    onContentChange: ((String) -> Unit)? = null
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val lines = content.split("\n")
        var inTable = false
        val tableLines = mutableListOf<String>()

        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("|")) {
                if (!inTable) {
                    inTable = true
                    tableLines.clear()
                }
                tableLines.add(line)
            } else {
                if (inTable) {
                    inTable = false
                    // Render accumulated table lines
                    RichWikiTable(lines = tableLines.toList(), onLinkClicked = onLinkClicked)
                    tableLines.clear()
                }

                when {
                    trimmed.startsWith("![") && trimmed.contains("](") && trimmed.endsWith(")") -> {
                        val startIndex = trimmed.indexOf("](") + 2
                        val endIndex = trimmed.length - 1
                        val imgPath = trimmed.substring(startIndex, endIndex).trim()
                        val altText = trimmed.substring(2, trimmed.indexOf("]("))
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                TappableImage(
                                    imagePath = imgPath,
                                    contentDescription = altText.ifEmpty { "Wiki Image" },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                if (altText.isNotBlank()) {
                                    Text(
                                        text = altText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    trimmed.startsWith("# ") -> {
                        MarkdownWikiText(
                            text = trimmed.removePrefix("# "),
                            onLinkClicked = onLinkClicked,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    trimmed.startsWith("## ") -> {
                        MarkdownWikiText(
                            text = trimmed.removePrefix("## "),
                            onLinkClicked = onLinkClicked,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    trimmed.startsWith("### ") -> {
                        MarkdownWikiText(
                            text = trimmed.removePrefix("### "),
                            onLinkClicked = onLinkClicked,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    (trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")) -> {
                        val isChecked = trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")
                        val textPart = trimmed.substring(5).trim()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (onContentChange != null) {
                                        val updatedLines = lines.toMutableList()
                                        val originalLine = updatedLines[lineIndex]
                                        val newLine = if (checked) {
                                            originalLine.replaceFirst("- [ ]", "- [x]")
                                        } else {
                                            originalLine.replaceFirst("- [x]", "- [ ]").replaceFirst("- [X]", "- [ ]")
                                        }
                                        updatedLines[lineIndex] = newLine
                                        onContentChange(updatedLines.joinToString("\n"))
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            MarkdownWikiText(
                                text = textPart,
                                onLinkClicked = onLinkClicked,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                        Row(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "• ",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            MarkdownWikiText(
                                text = trimmed.substring(2),
                                onLinkClicked = onLinkClicked,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    trimmed.startsWith("> ") -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(24.dp)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                MarkdownWikiText(
                                    text = trimmed.removePrefix("> ").trim(),
                                    onLinkClicked = onLinkClicked,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    trimmed.isEmpty() -> {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    else -> {
                        MarkdownWikiText(
                            text = line,
                            onLinkClicked = onLinkClicked
                        )
                    }
                }
            }
        }
        if (inTable) {
            RichWikiTable(lines = tableLines.toList(), onLinkClicked = onLinkClicked)
            tableLines.clear()
        }
    }
}

private fun stripWikiLinks(text: String): String {
    return text.replace("[[", "").replace("]]", "")
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun UserProfileSettingsScreen(
    viewModel: WikiViewModel,
    onClose: () -> Unit,
    importLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val userBio by viewModel.userBio.collectAsStateWithLifecycle()

    var nameInput by remember { mutableStateOf(userName) }
    var bioInput by remember { mutableStateOf(userBio) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val initials = remember(nameInput) {
        val parts = nameInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        when {
            parts.isEmpty() -> "??"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Clear All Data") },
            text = { Text("Are you absolutely sure you want to completely erase your personal wiki notes and database? This action is irreversible.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearWiki()
                        showDeleteConfirm = false
                        Toast.makeText(context, "Wiki completely cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "Profile & Workspace",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Box(modifier = Modifier.size(48.dp)) // spacer balance
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile Avatar Card
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        )
                                    )
                                )
                                .border(2.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Serif
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = userBio,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                // Profile Editor Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Personal Identity",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Display Name") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = bioInput,
                                onValueChange = { bioInput = it },
                                label = { Text("Short Bio / Tagline") },
                                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    if (nameInput.trim().isEmpty()) {
                                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.updateProfile(nameInput.trim(), bioInput.trim())
                                        Toast.makeText(context, "Profile settings updated successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Update Identity")
                            }
                        }
                    }
                }

                // Bring Your Own Gemini API Key Section
                item {
                    val currentApiKey by viewModel.apiKey.collectAsStateWithLifecycle()
                    var apiKeyInput by remember { mutableStateOf(currentApiKey) }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Gemini API Credentials (BYO)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Provide your personal Gemini API key. If left blank, the application will default to system-injected capabilities.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("Gemini API Key") },
                                placeholder = { Text("AIzaSy...") },
                                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("api_key_field")
                            )
                            Button(
                                onClick = {
                                    viewModel.updateApiKey(apiKeyInput)
                                    Toast.makeText(context, "API Key updated successfully!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save Credentials")
                            }
                        }
                    }
                }

                // Theme picker — Default (B&W), Adaptive (Material You), Editorial (warm dark).
                item {
                    val currentMode by viewModel.appThemeMode.collectAsStateWithLifecycle()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Default is neutral black-and-white. Adaptive uses your Android wallpaper colours (Material You). Editorial is a warm-dark parchment palette.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Segmented row of three pills.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AppThemeMode.values().forEach { mode ->
                                    val active = currentMode == mode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (active) MaterialTheme.colorScheme.primary
                                                else Color.Transparent
                                            )
                                            .clickable { viewModel.setAppThemeMode(mode) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = when (mode) {
                                                AppThemeMode.DEFAULT -> "Default"
                                                AppThemeMode.ADAPTIVE -> "Adaptive"
                                                AppThemeMode.EDITORIAL -> "Editorial"
                                            },
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (active) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Model Selection Section — populated live from the Gemini models.list API.
                // No model name is hardcoded; user must fetch the list and pick.
                item {
                    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
                    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
                    val modelListState by viewModel.modelListState.collectAsStateWithLifecycle()
                    var expanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Active Gemini Model",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Fetch the live list of models your API key can access, then pick one. Cheaper/lighter models cost less per request.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = { viewModel.refreshAvailableModels() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    when (modelListState) {
                                        is ModelListState.Loading -> "Fetching..."
                                        is ModelListState.Success -> "Refresh Model List"
                                        else -> "Fetch Available Models"
                                    }
                                )
                            }

                            when (val s = modelListState) {
                                is ModelListState.Error -> {
                                    Text(
                                        text = "Failed: ${s.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                else -> {}
                            }

                            if (availableModels.isNotEmpty()) {
                                ExposedDropdownMenuBox(
                                    expanded = expanded,
                                    onExpandedChange = { expanded = !expanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedModel.ifBlank { "— pick a model —" },
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Model") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        availableModels.forEach { m ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(m.id, fontWeight = FontWeight.Bold)
                                                        if (!m.displayName.isNullOrBlank()) {
                                                            Text(
                                                                m.displayName,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.setSelectedModel(m.id)
                                                    expanded = false
                                                    Toast.makeText(context, "Active model: ${m.id}", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                            } else if (selectedModel.isBlank()) {
                                Text(
                                    text = "No model selected yet. Tap fetch above, then pick one.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            } else {
                                Text(
                                    text = "Currently: $selectedModel (tap fetch to change)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Data Utilities Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Backup, Recovery & Seed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Maintain copy of your wiki as a compressed backup ZIP or reload sample data directories.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.exportWikiToZip(context) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Export Backup", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { importLauncher.launch("application/zip") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import Backup", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Profile & Activity Ledger Section
                item {
                    val logs by viewModel.activityLogs.collectAsStateWithLifecycle()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "My Activity Logs & Audit Trail",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Chronological system timeline detailing compilation actions, contradictions flagged, and cross-links generated.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No activities logged yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    logs.take(15).forEach { log ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            val iconType = when (log.type) {
                                                "ingest" -> Icons.Default.CloudDownload
                                                "query" -> Icons.Default.Search
                                                "lint" -> Icons.Default.HealthAndSafety
                                                else -> Icons.Default.Build
                                             }
                                            val badgeColor = when (log.type) {
                                                "ingest" -> MaterialTheme.colorScheme.primary
                                                "query" -> MaterialTheme.colorScheme.secondary
                                                "lint" -> SoftNeonYellow
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(badgeColor.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(iconType, contentDescription = null, tint = badgeColor, modifier = Modifier.size(14.dp))
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = log.summary,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = log.detail,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    if (logs.size > 15) {
                                        Text(
                                            text = "and ${logs.size - 15} more activities",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Danger Zone Section
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Danger Zone",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Clear Entire Wiki Database")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioRecorderPlayerSection(
    audioPath: String?,
    onAudioRecorded: (String) -> Unit,
    onAudioDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableStateOf(0) }
    var mediaRecorder: MediaRecorder? by remember { mutableStateOf(null) }
    var recordingFile: File? by remember { mutableStateOf(null) }

    // Player state
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer: MediaPlayer? by remember { mutableStateOf(null) }
    var playPositionSec by remember { mutableStateOf(0) }
    var playDurationSec by remember { mutableStateOf(0) }

    // Start timer for recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSec = 0
            while (isRecording) {
                delay(1000)
                recordingDurationSec++
            }
        }
    }

    // Start timer/track for playing
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        playPositionSec = player.currentPosition / 1000
                    } else {
                        isPlaying = false
                    }
                }
                delay(250)
            }
        }
    }

    // Permission state
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required to record voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    // Clean up media assets when component leaves screen
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaRecorder?.release()
        }
    }

    // Helper: start recording. Inlined as a lambda so the body stays flat.
    val startRecording = startRec@{
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@startRec
        }
        try {
            val file = File(context.filesDir, "audio_${System.currentTimeMillis()}.m4a")
            recordingFile = file
            val recorder = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }
            } catch (t: Throwable) {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            mediaRecorder = recorder.apply {
                try { setAudioSource(MediaRecorder.AudioSource.MIC) }
                catch (micEx: Exception) { setAudioSource(MediaRecorder.AudioSource.DEFAULT) }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Microphone error: ${e.localizedMessage ?: "Device access failed"}", Toast.LENGTH_LONG).show()
        }
    }

    val stopRecording: () -> Unit = {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            recordingFile?.let { onAudioRecorded(it.absolutePath) }
        } catch (e: Exception) {
            mediaRecorder = null
            isRecording = false
        }
    }

    val togglePlayback: () -> Unit = {
        if (audioPath != null) {
            if (isPlaying) {
                mediaPlayer?.pause()
                isPlaying = false
            } else {
                try {
                    if (mediaPlayer == null) {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(audioPath)
                            prepare()
                            playDurationSec = duration / 1000
                            setOnCompletionListener { isPlaying = false; playPositionSec = 0 }
                        }
                    }
                    mediaPlayer?.start()
                    isPlaying = true
                } catch (e: Exception) {
                    Toast.makeText(context, "Playback error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val deleteAudio: () -> Unit = {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        onAudioDeleted()
    }

    // Compact pill — single 40dp-tall row that fits in a side-by-side row with the image pill.
    // States: idle (tap to record) → recording (red dot + timer + stop) → recorded (play/pause + duration + ✕).
    val pillShape = RoundedCornerShape(20.dp)
    val pillModifier = modifier
        .height(40.dp)
        .clip(pillShape)
        .background(
            when {
                isRecording -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                audioPath != null -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
        .border(
            1.dp,
            when {
                isRecording -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                audioPath != null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            },
            pillShape
        )

    when {
        isRecording -> {
            Row(
                modifier = pillModifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Text(
                    text = String.format("%02d:%02d", recordingDurationSec / 60, recordingDurationSec % 60),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = stopRecording, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop recording",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        audioPath != null -> {
            Row(
                modifier = pillModifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = togglePlayback, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = if (playDurationSec > 0)
                        String.format("%02d:%02d / %02d:%02d", playPositionSec / 60, playPositionSec % 60, playDurationSec / 60, playDurationSec % 60)
                    else "Audio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { deleteAudio() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove audio",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        else -> {
            Row(
                modifier = pillModifier
                    .clickable { startRecording() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Record",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ImageAttachmentSection(
    imagePath: String?,
    onImageSelected: (String) -> Unit,
    onImageDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val file = copyUriToLocalFile(context, it)
            if (file != null) onImageSelected(file.absolutePath)
            else Toast.makeText(context, "Failed to copy image", Toast.LENGTH_SHORT).show()
        }
    }

    val pillShape = RoundedCornerShape(20.dp)
    val pillModifier = modifier
        .height(40.dp)
        .clip(pillShape)
        .background(
            if (imagePath != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
        .border(
            1.dp,
            if (imagePath != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            pillShape
        )

    if (imagePath == null) {
        Row(
            modifier = pillModifier
                .clickable { imageLauncher.launch("image/*") }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Image",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Row(
            modifier = pillModifier.padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Inline thumbnail. Tap opens fullscreen via TappableImage.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                TappableImage(
                    imagePath = imagePath,
                    contentDescription = "Attachment preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            Text(
                text = java.io.File(imagePath).name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onImageDeleted, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove image",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun MultiImageAttachmentSection(
    imagePaths: List<String>,
    onAddImages: (List<String>) -> Unit,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // GetMultipleContents lets the user pick more than one image in a single picker session.
    val launcher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val copied = uris.mapNotNull { copyUriToLocalFile(context, it)?.absolutePath }
        if (copied.isNotEmpty()) onAddImages(copied)
    }

    val pillShape = RoundedCornerShape(20.dp)
    val pillModifier = modifier
        .height(40.dp)
        .clip(pillShape)
        .background(
            if (imagePaths.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
        .border(
            1.dp,
            if (imagePaths.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            pillShape
        )

    if (imagePaths.isEmpty()) {
        Row(
            modifier = pillModifier
                .clickable { launcher.launch("image/*") }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Images",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        // Horizontal scroll of 32dp thumbnails inside the pill, each with an X badge. A + tile at
        // the right adds more. Whole row stays 40dp tall regardless of count.
        LazyRow(
            modifier = pillModifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(imagePaths) { path ->
                Box(modifier = Modifier.size(32.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        TappableImage(
                            imagePath = path,
                            contentDescription = "Attached image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    // X badge at top-right
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(14.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .clickable { onRemoveImage(path) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }
            // Add-more tile
            item {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add image",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Support function to copy URI content into application files directory
private fun copyUriToLocalFile(context: Context, uri: android.net.Uri): java.io.File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val extension = context.contentResolver.getType(uri)?.split("/")?.lastOrNull() ?: "jpg"
        val outputFile = java.io.File(context.filesDir, "image_${System.currentTimeMillis()}.$extension")
        outputFile.outputStream().use { outputStream ->
            inputStream.use { it.copyTo(outputStream) }
        }
        outputFile
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// ==================== ONBOARDING (multi-page pager, fully skippable) ====================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: WikiViewModel, onDone: () -> Unit) {
    val pageCount = 5
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    // Per-page background gradient palette — drifts warm to cool across the flow.
    val gradientPairs = listOf(
        Color(0xFF1A1320) to Color(0xFF2A1F38),  // hero — deep plum
        Color(0xFF12181F) to Color(0xFF1E2935),  // capture — slate
        Color(0xFF161D1A) to Color(0xFF22302C),  // graph — moss
        Color(0xFF1B1810) to Color(0xFF2E281A),  // key — warm umber
        Color(0xFF101820) to Color(0xFF1F2B3A)   // model — twilight
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Animated background — colour interpolates between page gradients as the user swipes.
            val pageOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val nextIdx = (pagerState.currentPage + 1).coerceAtMost(pageCount - 1)
            val t = pagerState.currentPageOffsetFraction.coerceIn(-1f, 1f).let {
                if (it >= 0f) it else 0f
            }
            val topColor = androidx.compose.ui.graphics.lerp(
                gradientPairs[pagerState.currentPage].first,
                gradientPairs[nextIdx].first,
                t
            )
            val bottomColor = androidx.compose.ui.graphics.lerp(
                gradientPairs[pagerState.currentPage].second,
                gradientPairs[nextIdx].second,
                t
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top bar: page dots + Skip on the right. Skip jumps to the last page so the user
                // still lands on Get Started; they can also leave key/model blank.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pageCount) { idx ->
                            val active = idx == pagerState.currentPage
                            val width = if (active) 22.dp else 6.dp
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(width)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (active) Color.White.copy(alpha = 0.9f)
                                        else Color.White.copy(alpha = 0.2f)
                                    )
                            )
                        }
                    }
                    if (pagerState.currentPage < pageCount - 1) {
                        TextButton(onClick = onDone) {
                            Text(
                                "Skip",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> OnboardHeroPage()
                        1 -> OnboardCapturePage()
                        2 -> OnboardGraphPage()
                        3 -> OnboardApiKeyPage(viewModel)
                        4 -> OnboardModelPage(viewModel)
                    }
                }

                // Bottom action area — Back + Next/Get Started. Always present so the user can
                // navigate without swiping. Last page replaces Next with Get Started.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Text("Back", color = Color.White.copy(alpha = 0.85f))
                        }
                    }

                    val isLast = pagerState.currentPage == pageCount - 1
                    Button(
                        onClick = {
                            if (isLast) onDone()
                            else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(
                            text = if (isLast) "Start using your wiki" else "Next",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageScaffold(
    eyebrow: String,
    title: String,
    body: String,
    visual: @Composable BoxScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            visual()
        }
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = Color.White.copy(alpha = 0.5f)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp
            ),
            color = Color.White
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun OnboardHeroPage() {
    OnboardingPageScaffold(
        eyebrow = "PERSONAL WIKI",
        title = "A wiki that\nthinks with you.",
        body = "Drop a thought, a voice memo, a photo, or a URL. It compiles them into interlinked pages you can search, link, and chat with.",
        visual = {
            // Abstract concentric arcs evoking a knowledge graph nucleus.
            Canvas(modifier = Modifier.size(220.dp)) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                val baseColor = Color.White
                repeat(5) { i ->
                    drawCircle(
                        color = baseColor.copy(alpha = 0.05f + i * 0.04f),
                        radius = (size.minDimension / 2) - (i * 18f),
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                    )
                }
                drawCircle(
                    color = baseColor.copy(alpha = 0.95f),
                    radius = 8f,
                    center = center
                )
            }
        }
    )
}

@Composable
private fun OnboardCapturePage() {
    OnboardingPageScaffold(
        eyebrow = "CAPTURE ANYTHING",
        title = "Text, voice,\nimages, URLs.",
        body = "Type a quick note, record a memo, attach multiple photos, paste a link. The agent reads them all and updates the right pages.",
        visual = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(
                    Icons.Default.Edit to "Text",
                    Icons.Default.Mic to "Voice",
                    Icons.Default.Image to "Images",
                    Icons.Default.Link to "URLs"
                ).forEach { (icon, label) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Text(label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    )
}

@Composable
private fun OnboardGraphPage() {
    OnboardingPageScaffold(
        eyebrow = "AUTO-LINKED",
        title = "Every mention\nbecomes a link.",
        body = "Mention a person, project, or concept and the agent links it. Tap a link, see the connected pages. Ask a question, get answers grounded in your own notes.",
        visual = {
            // A small constellation of nodes connected by lines.
            Canvas(modifier = Modifier.size(240.dp)) {
                val w = size.width
                val h = size.height
                val nodes = listOf(
                    androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.18f),
                    androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.42f),
                    androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.40f),
                    androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.78f),
                    androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.80f),
                    androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.55f)
                )
                val edges = listOf(0 to 5, 1 to 5, 2 to 5, 3 to 5, 4 to 5, 1 to 3, 2 to 4, 0 to 2)
                edges.forEach { (a, b) ->
                    drawLine(
                        color = Color.White.copy(alpha = 0.25f),
                        start = nodes[a],
                        end = nodes[b],
                        strokeWidth = 1.2f
                    )
                }
                nodes.forEachIndexed { idx, p ->
                    val isCenter = idx == 5
                    drawCircle(
                        color = Color.White.copy(alpha = if (isCenter) 1f else 0.7f),
                        radius = if (isCenter) 10f else 6f,
                        center = p
                    )
                }
            }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OnboardApiKeyPage(viewModel: WikiViewModel) {
    val savedApiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    var apiKeyInput by remember { mutableStateOf(savedApiKey) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(modifier = Modifier.weight(0.5f))
        Text(
            text = "BRING YOUR OWN KEY",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            color = Color.White.copy(alpha = 0.5f)
        )
        Text(
            text = "Your Gemini\nAPI key.",
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp
            ),
            color = Color.White
        )
        Text(
            text = "Grab a free key at aistudio.google.com/apikey. You pay Google directly. It's stored on your device only.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = Color.White.copy(alpha = 0.7f)
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = {
                apiKeyInput = it
                viewModel.updateApiKey(it)
            },
            placeholder = { Text("Paste your key", color = Color.White.copy(alpha = 0.4f)) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color.White.copy(alpha = 0.7f)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_api_key_field"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = Color.White
            )
        )
        Text(
            text = "You can leave this blank and set it later in Settings.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f)
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OnboardModelPage(viewModel: WikiViewModel) {
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val modelListState by viewModel.modelListState.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(modifier = Modifier.weight(0.5f))
        Text(
            text = "PICK YOUR ENGINE",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            color = Color.White.copy(alpha = 0.5f)
        )
        Text(
            text = "Which Gemini\nshould drive?",
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp
            ),
            color = Color.White
        )
        Text(
            text = "Flash Lite is cheapest. Flash and Pro stream their thinking. Swap any time from Settings.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = Color.White.copy(alpha = 0.7f)
        )

        Button(
            onClick = { viewModel.refreshAvailableModels() },
            enabled = apiKey.isNotBlank() && modelListState !is ModelListState.Loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                disabledContentColor = Color.White.copy(alpha = 0.3f)
            )
        ) {
            when (modelListState) {
                is ModelListState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetching")
                }
                else -> {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (availableModels.isEmpty()) "Fetch available models" else "Refresh model list")
                }
            }
        }

        if (modelListState is ModelListState.Error) {
            Text(
                "Couldn't fetch: ${(modelListState as ModelListState.Error).message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (availableModels.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedModel.ifBlank { "Choose a model" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.6f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTrailingIconColor = Color.White,
                        unfocusedTrailingIconColor = Color.White.copy(alpha = 0.6f)
                    )
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    availableModels.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.id, fontWeight = FontWeight.Bold)
                                    if (!m.displayName.isNullOrBlank()) {
                                        Text(m.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            onClick = {
                                viewModel.setSelectedModel(m.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Text(
            text = if (apiKey.isBlank())
                "Tip: add your API key first to fetch the model list. You can also skip and set both later."
            else "You can skip this and choose later in Settings.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f)
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ==================== LIVE AGENT ACTIVITY BANNER + STREAM SHEET ====================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveAgentBanner(viewModel: WikiViewModel) {
    val activity by viewModel.agentActivity.collectAsStateWithLifecycle()
    // Drive a recomposition every second so the elapsed timer updates while loading.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(activity.isActive) {
        while (activity.isActive) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    var sheetOpen by remember { mutableStateOf(false) }

    // Hide entirely when nothing has run AND nothing is running.
    if (!activity.isActive && activity.thinking.isEmpty() && activity.answer.isEmpty() && activity.lastError == null) return

    val borderColor = when {
        activity.lastError != null -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        activity.isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { sheetOpen = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (activity.isActive) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = if (activity.lastError != null) Icons.Default.Error else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (activity.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            val title = when {
                activity.isActive -> "Agent working — ${activity.elapsedSec}s"
                activity.lastError != null -> "Last run failed"
                else -> "Last run complete — tap to view"
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            val subtitle = when {
                activity.isActive && activity.thinking.isNotEmpty() -> "Thinking: ${activity.thinking.takeLast(60).replace('\n', ' ')}"
                activity.isActive -> activity.operation
                activity.lastError != null -> activity.lastError!!
                else -> "Tap to inspect what was thought and produced"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState
        ) {
            AgentStreamSheetContent(activity = activity)
        }
    }
}

@Composable
private fun AgentStreamSheetContent(activity: AgentActivity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .heightIn(min = 200.dp, max = 600.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (activity.isActive) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Text(
                text = if (activity.isActive) "Agent thinking… ${activity.elapsedSec}s" else "Last run",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (activity.thinking.isNotBlank()) {
                item {
                    Text(
                        "THINKING",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                item {
                    // Render thinking as markdown (handles headings, lists, [[links]], etc.)
                    // rather than as a raw monospace dump.
                    RichWikiRenderer(
                        content = activity.thinking,
                        onLinkClicked = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (activity.answer.isNotBlank()) {
                item {
                    Text(
                        "Compiling…",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            if (activity.lastError != null) {
                item {
                    Text(
                        "ERROR",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                item {
                    Text(
                        text = activity.lastError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (activity.thinking.isBlank() && activity.answer.isBlank() && activity.lastError == null) {
                item {
                    Text(
                        text = "Stream hasn't produced any text yet — give it a moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==================== LIBRARY AGENT TAKEOVER ====================

// When the agent is actively streaming, replace the Library masthead with this — same vertical
// space, but the wordmark becomes "Thinking" + the live thinking/output text scrolls under it.
@Composable
fun LibraryAgentTakeover(activity: AgentActivity) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "AGENT LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "${activity.elapsedSec}S",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (activity.thinking.isNotBlank()) "Thinking" else "Working",
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
                lineHeight = 64.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Render the model's chain-of-thought as markdown while it streams. Once the JSON answer
        // body begins arriving the thinking is presumably finished — freeze the rendered thinking
        // and show a quiet "Compiling…" footer instead of dumping the JSON to the user.
        val jsonStarted = activity.answer.isNotBlank()
        when {
            activity.thinking.isNotBlank() -> {
                RichWikiRenderer(
                    content = activity.thinking,
                    onLinkClicked = {},
                    modifier = Modifier.fillMaxWidth()
                )
                if (jsonStarted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Compiling page updates…",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            jsonStarted -> {
                // No thinking yet (model didn't emit thinking parts) — just show that the model
                // is busy assembling its output, but don't dump the raw JSON on the user.
                Text(
                    text = "Compiling page updates…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Text(
                    text = "Connecting to the model…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== MAGAZINE MASTHEAD (reusable) ====================

@Composable
fun EditorialMasthead(
    eyebrow: String,
    title: String,
    countText: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val today = remember {
        val cal = java.util.Calendar.getInstance()
        val m = listOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")[cal.get(java.util.Calendar.MONTH)]
        "$m ${cal.get(java.util.Calendar.DAY_OF_MONTH)}, ${cal.get(java.util.Calendar.YEAR)}"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = today,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
                lineHeight = 64.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = countText ?: "",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                content = actions
            )
        }
    }
}

// ==================== TAPPABLE + FULLSCREEN IMAGE ====================

@Composable
fun TappableImage(
    imagePath: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop
) {
    var fullscreen by remember(imagePath) { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val model = remember(imagePath) {
        coil.request.ImageRequest.Builder(ctx)
            .data(if (imagePath.startsWith("/")) java.io.File(imagePath) else imagePath)
            .crossfade(true)
            .build()
    }
    coil.compose.AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.clickable { fullscreen = true },
        contentScale = contentScale
    )
    if (fullscreen) {
        FullscreenImageDialog(imagePath = imagePath, onDismiss = { fullscreen = false })
    }
}

@Composable
fun FullscreenImageDialog(imagePath: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(ctx)
                    .data(if (imagePath.startsWith("/")) java.io.File(imagePath) else imagePath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
