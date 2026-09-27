package com.craftworks.music.ui.elements.dialogs

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.craftworks.music.R
import com.craftworks.music.data.BottomNavItem
import com.craftworks.music.managers.settings.AppTheme
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.managers.settings.MiniPlayerButtonLayout
import com.craftworks.music.managers.settings.rememberAppearanceSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftworks.music.ui.elements.bounceClick
import com.craftworks.music.ui.playing.NowPlayingAlignment
import com.craftworks.music.ui.playing.NowPlayingBackground
import com.craftworks.music.ui.screens.HomeItem
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

//region PREVIEWS
@Preview(showBackground = true)
@Composable
fun PreviewBackgroundDialog(){
    BackgroundDialog(setShowDialog = { })
}

@Preview(showBackground = true)
@Composable
fun PreviewNavbarItemsDialog(){
    NavbarItemsDialog(setShowDialog = { })
}
@Preview(showBackground = true)
@Composable
fun PreviewHomeItemsDialog(){
    HomeItemsDialog(setShowDialog = { })
}

@Preview(showBackground = true)
@Composable
fun PreviewThemeDialog(){
    ThemeDialog(setShowDialog = { })
}
//endregion

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Preview
@Composable
fun NameDialog(setShowDialog: (Boolean) -> Unit = {} ) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = rememberAppearanceSettings()
    val username by rememberAppearanceSettings().usernameFlow.collectAsStateWithLifecycle("Username")
    var usernameTextField by remember(username) { mutableStateOf(username) }

    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(stringResource(R.string.Setting_Username)) },
        text = {
            OutlinedTextField(
                value = usernameTextField,
                onValueChange = {
                    coroutineScope.launch {
                        settings.setUsername(it)
                    }
                },
                label = { stringResource(R.string.Setting_Username) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = {
                coroutineScope.launch {
                    settings.setUsername(username)
                    setShowDialog(false)
                }
            }) {
                Text(stringResource(R.string.Action_Done))
            }
        }
    )
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun BackgroundDialog(setShowDialog: (Boolean) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = rememberAppearanceSettings()

    val backgroundType by rememberAppearanceSettings().npBackgroundFlow.collectAsStateWithLifecycle(NowPlayingBackground.ANIMATED_BLUR)

    val backgroundTypeLabels = mapOf(
        NowPlayingBackground.PLAIN to R.string.Background_Plain,
        NowPlayingBackground.STATIC_BLUR to R.string.Background_Blur,
        NowPlayingBackground.ANIMATED_BLUR to R.string.Background_Anim
    )

    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(stringResource(R.string.Setting_Background)) },
        text = {
            Column{
                NowPlayingBackground.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = (option == backgroundType),
                                onClick = {
                                    coroutineScope.launch {
                                        settings.setBackgroundType(option)
                                    }
                                    setShowDialog(false)
                                },
                                role = Role.RadioButton,
                                enabled = !(option == NowPlayingBackground.ANIMATED_BLUR && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == backgroundType,
                            onClick = {
                                coroutineScope.launch {
                                    settings.setBackgroundType(option)
                                }
                                setShowDialog(false)
                            },
                            modifier = Modifier.bounceClick(),
                            enabled = !(option == NowPlayingBackground.ANIMATED_BLUR && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                        )
                        Text(
                            text = stringResource(id = backgroundTypeLabels[option] ?: androidx.media3.session.R.string.error_message_invalid_state) +
                                    if (option == NowPlayingBackground.ANIMATED_BLUR && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                                        " (Android 13+)"
                                    else "",
                            fontWeight = FontWeight.Normal,
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = { }
    )
}


@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun ThemeDialog(setShowDialog: (Boolean) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = rememberAppearanceSettings()

    val selectedTheme by AppearanceSettingsManager(context).appTheme.collectAsStateWithLifecycle(
        AppTheme.SYSTEM.name)

    val themes = listOf(
       AppTheme.DARK,
       AppTheme.LIGHT,
       AppTheme.SYSTEM
    )

    val themeStrings = listOf(
        R.string.Theme_Dark, R.string.Theme_Light, R.string.Theme_System
    )

    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(stringResource(R.string.Dialog_Theme)) },
        text = {
            Column{
                for ((index, option) in themes.withIndex()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = (option.name == selectedTheme),
                                onClick = {
                                    coroutineScope.launch {
                                        settings.setAppTheme(option)
                                        val uiModeManager =
                                            context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

                                        when (option) {
                                            AppTheme.DARK -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                                    uiModeManager.setApplicationNightMode(
                                                        UiModeManager.MODE_NIGHT_YES
                                                    )
                                                else
                                                    AppCompatDelegate.setDefaultNightMode(
                                                        AppCompatDelegate.MODE_NIGHT_YES
                                                    )
                                            }

                                            AppTheme.LIGHT -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                                    uiModeManager.setApplicationNightMode(
                                                        UiModeManager.MODE_NIGHT_NO
                                                    )
                                                else
                                                    AppCompatDelegate.setDefaultNightMode(
                                                        AppCompatDelegate.MODE_NIGHT_NO
                                                    )
                                            }

                                            AppTheme.SYSTEM -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                                    uiModeManager.setApplicationNightMode(
                                                        UiModeManager.MODE_NIGHT_AUTO
                                                    )
                                                else
                                                    AppCompatDelegate.setDefaultNightMode(
                                                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                                    )
                                            }
                                        }
                                    }
                                    setShowDialog(false)
                                },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.name == selectedTheme,
                            onClick = {
                                coroutineScope.launch {
                                    settings.setAppTheme(option)
                                    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

                                    when (option) {
                                       AppTheme.DARK -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                                uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                                            else
                                                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                                        }
                                       AppTheme.LIGHT -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                                uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                                            else
                                                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                                        }
                                       AppTheme.SYSTEM -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                                uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                                            else
                                                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                                        }
                                    }
                                }
                                setShowDialog(false)
                            },
                            modifier = Modifier.bounceClick()
                        )
                        Text(
                            text = stringResource(id = themeStrings[index]),
                            fontWeight = FontWeight.Normal,
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = { }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavbarItemsDialog(setShowDialog: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settings = rememberAppearanceSettings()
    val coroutineScope = rememberCoroutineScope()
    val bottomNavigationItems =
        (rememberAppearanceSettings().bottomNavItemsFlow.collectAsStateWithLifecycle(emptyList()).value).toMutableList()

    val navItemTitleMap = remember {
        mapOf(
            "Home" to "首页",
            "Albums" to "专辑",
            "Songs" to "歌曲",
            "Playlists" to "播放列表"
        )
    }

    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(stringResource(R.string.Setting_Navbar_Items)) },
        text = {
            val lazyListState = rememberLazyListState()
            val reorderableLazyColumnState =
                rememberReorderableLazyListState(lazyListState) { from, to ->
                    settings.setBottomNavItems(bottomNavigationItems.toMutableList()
                        .apply {
                            add(to.index, removeAt(from.index))
                        })
                }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = lazyListState
            ) {
                items(bottomNavigationItems, key = { it.title }) { navItem ->
                    ReorderableItem(reorderableLazyColumnState, navItem.title) {
                        val interactionSource = remember { MutableInteractionSource() }
                        val index = bottomNavigationItems.indexOf(navItem)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(48.dp)
                        ) {
                            Checkbox(
                                enabled = navItem.title != "Home",
                                checked = bottomNavigationItems[index].enabled,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        bottomNavigationItems[index] = bottomNavigationItems[index].copy(enabled = it)
                                        settings.setBottomNavItems(bottomNavigationItems)
                                    }
                                },
                                modifier = Modifier
                                    .semantics { contentDescription = navItem.title }
                                    .bounceClick()
                            )
                            Text(
                                text = navItemTitleMap[navItem.title] ?: navItem.title,
                                fontWeight = FontWeight.Normal,
                                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                    },
                                    onDragStopped = {
                                    },
                                    interactionSource = interactionSource,
                                ),
                                onClick = {},
                            ) {
                                Icon(
                                    ImageVector.vectorResource(R.drawable.baseline_drag_handle_24),
                                    contentDescription = "Reorder"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                setShowDialog(false)
            }) {
                Text(stringResource(R.string.Action_Done))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        settings.setBottomNavItems(
                            //region Default Values
                            mutableStateListOf(
                                BottomNavItem(
                                    "Home", R.drawable.rounded_home_24, "home_screen"
                                ), BottomNavItem(
                                    "Albums",
                                    R.drawable.rounded_library_music_24,
                                    "album_screen"
                                ), BottomNavItem(
                                    "Songs",
                                    R.drawable.round_music_note_24,
                                    "songs_screen"
                                ), BottomNavItem(
                                    "Artists",
                                    R.drawable.rounded_artist_24,
                                    "artists_screen"
                                ), BottomNavItem(
                                    "Radios", R.drawable.rounded_radio, "radio_screen"
                                ), BottomNavItem(
                                    "Playlists",
                                    R.drawable.placeholder,
                                    "playlist_screen"
                                )
                            ) //endregion
                        )
                        setShowDialog(false)
                    }
                }
            ) {
                Text(stringResource(R.string.Action_Reset))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeItemsDialog(setShowDialog: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settings = rememberAppearanceSettings()
    val coroutineScope = rememberCoroutineScope()
    val homeItems =
        (rememberAppearanceSettings().homeItemsItemsFlow.collectAsStateWithLifecycle(emptyList()).value).toMutableList()

    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(stringResource(R.string.Setting_Home_Items)) },
        text = {
            val lazyListState = rememberLazyListState()
            val reorderableLazyColumnState =
                rememberReorderableLazyListState(lazyListState) { from, to ->
                    settings.setHomeItems(homeItems.toMutableList()
                        .apply {
                            add(to.index, removeAt(from.index))
                        })
                }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = lazyListState
            ) {
                items(homeItems, key = { it.key }) { item ->
                    ReorderableItem(reorderableLazyColumnState, item.key) {
                        val interactionSource = remember { MutableInteractionSource() }
                        val index = homeItems.indexOf(item)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(48.dp)
                        ) {
                            Checkbox(
                                checked = homeItems[index].enabled,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        homeItems[index] = homeItems[index].copy(enabled = it)
                                        settings.setHomeItems(homeItems)
                                    }
                                },
                                modifier = Modifier
                                    .bounceClick()
                            )
                            val titleMap = remember {
                                mapOf(
                                    "recently_played" to R.string.recently_played,
                                    "recently_added" to R.string.recently_added,
                                    "most_played" to R.string.most_played,
                                    "random_songs" to R.string.random_songs
                                )
                            }
                            Text(
                                text = stringResource(titleMap[item.key] ?: androidx.media3.session.R.string.error_message_fallback),
                                fontWeight = FontWeight.Normal,
                                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                    },
                                    onDragStopped = {
                                    },
                                    interactionSource = interactionSource,
                                ),
                                onClick = {},
                            ) {
                                Icon(
                                    ImageVector.vectorResource(R.drawable.baseline_drag_handle_24),
                                    contentDescription = "Reorder"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                setShowDialog(false)
            }) {
                Text(stringResource(R.string.Action_Done))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        settings.setHomeItems(
                            //region Default Values
                            mutableStateListOf(
                                HomeItem(
                                    "recently_played",
                                    true
                                ),
                                HomeItem(
                                    "recently_added",
                                    true
                                ),
                                HomeItem(
                                    "most_played",
                                    true
                                ),
                                HomeItem(
                                    "random_songs",
                                    true
                                )
                            ) //endregion
                        )
                        setShowDialog(false)
                    }
                }
            ) {
                Text(stringResource(R.string.Action_Reset))
            }
        }
    )
}

@Composable
@Preview
fun NowPlayingTitleAlignmentDialog(
    setShowDialog: (Boolean) -> Unit = { },
    title: String = "",
    selection: NowPlayingAlignment = NowPlayingAlignment.LEFT,
    onSet: (NowPlayingAlignment) -> Unit = { }
) {
    val nowPlayingTitleAlignment by remember { mutableStateOf(selection) }

    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(title) },
        text = {
            Column {
                NowPlayingAlignment.entries.forEach { alignment ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = (alignment == nowPlayingTitleAlignment),
                                onClick = {
                                    onSet(alignment)
                                    setShowDialog(false)
                                },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = alignment == nowPlayingTitleAlignment,
                            onClick = {
                                onSet(alignment)
                            },
                            modifier = Modifier.bounceClick()
                        )
                        val alignmentStringRes = when (alignment) {
                            NowPlayingAlignment.LEFT -> R.string.NowPlayingTitleAlignment_Left
                            NowPlayingAlignment.CENTER -> R.string.NowPlayingTitleAlignment_Center
                            NowPlayingAlignment.RIGHT -> R.string.NowPlayingTitleAlignment_Right
                        }

                        Text(
                            text = stringResource(id = alignmentStringRes),
                            fontWeight = FontWeight.Normal,
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = { }
    )
}

@Composable
@Preview
fun PageTransitionStyleDialog(
    setShowDialog: (Boolean) -> Unit = { },
    title: String = "播放界面滑动动画",
    selection: com.craftworks.music.managers.settings.PageTransitionStyle = com.craftworks.music.managers.settings.PageTransitionStyle.ELEGANT_SPRING,
    onSet: (com.craftworks.music.managers.settings.PageTransitionStyle) -> Unit = { }
) {
    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(title) },
        text = {
            Column {
                com.craftworks.music.managers.settings.PageTransitionStyle.entries.forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = (style == selection),
                                onClick = {
                                    onSet(style)
                                    setShowDialog(false)
                                },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = style == selection,
                            onClick = {
                                onSet(style)
                                setShowDialog(false)
                            },
                            modifier = Modifier.bounceClick()
                        )
                        val (label, desc) = when (style) {
                            com.craftworks.music.managers.settings.PageTransitionStyle.ELEGANT_SPRING -> "优雅平滑" to "自然阻尼，无回弹 (推荐)"
                            com.craftworks.music.managers.settings.PageTransitionStyle.CUBIC_BEZIER -> "经典缓动" to "贝塞尔平滑缓动"
                            com.craftworks.music.managers.settings.PageTransitionStyle.SNAPPY -> "干脆利落" to "快速无延迟切换"
                            com.craftworks.music.managers.settings.PageTransitionStyle.GENTLE -> "柔和渐进" to "节奏从容，柔和过渡"
                        }

                        Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                            Text(
                                text = label,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { }
    )
}

@Composable
@Preview
fun MiniPlayerButtonLayoutDialog(
    setShowDialog: (Boolean) -> Unit = { },
    title: String = "",
    selection: MiniPlayerButtonLayout = MiniPlayerButtonLayout.SYMMETRIC,
    onSet: (MiniPlayerButtonLayout) -> Unit = { }
) {
    AlertDialog(
        onDismissRequest = { setShowDialog(false) },
        title = { Text(title) },
        text = {
            Column {
                MiniPlayerButtonLayout.entries.forEach { layout ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = (layout == selection),
                                onClick = {
                                    onSet(layout)
                                    setShowDialog(false)
                                },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = layout == selection,
                            onClick = {
                                onSet(layout)
                                setShowDialog(false)
                            },
                            modifier = Modifier.bounceClick()
                        )
                        val labelRes = when (layout) {
                            MiniPlayerButtonLayout.LEFT_PAIRED -> R.string.MiniPlayerButtons_LeftPaired
                            MiniPlayerButtonLayout.SYMMETRIC -> R.string.MiniPlayerButtons_Symmetric
                            MiniPlayerButtonLayout.RIGHT_PAIRED -> R.string.MiniPlayerButtons_RightPaired
                        }
                        val descRes = when (layout) {
                            MiniPlayerButtonLayout.LEFT_PAIRED -> R.string.MiniPlayerButtons_LeftPaired_Desc
                            MiniPlayerButtonLayout.SYMMETRIC -> R.string.MiniPlayerButtons_Symmetric_Desc
                            MiniPlayerButtonLayout.RIGHT_PAIRED -> R.string.MiniPlayerButtons_RightPaired_Desc
                        }

                        Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                            Text(
                                text = stringResource(labelRes),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { }
    )
}