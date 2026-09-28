package io.github.wearmedia.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SpeakerPhone
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.R

/** Icon for where the audio plays, as the phone reports it. */
fun outputIcon(kind: String): ImageVector = when (kind) {
    "bluetooth" -> Icons.Rounded.Headphones
    "wired" -> Icons.Rounded.Headset
    "usb" -> Icons.Rounded.Usb
    else -> Icons.Rounded.SpeakerPhone
}

/** Picks the phone's audio output (speaker, Bluetooth, wired...) right from the watch. */
@Composable
fun OutputPicker(link: PhoneLink, onDismiss: () -> Unit) {
    val outputs by link.outputs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { link.requestOutputs() }
    BackHandler(onBack = onDismiss)

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    RequestFocusWhenActive(focusRequester, true)
    val thisPhone = stringResource(R.string.this_phone)

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF5000000))
            .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
    ) {
        item {
            BasicText(
                stringResource(R.string.play_on),
                style = WearText.header.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        val current = outputs
        if (current == null) {
            item {
                Box(Modifier.padding(16.dp)) { PlayingIndicator(Modifier.size(16.dp, 18.dp)) }
            }
        } else {
            item {
                OutputRow(Icons.Rounded.AutoMode, stringResource(R.string.output_auto), selected = current.auto) {
                    link.selectOutput(null)
                }
            }
            current.devices.forEach { device ->
                item(key = device.id) {
                    OutputRow(
                        icon = outputIcon(device.kind),
                        name = if (device.kind == "phone") thisPhone else device.name,
                        selected = device.active,
                    ) {
                        link.selectOutput(device.id)
                        onDismiss()
                    }
                }
            }
        }
        item {
            OutputRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.more_on_phone), selected = false, dim = true) {
                link.output()
                onDismiss()
            }
        }
        item { PickerChip(stringResource(R.string.cancel), onClick = onDismiss) }
    }
}

@Composable
private fun OutputRow(icon: ImageVector, name: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) WearColors.accent.copy(alpha = 0.25f) else WearColors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, size = 22.dp, tint = if (selected) WearColors.accent else if (dim) WearColors.textSecondary else Color.White)
        Spacer(Modifier.width(10.dp))
        BasicText(
            name,
            style = WearText.body.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (dim) WearColors.textSecondary else Color.White,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Check, size = 20.dp, tint = WearColors.accent)
        }
    }
}
