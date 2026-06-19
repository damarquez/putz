package com.damarquez.putz.ui.files

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.damarquez.putz.util.MetadataUtils

/**
 * Renders [name] in a reordered file list, collapsing its leading portion to "..." when it
 * repeats [previousName] (the row above), and underlining the portion that the row below
 * ([nextName]) will collapse, so the shared substring is visible exactly once per group.
 */
@Composable
fun CollapsedFileNameText(
    name: String,
    previousName: String?,
    nextName: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val isCollapsed = previousName != null && name.commonPrefixWith(previousName).length > 5
    if (isCollapsed) {
        Text(text = MetadataUtils.collapsePrefix(name, previousName), style = style, modifier = modifier)
        return
    }

    val underlineLength = MetadataUtils.collapsedPrefixLength(name, nextName)
    if (underlineLength <= 0) {
        Text(text = name, style = style, modifier = modifier)
        return
    }

    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            append(name.substring(0, underlineLength))
        }
        append(name.substring(underlineLength))
    }
    Text(text = annotated, style = style, modifier = modifier)
}
