package mihon.discover

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque

/** Shared process-wide rate gate for public catalogue and authenticated tracker requests. */
object AniListSharedRateInterceptor : Interceptor {
    private const val WINDOW_MS = 60_000L
    private const val DEFAULT_LIMIT = 25
    private val lock = Object()
    private val recent = ArrayDeque<Long>()
    private var limit = DEFAULT_LIMIT
    private var blockedUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.host != "graphql.anilist.co") return chain.proceed(chain.request())
        val call = chain.call()
        synchronized(lock) {
            while (true) {
                if (call.isCanceled()) throw IOException("AniList request cancelled")
                val now = System.currentTimeMillis()
                while (recent.isNotEmpty() && recent.first() <= now - WINDOW_MS) recent.removeFirst()
                val untilWindow = if (recent.size >= limit) recent.first() + WINDOW_MS else now
                val wait = maxOf(blockedUntil, untilWindow) - now
                if (wait <= 0) {
                    recent.addLast(now)
                    break
                }
                try {
                    lock.wait(wait.coerceAtMost(250L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("AniList request interrupted", e)
                }
            }
        }
        val response = chain.proceed(chain.request())
        synchronized(lock) {
            response.header("X-RateLimit-Limit")?.toIntOrNull()?.let { limit = it.coerceIn(1, DEFAULT_LIMIT) }
            if (response.code == 429 || response.header("X-RateLimit-Remaining") == "0") {
                val now = System.currentTimeMillis()
                val retryAt = response.header("Retry-After")?.toLongOrNull()?.let { now + it * 1000L }
                val resetAt = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000L)
                blockedUntil = maxOf(blockedUntil, retryAt ?: resetAt ?: now + WINDOW_MS)
            }
            lock.notifyAll()
        }
        return response
    }
}
