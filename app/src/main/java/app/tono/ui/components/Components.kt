package app.tono.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tono.R
import app.tono.ui.theme.Tono

val Radius12 = RoundedCornerShape(12.dp)
val Radius14 = RoundedCornerShape(14.dp)
val Radius18 = RoundedCornerShape(18.dp)
val Radius24 = RoundedCornerShape(24.dp)
val Pill = RoundedCornerShape(percent = 50)

@Composable
fun TonoIcon(
    @DrawableRes res: Int,
    modifier: Modifier = Modifier,
    tint: Color = Tono.colors.text,
    size: Dp = 22.dp,
    contentDescription: String? = null,
) {
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)
}

/** 44×44 icon-only button (the design's minimum touch target). */
@Composable
fun IconButton44(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Tono.colors.text,
    background: Color = Color.Transparent,
    iconSize: Dp = 22.dp,
    shape: androidx.compose.ui.graphics.Shape = Radius14,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(shape)
            .background(background)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { TonoIcon(icon, tint = tint, size = iconSize) }
}

/** Back / close + centered title, as on Trim, Save and Filters. */
@Composable
fun TopBar(
    title: String,
    @DrawableRes navIcon: Int,
    navLabel: String,
    onNav: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = { Spacer(Modifier.width(44.dp)) },
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton44(navIcon, navLabel, onNav)
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        trailing()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text.uppercase(),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelSmall,
            color = Tono.colors.muted,
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Tono.colors.muted)
    }
}

/** Rounded tile with a music note, used for result rows and track headers. */
@Composable
fun MusicTile(size: Dp = 52.dp, iconSize: Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(Radius14).background(Tono.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) { TonoIcon(R.drawable.ic_music, tint = Tono.colors.accent, size = iconSize) }
}

/** Album art / thumbnail, cropped square; the music-note tile shows while loading or if there's none. */
@Composable
fun Artwork(
    url: String?,
    size: Dp = 52.dp,
    shape: androidx.compose.ui.graphics.Shape = Radius14,
    iconSize: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clip(shape).background(Tono.colors.accentSoft), contentAlignment = Alignment.Center) {
        TonoIcon(R.drawable.ic_music, tint = Tono.colors.accent, size = iconSize)
        if (url != null) {
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
) {
    val c = Tono.colors
    Row(
        modifier
            .height(56.dp)
            .clip(Radius18)
            .background(if (enabled) c.accent else c.off)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.onAccent else c.muted)
        if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            TonoIcon(trailingIcon, tint = c.onAccent, size = 18.dp)
        }
    }
}

/** Single-choice chip (Length, Sort by, Clip length…): filled with text color when selected. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Tono.colors
    Box(
        Modifier
            .height(44.dp)
            .clip(Radius12)
            .background(if (selected) c.text else Color.Transparent)
            .border(1.dp, if (selected) c.text else c.line, Radius12)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.bg else c.text)
    }
}

/** Multi-select chip ("Hide versions"): accent tint + check when on. */
@Composable
fun ToggleChip(text: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Tono.colors
    Row(
        Modifier
            .height(44.dp)
            .clip(Radius12)
            .background(if (on) c.accentSoft else Color.Transparent)
            .border(1.dp, if (on) c.accent else c.line, Radius12)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on) TonoIcon(R.drawable.ic_check, tint = c.accent, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.text)
    }
}

/** Source tab pill (YouTube / Link / On device). */
@Composable
fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Tono.colors
    Box(
        Modifier
            .height(44.dp)
            .clip(Pill)
            .background(if (selected) c.text else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else c.line, Pill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.bg else c.muted)
    }
}

@Composable
fun TonoSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    val c = Tono.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.accent,
            checkedThumbColor = c.onAccent,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = c.off,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent,
        ),
        thumbContent = null,
    )
}

/** Label + hint + switch. The whole row toggles. */
@Composable
fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Tono.colors.muted)
        }
        TonoSwitch(checked, null)
    }
}

/** Surface card with a 1dp hairline, used to group rows. */
@Composable
fun Card(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = Radius18, content: @Composable () -> Unit) {
    val c = Tono.colors
    Box(modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape)) { content() }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tono.colors.line))
}

@Composable
fun CountBadge(count: Int) {
    val c = Tono.colors
    Box(
        Modifier.heightIn(min = 20.dp).clip(Pill).background(c.accent).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = c.onAccent, fontSize = 12.sp, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SongRow(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
    onOpen: () -> Unit = onAction,
) {
    val c = Tono.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(Radius18)
            .background(if (highlighted) c.surface else Color.Transparent)
            .clickable(onClickLabel = "Open $title", onClick = onOpen)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(thumbnailUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton44(
            R.drawable.ic_scissors,
            actionLabel,
            onAction,
            tint = if (highlighted) c.onAccent else c.text,
            background = if (highlighted) c.accent else c.surface,
            iconSize = 20.dp,
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
