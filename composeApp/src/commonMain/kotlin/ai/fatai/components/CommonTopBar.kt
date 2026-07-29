package ai.fatai.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 一个通用的、居中对齐的顶部应用栏。
 *
 * @param title 顶部栏显示的标题文字。
 * @param modifier 应用于此顶部栏的 Modifier。
 * @param showNavIcon 是否显示导航图标（通常是返回箭头）。
 * @param navIcon 导航图标的 ImageVector，默认为返回箭头。
 * @param onNavClick 导航图标的点击事件回调。
 * @param actions 右侧的操作按钮区域，是一个 Composable lambda。
 * @param colors 顶部栏的颜色配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    showNavIcon: Boolean = false,
    navIcon: ImageVector = FeatherIcons.ArrowLeft,
    navigationContentDescription: String? = null,
    onNavClick: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
        containerColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onPrimary,
        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
    ),
    topAppBarScrollBehavior: TopAppBarScrollBehavior? = null
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (showNavIcon) {
                IconButton(onClick = onNavClick) {
                    Icon(
                        imageVector = navIcon,
                        contentDescription = navigationContentDescription
                    )
                }
            }
        },
        actions = actions,
        colors = colors,
        scrollBehavior = topAppBarScrollBehavior
    )
}
