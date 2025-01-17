package com.zt.innertube.network.service

import android.os.Build
import com.zt.innertube.network.body.*
import com.zt.innertube.network.dto.*
import com.zt.innertube.network.dto.auth.AccessToken
import com.zt.innertube.network.dto.auth.RefreshToken
import com.zt.innertube.network.dto.auth.UserCode
import com.zt.innertube.network.dto.browse.*
import com.zt.ktor.brotli.brotli
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.name
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.URLEncoder
import java.util.Locale

class InnerTubeService : CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {
    private val json = Json {
        ignoreUnknownKeys = true

        serializersModule = SerializersModule {
            include(nextModule)
            include(searchModule)
        }
    }

    private val httpClient = HttpClient(
        engineFactory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) CIO else Android
    ) {
        BrowserUserAgent()

        install(ContentNegotiation) {
            json(json)
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 5)
            exponentialDelay()
        }

        install(ContentEncoding) {
            deflate()
            gzip()
            brotli()
        }

        install(HttpCookies)

        defaultRequest {
            contentType(ContentType.Application.Json)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            install(HttpCache)
        }
    }

    private val protobuf = ProtoBuf {
        encodeDefaults = true
    }

    private lateinit var innerTubeContext: ApiContext
    val state = MutableStateFlow<State>(State.Uninitialized)

    sealed interface State {
        object Uninitialized : State
        object Initializing : State
        object Initialized : State
    }

    init {
        launch {
            state.emit(State.Initializing)

            val body = httpClient.get(YOUTUBE_URL).bodyAsText()
            val (ytCfg) = """ytcfg\.set\((.*?)\);""".toRegex()
                .findAll(body)
                .elementAt(1)
                .destructured

            val (context) = json.decodeFromString<InnerTubeData>(ytCfg)
            val locale = Locale.getDefault()

            innerTubeContext = ApiContext(
                client = ApiContext.Client(
                    clientName = CLIENT_NAME,
                    clientVersion = CLIENT_VERSION,
                    gl = locale.country,
                    hl = locale.language,
                    platform = PLATFORM,
                    userAgent = context.client.userAgent,
                    visitorData = context.client.visitorData,
                    clientFormFactor = FORM_FACTOR
                ),
                request = context.request,
                user = context.user
            )

            state.emit(State.Initialized)
        }
    }

    private suspend inline fun <reified T> post(endpoint: String, crossinline body: () -> Body) = withContext(Dispatchers.IO) {
        httpClient.post("$API_URL/$endpoint") {
            parameter("key", API_KEY)
            setBody(body())
        }.body<T>()
    }

    private suspend inline fun <reified T> getBrowse(
        browseId: String,
        continuation: String? = null,
        params: String? = null
    ): T = post("browse") {
        BrowseBody(
            context = innerTubeContext,
            browseId = browseId,
            continuation = continuation,
            params = params
        )
    }

    private suspend inline fun <reified T> encodeProtobuf(value: T) = withContext(Dispatchers.IO) {
        URLEncoder.encode(
            /* s = */ protobuf.encodeToByteArray(value).encodeBase64(),
            /* enc = */ Charsets.UTF_8.name
        )
    }

    suspend fun getClientInfo(): ClientInfo = withContext(Dispatchers.IO) {
        val body = httpClient.get("https://www.youtube.com/tv") {
            userAgent(TV_USER_AGENT)
        }.bodyAsText()

        val (baseJs) = tvBaseJsRegex.find(body)!!.destructured
        val baseJsText = httpClient.get("https://www.youtube.com$baseJs").bodyAsText()

        val (clientId, clientSecret) = tvClientRegex.find(baseJsText)!!.destructured

        ClientInfo(clientId, clientSecret)
    }

    suspend fun getUserCode(clientId: String, deviceId: String): UserCode = withContext(Dispatchers.IO) {
        httpClient.post("$OAUTH_URL/device/code") {
            setBody(UserCodeBody(clientId, deviceId))
        }.body()
    }

    suspend fun getRefreshToken(deviceCode: String, clientId: String, clientSecret: String): RefreshToken = withContext(Dispatchers.IO) {
        httpClient.post("$OAUTH_URL/token") {
            setBody(
                body = RefreshTokenBody(
                    code = deviceCode,
                    clientId = clientId,
                    clientSecret = clientSecret
                )
            )
        }.body()
    }

    internal suspend fun getAccessToken(refreshToken: String, clientId: String, clientSecret: String): AccessToken = withContext(Dispatchers.IO) {
        httpClient.post("$OAUTH_URL/token") {
            setBody(AccessTokenBody(refreshToken, clientId, clientSecret))
        }.body()
    }

    internal suspend fun getAccountsList() {
        httpClient.post("$API_URL/account/accounts_list") {
            setBody(AccountsBody(innerTubeContext))
        }
    }

    internal suspend fun getRecommendations(): ApiRecommended = getBrowse("FEwhat_to_watch")
    internal suspend fun getRecommendations(continuation: String): ApiRecommendedContinuation = getBrowse("FEwhat_to_watch", continuation)

    internal suspend fun getPlaylist(id: String): ApiPlaylist = getBrowse("VL$id")
    internal suspend fun getPlaylist(id: String, continuation: String): ApiPlaylistContinuation = getBrowse(id, continuation)

    internal suspend fun getTrending(): ApiTrending = getBrowse("FEtrending")
    internal suspend fun getTrending(continuation: String): ApiTrendingContinuation = getBrowse("FEtrending", continuation)

    internal suspend fun getChannel(id: String, tab: ChannelTab = ChannelTab.VIDEOS): ApiChannel = getBrowse(id, params = encodeProtobuf(ChannelParams(tab)))

    internal suspend fun getSearchSuggestions(search: String): JsonElement = withContext(Dispatchers.IO) {
        val body = httpClient.get("https://suggestqueries-clients6.youtube.com/complete/search") {
            parameter("client", "youtube")
            parameter("ds", "yt")
            parameter("q", search)
        }.bodyAsText()

        json.parseToJsonElement(body.substringAfter("(").substringBeforeLast(")"))
    }

    internal suspend fun getPlayer(id: String): ApiPlayer = post("player") {
        PlayerBody(
            context = innerTubeContext,
            videoId = id
        )
    }

    internal suspend fun getNext(id: String): ApiNext = post("next") {
        NextBody(
            context = innerTubeContext,
            videoId = id
        )
    }

    internal suspend fun getNext(id: String, continuation: String): ApiNextContinuation = post("next") {
        NextBody(
            context = innerTubeContext,
            videoId = id,
            continuation = continuation
        )
    }

    internal suspend fun getComments(id: String, page: Int = 1) = getNext(
        id = id,
        continuation = encodeProtobuf(CommentParams(id, page))
    )

    internal suspend fun getSearchResults(query: String): ApiSearch = post("search") {
        SearchBody(
            context = innerTubeContext,
            query = query
        )
    }

    internal suspend fun getSearchResults(query: String, continuation: String): ApiSearchContinuation = post("search") {
        SearchBody(
            context = innerTubeContext,
            query = query,
            continuation = continuation
        )
    }

    internal suspend fun getTag(tag: String): ApiTag = getBrowse(
        browseId = "FEhashtag",
        params = encodeProtobuf(ApiTagParams(ApiTagParams.Context(tag.removePrefix("#").lowercase())))
    )

    internal suspend fun getTagContinuation(continuation: String): ApiTagContinuation = getBrowse("FEhashtag", continuation)

    private val apiToken: String = ""

    internal suspend fun getSubscriptions(): ApiSubscriptions = withContext(Dispatchers.IO) {
        httpClient.post("$API_URL/browse") {
            bearerAuth(apiToken)
            setBody(
                body = BrowseBody(
                    context = innerTubeContext,
                    browseId = "FEsubscriptions"
                )
            )
        }.body()
    }

    internal suspend fun getLibrary(): ApiLibrary = withContext(Dispatchers.IO) {
        httpClient.post("$API_URL/browse") {
            bearerAuth(apiToken)
            setBody(
                body = BrowseBody(
                    context = innerTubeContext,
                    browseId = "FElibrary"
                )
            )
        }.body()
    }

    companion object {
        private const val YOUTUBE_URL = "https://www.youtube.com"
        private const val API_URL = "https://www.youtube.com/youtubei/v1"
        private const val API_KEY = "AIzaSyCtkvNIR1HCEwzsqK6JuE6KqpyjusIRI30"

        private const val CLIENT_NAME = "WEB"
        private const val CLIENT_VERSION = "2.20230221.01.00"
        private const val PLATFORM = "DESKTOP"
        private const val FORM_FACTOR = "UNKNOWN_FORM_FACTOR"

        private const val OAUTH_URL = "https://www.youtube.com/o/oauth2"
        private const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private val tvBaseJsRegex = """<script id="base-js" src="(.*?)" nonce=".*?"></script>""".toRegex()
        private val tvClientRegex = """clientId:"([-\w]+\.apps\.googleusercontent\.com)",\w+:"(\w+)"""".toRegex()

        fun getVideoThumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }
}

@Serializable
private data class InnerTubeData(
    @SerialName("INNERTUBE_CONTEXT")
    val innerTubeContext: ApiContext
)