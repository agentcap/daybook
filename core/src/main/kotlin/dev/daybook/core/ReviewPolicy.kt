package dev.daybook.core

object ReviewPolicy {
    fun allowed(settings: Settings) = settings.reviewProvider == "local" || settings.cloudConsent
    fun needsNetwork(provider: String) = provider == "openai"
}
