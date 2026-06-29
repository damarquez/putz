package com.damarquez.putz

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PutzApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(GifDecoder.Factory()) }
        .build()
}
