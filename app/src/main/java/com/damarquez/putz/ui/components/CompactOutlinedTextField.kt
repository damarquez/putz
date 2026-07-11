package com.damarquez.putz.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * An OutlinedTextField with reduced vertical content padding (~6 dp instead of the default 16 dp),
 * making it noticeably shorter while retaining the full M3 outlined style and label behaviour.
 * Opts into ExperimentalMaterial3Api internally so callers don't need the annotation.
 *
 * @param dense Shrinks content padding further (~2 dp) and uses bodyMedium instead of bodyLarge for
 * the field text, for UIs with many stacked fields (e.g. the batch send-to-Calibre list) where
 * [CompactOutlinedTextField]'s normal size is still too tall. Leaves other callers unaffected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    dense: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = OutlinedTextFieldDefaults.colors()
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val baseTextStyle = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = baseTextStyle.copy(color = textColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = isError,
                label = { Text(label, style = if (dense) MaterialTheme.typography.bodySmall else LocalTextStyle.current) },
                placeholder = placeholder?.let { { Text(it, style = if (dense) MaterialTheme.typography.bodySmall else LocalTextStyle.current) } },
                trailingIcon = trailingIcon,
                supportingText = supportingText,
                colors = colors,
                contentPadding = OutlinedTextFieldDefaults.contentPadding(
                    top = if (dense) 2.dp else 6.dp,
                    bottom = if (dense) 2.dp else 6.dp,
                ),
                container = {
                    OutlinedTextFieldDefaults.ContainerBox(
                        enabled = enabled,
                        isError = isError,
                        interactionSource = interactionSource,
                        colors = colors,
                    )
                },
            )
        },
    )
}
