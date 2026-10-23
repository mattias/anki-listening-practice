package dev.lovkvist.listening.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lovkvist.listening.data.local.PracticeDatabase
import dev.lovkvist.listening.data.local.SentenceStateDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PracticeDatabase =
        Room.databaseBuilder(context, PracticeDatabase::class.java, "practice.db")
            .addMigrations(PracticeDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun sentenceStateDao(db: PracticeDatabase): SentenceStateDao = db.sentenceStateDao()
}
