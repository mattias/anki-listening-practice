package dev.lovkvist.listening

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.lovkvist.listening.data.dict.DictionaryUpdateWorker

@HiltAndroidApp
class ListeningApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DictionaryUpdateWorker.schedule(this)
    }
}
