package com.damarquez.putz.ui.components

import androidx.annotation.DrawableRes
import com.damarquez.putz.R

object FileIconProvider {
    @DrawableRes
    val folder: Int = R.drawable.ic_file_folder

    @DrawableRes
    fun forExtension(extension: String): Int? = when (extension.lowercase()) {
        "cbr"  -> R.drawable.ic_file_cbr
        "pdf"  -> R.drawable.ic_file_pdf
        "m4b"  -> R.drawable.ic_file_m4b
        "m4a"  -> R.drawable.ic_file_m4a
        "mp3"  -> R.drawable.ic_file_mp3
        "mobi" -> R.drawable.ic_file_mobi
        "rar"  -> R.drawable.ic_file_rar
        "rtf"  -> R.drawable.ic_file_rtf
        "zip"  -> R.drawable.ic_file_zip
        else   -> null
    }
}
