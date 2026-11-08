package dev.lovkvist.listening.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lovkvist.listening.data.local.PracticeDatabase
import dev.lovkvist.listening.data.local.ListeningStatDao
import dev.lovkvist.listening.data.local.PracticeDayDao
import dev.lovkvist.listening.data.local.WordStatusDao
import dev.lovkvist.listening.data.local.SentenceStateDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PracticeDatabase =
        Room.databaseBuilder(context, PracticeDatabase::class.java, "practice.db")
            .addMigrations(PracticeDatabase.MIGRATION_1_2, PracticeDatabase.MIGRATION_2_3, PracticeDatabase.MIGRATION_3_4, PracticeDatabase.MIGRATION_4_5)
            .build()

    @Provides
    fun sentenceStateDao(db: PracticeDatabase): SentenceStateDao = db.sentenceStateDao()

    @Provides
    fun practiceDayDao(db: PracticeDatabase): PracticeDayDao = db.practiceDayDao()

    @Provides
    fun listeningStatDao(db: PracticeDatabase): ListeningStatDao = db.listeningStatDao()

    @Provides
    fun wordStatusDao(db: PracticeDatabase): WordStatusDao = db.wordStatusDao()
}
