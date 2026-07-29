package ai.fatai

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform