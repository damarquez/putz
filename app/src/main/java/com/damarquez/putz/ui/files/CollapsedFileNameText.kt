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
    // How many leading chars are hidden by "..." (0 = not collapsed)
    val prevSharedLength = if (previousName != null) {
        name.commonPrefixWith(previousName).length.let { if (it > 5) it else 0 }
    } else 0
    val isCollapsed = prevSharedLength > 0

    // How many leading chars the next row will also omit (0 = next won't collapse)
    val nextSharedLength = MetadataUtils.collapsedPrefixLength(name, nextName)

    // Chars that are visible in THIS row but still omitted by the next → underline them
    val underlineInVisible = maxOf(0, nextSharedLength - prevSharedLength)

    if (!isCollapsed && underlineInVisible == 0) {
        Text(text = name, style = style, modifier = modifier)
        return
    }

    val annotated = buildAnnotatedString {
        if (isCollapsed) append("...")
        if (underlineInVisible > 0) {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                append(name.substring(prevSharedLength, prevSharedLength + underlineInVisible))
            }
        }
        append(name.substring(prevSharedLength + underlineInVisible))
    }
    Text(text = annotated, style = style, modifier = modifier)
}
