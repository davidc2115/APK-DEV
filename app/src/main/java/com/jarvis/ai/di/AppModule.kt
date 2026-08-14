package com.jarvis.ai.di

import android.content.Context
import androidx.room.Room
import com.jarvis.ai.core.ai.AIProvider
import com.jarvis.ai.core.ai.providers.ClaudeProvider
import com.jarvis.ai.core.ai.providers.GeminiProvider
import com.jarvis.ai.core.ai.providers.GroqProvider
import com.jarvis.ai.core.ai.providers.LocalLlmProvider
import com.jarvis.ai.core.ai.providers.OllamaProvider
import com.jarvis.ai.core.ai.providers.OpenAIProvider
import com.jarvis.ai.core.ai.providers.PerplexityProvider
import com.jarvis.ai.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "jarvis.db").build()

    @Provides
    fun provideConversationDao(db: AppDatabase) = db.conversationDao()

    // Chaque fournisseur IA s'enregistre dans le Set<AIProvider> injecté par AIRouter.
    @Provides @IntoSet @Singleton
    fun provideClaudeProvider(p: ClaudeProvider): AIProvider = p

    @Provides @IntoSet @Singleton
    fun provideOpenAIProvider(p: OpenAIProvider): AIProvider = p

    @Provides @IntoSet @Singleton
    fun provideGeminiProvider(p: GeminiProvider): AIProvider = p

    @Provides @IntoSet @Singleton
    fun provideGroqProvider(p: GroqProvider): AIProvider = p

    @Provides @IntoSet @Singleton
    fun providePerplexityProvider(p: PerplexityProvider): AIProvider = p

    @Provides @IntoSet @Singleton
    fun provideOllamaProvider(p: OllamaProvider): AIProvider = p

    @Provides @IntoSet @Singleton
    fun provideLocalLlmProvider(p: LocalLlmProvider): AIProvider = p
}
