package com.prototypejust.weworkshop.steam

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.SteamAuthentication
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Steam 会话管理（javaSteam）。
 *
 * 登录流程：
 *  1. [loginAsync] 用户名 + 密码 → beginAuthSessionViaCredentials
 *  2. 如 Steam 要求 Guard 验证码，[onGuardRequired] 回调 UI，[submitGuardCode] 提交
 *  3. 得到 refreshToken 后用 [logOnWithToken] 登录，成功后 [isLoggedIn] = true
 *  4. 下次启动 [init] 用本地保存的 refreshToken 自动恢复
 */
object SteamService {
    private const val TAG = "WESteam"
    private const val CONNECT_TIMEOUT_MS = 40_000L
    private const val LOGON_TIMEOUT_MS = 90_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var client: SteamClient? = null
    @Volatile private var callbackManager: CallbackManager? = null
    @Volatile private var user: SteamUser? = null
    @Volatile private var apps: SteamApps? = null
    @Volatile private var content: SteamContent? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var refreshToken: String = ""
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var restoreJob: Job? = null

    @Volatile var isLoggedIn = false
        private set
    @Volatile var accountName = ""
        private set
    @Volatile var steamId64 = 0L
        private set
    @Volatile var busy = false
        private set
    @Volatile var status = "未登录"
        private set
    @Volatile var error = ""
        private set
    @Volatile var guardNeeded = false
        private set
    @Volatile var guardType = ""
        private set
    @Volatile var guardEmail = ""
        private set

    @Volatile private var stateListener: Runnable? = null
    @Volatile private var guardListener: Runnable? = null

    @Volatile private var logonCallback: LoggedOnCallback? = null
    @Volatile private var guardFuture: CompletableFuture<String>? = null

    /**
     * 本次登录是否跳过「手机确认」，直接走验证码。
     *
     * javaSteam 的 AuthSession.pollingWaitForResult() 会先看 allowedConfirmations[0]：
     * 手机令牌账号这一项通常是 DeviceConfirmation，此时它会调 IAuthenticator.acceptDeviceConfirmation()，
     * 返回 true 就进入 pollDeviceConfirmation() 的 while(true) 死等——没有任何回调、没有任何超时，
     * 用户在没收到手机弹窗时只能干等到外层 600s 超时，表现就是「一直转圈然后登录失败」。
     * 返回 false 则降级到 allowedConfirmations[1]（DeviceCode），由 UI 收验证码，这条路径一定能走通。
     */
    @Volatile private var forceCodeLogin = false

    /** UI 点了「改用验证码」：需要中断当前正在等待手机确认的登录，重来一次。 */
    @Volatile private var codeLoginRequested = false

    /**
     * 该账号是否真的能走「验证码」。
     *
     * 只有当 Steam 下发的 allowedConfirmations 里含 DeviceCode / EmailCode 时，
     * 放弃手机确认才有意义；否则点「改用验证码」只会重建一次会话再撞回同一堵墙。
     * 界面按钮据此显示，避免用户点进死胡同。
     */
    @Volatile var codeFallbackAvailable = false
        private set

    /** 正在等待手机确认的 poll future，取消它才能让 .get() 立刻返回。 */
    @Volatile private var pollFuture: CompletableFuture<*>? = null

    // ---------------- 生命周期 ----------------

    /** 应用启动时调用；若本地有 refreshToken 会后台自动恢复会话。 */
    @JvmStatic
    fun setStateListener(listener: Runnable?) { stateListener = listener }

    @JvmStatic
    fun setGuardListener(listener: Runnable?) { guardListener = listener }

    @JvmStatic
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        val sp = app.getSharedPreferences("steam_session", Context.MODE_PRIVATE)
        prefs = sp
        val token = sp.getString("refresh_token", "").orEmpty()
        val account = sp.getString("account_name", "").orEmpty()
        refreshToken = token
        if (token.isNotBlank() && account.isNotBlank()) {
            accountName = account
            status = "正在恢复 Steam 会话…"
            restoreJob = scope.launch {
                try {
                    restoreSession(account, token)
                } catch (e: java.util.concurrent.CancellationException) {
                    // 用户手动登录时会把恢复流程取消，这时别去覆盖登录流程正在显示的状态。
                    Log.i(TAG, "restore session cancelled")
                } catch (t: Throwable) {
                    Log.w(TAG, "restore session failed", t)
                    isLoggedIn = false
                    status = "未登录"
                    notifyState()
                }
            }
        }
    }

    fun isAvailable(): Boolean = isLoggedIn && client?.isConnected == true

    internal fun steamClient(): SteamClient? = client
    internal fun appsOrThrow(): SteamApps = apps ?: throw IllegalStateException("Steam 正在连接，请稍后重试")
    internal fun contentOrThrow(): SteamContent = content ?: throw IllegalStateException("Steam 正在连接，请稍后重试")

    // ---------------- 登录 ----------------

    /** 供 Java/Bridge 调用的异步登录入口。 */
    @JvmStatic
    fun loginAsync(username: String, password: String) {
        scope.launch { login(username, password) }
    }

    /**
     * 用户名密码登录（含 Guard 等待）。返回是否成功。
     *
     * @param forceCode 跳过「手机确认」，直接要验证码。UI 点「改用验证码」后由 [login] 内部重试时传 true。
     */
    suspend fun login(username: String, password: String, forceCode: Boolean = false): Boolean {
        if (!claimLogin()) return false
        // 启动时的「恢复会话」会和手动登录抢同一条连接和 logonCallback，
        // 必须先把它停干净：cancel 只是发信号，join 才能保证它不再碰 client。
        restoreJob?.let { job ->
            job.cancel()
            runCatching { job.join() }
        }
        restoreJob = null
        error = ""
        guardNeeded = false
        forceCodeLogin = forceCode
        codeLoginRequested = false
        codeFallbackAvailable = false
        try {
            withContext(Dispatchers.IO) {
                status = "正在连接 Steam…"
                notifyState()
                ensureClient()
                client?.disconnect()
                delay(400)
                connectAndWait()

                status = "正在验证账号…"
                notifyState()
                val details = AuthSessionDetails().apply {
                    this.username = username.trim()
                    this.password = password
                    this.persistentSession = true
                    this.deviceFriendlyName = "WE Workshop Android"
                    this.clientOSType = EOSType.AndroidUnknown
                    this.authenticator = GuardAuthenticator()
                    // Steam 在上一次登录后会给一段 guardData（JWT），带上它能少触发一次 2FA。
                    // 以前没存，导致每次换设备/换网络都要重新验证。
                    this.guardData = prefs?.getString("guard_data", null)
                }
                val authSession = client!!.authentication
                    .beginAuthSessionViaCredentials(details)
                    .get(60, TimeUnit.SECONDS)
                // 把 Steam 允许的验证方式写进日志：手机确认 / 手机令牌 / 邮箱，决定后面走哪条路。
                val kinds = authSession.allowedConfirmations.map {
                    it.confirmationType.name.removePrefix("k_EAuthSessionGuardType_")
                }
                codeFallbackAvailable = authSession.allowedConfirmations.any {
                    it.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode ||
                        it.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                }
                fileLog("allowedConfirmations=[${kinds.joinToString(",")}] codeFallback=$codeFallbackAvailable")

                status = "等待 Steam 验证…"
                notifyState()

                // 「手机确认」自己轮询（原因见 pollDeviceConfirmationWithReconnect 的注释）；
                // 验证码分支仍交给 javaSteam，因为提交验证码的接口没有对外开放。
                // wantCode 额外要求 codeFallbackAvailable：账号根本没有验证码可退时，
                // 就算 forceCode 也要老老实实回到手机确认，否则只会重建一次会话再失败。
                val wantCode = forceCodeLogin && codeFallbackAvailable
                val isDeviceConfirm = !wantCode &&
                    authSession.allowedConfirmations.firstOrNull()?.confirmationType ==
                    EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
                val poll: AuthPollResult = if (isDeviceConfirm) {
                    requestDeviceConfirmation()
                    pollDeviceConfirmationWithReconnect(authSession, System.currentTimeMillis() + 600_000L)
                } else {
                    val pf = authSession.pollingWaitForResult()
                    pollFuture = pf
                    try {
                        pf.get(600, TimeUnit.SECONDS)
                    } finally {
                        pollFuture = null
                    }
                }
                // guardData 会随登录下发，存下来减少下次 2FA。
                poll.newGuardData?.takeIf { it.isNotBlank() }?.let {
                    prefs?.edit()?.putString("guard_data", it)?.apply()
                }
                val token = poll.refreshToken.orEmpty()
                val account = poll.accountName?.takeIf { it.isNotBlank() } ?: username.trim()
                if (token.isBlank()) throw IllegalStateException("Steam 未返回登录令牌")

                status = "正在登录 Steam…"
                notifyState()
                logOnWithToken(account, token)
                accountName = account
                refreshToken = token
                prefs?.edit()?.putString("refresh_token", token)?.putString("account_name", account)?.apply()
                isLoggedIn = true
                // 登录成功就撤掉「等待确认」的界面状态，别把 guard 标记留给下一次。
                guardNeeded = false
                guardType = ""
                status = "已登录：$account"
                notifyState()
            }
            return true
        } catch (t: Throwable) {
            // 用户点了「改用验证码」：当前这轮是被我们主动取消的，不是真失败，直接带 forceCode 重来。
            if (codeLoginRequested) {
                codeLoginRequested = false
                pollFuture = null
                busy = false
                fileLog("switch to code login, retrying")
                return login(username, password, forceCode = true)
            }
            Log.e(TAG, "login failed", t)
            fileLog("login failed: ${describeChain(t)}")
            isLoggedIn = false
            error = friendlyError(t)
            status = error
            notifyState()
            return false
        } finally {
            busy = false
        }
    }

    /**
     * 抢占登录权：连点两次登录时只放行第一个，避免同时发起两个认证会话
     * （手机上会收到两条确认请求，批错一条就会被服务端判成 Cancelled）。
     */
    @Synchronized
    private fun claimLogin(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    /**
     * 自己轮询「手机确认」结果，断线后重连继续等。
     *
     * 为什么不用 javaSteam 的 pollingWaitForResult()：
     * 它的 poll 请求挂在 AsyncJobManager 上，而 SteamClient.onClientDisconnected() 会调
     * jobManager.cancelPendingJobs()，AsyncJobSingle.setFailed(false) 的实现是
     * future.cancel(true)。也就是说等待手机确认期间只要断一次网（切到手机 App、锁屏、
     * VPN 抖动、心跳超时），正在等待的 poll 就抛 CancellationException，
     * 被 friendlyError 翻成「登录已取消」——用户其实什么都没取消，
     * Steam 服务端也仍然留着这个 requestID，手机上批准后本可以拿到 token。
     *
     * 这里改成：断线 → 重连 → 用同一个 authSession 继续 poll。
     * requestID 存在服务端，重连后照常生效，手机上的批准不会白费。
     */
    private suspend fun pollDeviceConfirmationWithReconnect(
        authSession: AuthSession,
        deadlineMs: Long,
    ): AuthPollResult {
        // pollingInterval 单位是秒（Steam 一般下发 5.0），javaSteam 内部直接 delay(toLong())
        // 当毫秒用，实际变成了 5ms 一次；这里按秒换算，并夹在 1~10s 之间。
        val intervalMs = (authSession.pollingInterval * 1000f).toLong().coerceIn(1_000L, 10_000L)
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadlineMs) {
            if (codeLoginRequested) throw java.util.concurrent.CancellationException("switch to code login")
            try {
                if (client?.isConnected != true) {
                    status = "网络断开，正在重连 Steam…（连上后会接着等手机确认）"
                    notifyState()
                    fileLog("poll: client disconnected, reconnecting")
                    connectAndWait()
                    status = "请在手机 Steam App 中确认本次登录"
                    notifyState()
                    fileLog("poll: reconnected, resume polling")
                }
                val f = authSession.pollAuthSessionStatus()
                pollFuture = f
                val result = try {
                    f.get(45, TimeUnit.SECONDS)
                } finally {
                    pollFuture = null
                }
                if (result != null) return result
            } catch (e: Throwable) {
                // 用户点了「改用验证码」：交回 login() 的 catch 分支去重试。
                if (codeLoginRequested) throw e
                val root = rootCause(e)
                // 会话真的废了（过期 / 被撤销 / 用户拒绝）：重试只是白等。
                if (root is AuthenticationException && isTerminalPollResult(root.result)) throw root
                lastError = root
                fileLog("poll retry: ${describeChain(e)}")
            }
            if (codeLoginRequested) throw java.util.concurrent.CancellationException("switch to code login")
            delay(intervalMs)
        }
        throw java.util.concurrent.TimeoutException(
            "Steam 验证超时" + (lastError?.message?.let { "（最后一次错误：$it）" } ?: "")
        )
    }

    /** poll 返回这些结果时说明会话已经作废，继续重试只会白等。 */
    private fun isTerminalPollResult(result: EResult?): Boolean = when (result) {
        EResult.Expired,
        EResult.FileNotFound,
        EResult.Revoked,
        EResult.Cancelled,
        EResult.AccessDenied,
        EResult.Fail -> true
        else -> false
    }

    /** 取异常链最深处的那一个：.get() 抛的 ExecutionException 会把真正原因包两层。 */
    private fun rootCause(t: Throwable): Throwable {
        var cur = t
        var guard = 0
        while (guard < 6) {
            val next = cur.cause ?: break
            if (next === cur) break
            cur = next
            guard++
        }
        return cur
    }

    private suspend fun restoreSession(account: String, token: String) {
        withContext(Dispatchers.IO) {
            ensureClient()
            connectAndWait()
            status = "正在恢复 Steam 会话…"
            notifyState()
            logOnWithToken(account, token)
            isLoggedIn = true
            status = "已登录：$account"
            notifyState()
            Log.i(TAG, "session restored: $account")
        }
    }

    /** 网络切换/断线后自动用保存的 refreshToken 重连，避免要求用户重新输密码。 */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if (refreshToken.isBlank() || accountName.isBlank()) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive && attempt < 10) {
                attempt++
                if (busy) { delay(1_000L); continue }  // 用户正在手动登录时让路
                status = "网络断开，正在重连 Steam…（第 $attempt 次）"
                notifyState()
                delay(minOf(20_000L, 1_000L * attempt))
                try {
                    if (client?.isConnected != true) {
                        ensureClient()
                        connectAndWait()
                    }
                    logOnWithToken(accountName, refreshToken)
                    isLoggedIn = true
                    error = ""
                    status = "已登录：$accountName"
                    notifyState()
                    Log.i(TAG, "reconnected on attempt $attempt")
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "reconnect attempt $attempt failed: ${t.message}")
                    runCatching { client?.disconnect() }
                    delay(500)
                }
            }
            isLoggedIn = false
            status = "Steam 连接已断开，请检查网络后重试"
            notifyState()
        }
    }

    @JvmStatic
    fun submitGuardCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        val f = guardFuture ?: return
        guardFuture = null
        guardNeeded = false
        fileLog("submit guard code: $trimmed")
        status = "正在验证 Steam Guard…"
        notifyState()
        f.complete(trimmed)
    }

    /**
     * 用户点「改用验证码」：中断正在等待的手机确认，带验证码重新走一遍登录。
     *
     * 取消 poll future 会让 login() 里的 .get() 抛 CancellationException，
     * catch 分支看到 codeLoginRequested 就知道这是主动切换，于是带上 forceCode 重试。
     */
    @JvmStatic
    fun useCodeLogin() {
        if (!guardNeeded || guardType != "device_confirm") return
        // 账号只允许手机确认时，切验证码等于重建会话再撞回同一堵墙，还可能让手机上的
        // 确认请求失效。直接说明原因，不打断当前等待。
        if (!codeFallbackAvailable) {
            fileLog("code login unavailable: only device confirmation allowed")
            status = "该账号只支持手机 App 确认：请打开手机上的 Steam App 点「允许」"
            error = ""
            notifyState()
            return
        }
        codeLoginRequested = true
        guardNeeded = false
        fileLog("user chose code login over device confirmation")
        status = "正在改用验证码登录…"
        notifyState()
        pollFuture?.cancel(true)
    }

    @JvmStatic
    fun logout() {
        scope.launch {
            runCatching { user?.logOff() }
            delay(300)
            runCatching { client?.disconnect() }
            isLoggedIn = false
            steamId64 = 0L
            accountName = ""
            refreshToken = ""
            reconnectJob?.cancel()
            // 主动登出说明用户可能要换账号：清干净令牌与登录中间态，别让上一次的 forceCode 串到下一次。
            guardNeeded = false
            guardType = ""
            forceCodeLogin = false
            codeLoginRequested = false
            codeFallbackAvailable = false
            pollFuture = null
            prefs?.edit()?.remove("refresh_token")?.remove("account_name")?.remove("guard_data")?.apply()
            status = "未登录"
            notifyState()
        }
    }

    /** 登录态摘要（给 JS 用 JSON 文本） */
    @JvmStatic
    fun stateJson(): String = buildString {
        append('{')
        append("\"loggedIn\":").append(isLoggedIn).append(',')
        append("\"busy\":").append(busy).append(',')
        append("\"guard\":").append(guardNeeded).append(',')
        append("\"guardType\":\"").append(guardType.replace("\"", "")).append("\",")
        append("\"guardEmail\":\"").append(guardEmail.replace("\"", "")).append("\",")
        append("\"codeFallback\":").append(codeFallbackAvailable).append(',')
        append("\"account\":\"").append(accountName.replace("\"", "\\\"")).append("\",")
        append("\"status\":\"").append(status.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",")
        append("\"error\":\"").append(error.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        append('}')
    }

    /** 登录/网络诊断：代理设置、系统代理、VPN 状态、连接与登录状态。 */
    @JvmStatic
    fun diagnosticsJson(): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")

        val proxySetting = appContext?.getSharedPreferences("weworkshop", Context.MODE_PRIVATE)
            ?.getString("proxy", "")?.trim().orEmpty()

        var sysProxy = "读取失败"
        try {
            val ps = java.net.ProxySelector.getDefault()
            val p = ps?.select(java.net.URI("https://steamcommunity.com"))?.firstOrNull()
            sysProxy = when {
                p == null -> "无（直连）"
                p.type() == java.net.Proxy.Type.DIRECT -> "无（直连）"
                else -> p.type().name + " " + p.address().toString()
            }
        } catch (t: Throwable) {
            sysProxy = "读取失败"
        }

        var vpn = "未知"
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm?.getNetworkCapabilities(net) else null
            vpn = when {
                caps == null -> "无法读取网络状态"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN 已连接（可接管全部流量）"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi（未检测到系统 VPN）"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据（未检测到系统 VPN）"
                else -> "其他网络（未检测到 VPN）"
            }
        } catch (t: Throwable) {
            vpn = "读取失败"
        }

        return buildString {
            append('{')
            append("\"proxy\":\"").append(esc(if (proxySetting.isEmpty()) "直连（未设置）" else proxySetting)).append("\",")
            append("\"systemProxy\":\"").append(esc(sysProxy)).append("\",")
            append("\"vpn\":\"").append(esc(vpn)).append("\",")
            append("\"connected\":").append(client?.isConnected == true).append(',')
            append("\"loggedIn\":").append(isLoggedIn).append(',')
            append("\"account\":\"").append(esc(accountName)).append("\",")
            append("\"status\":\"").append(esc(status)).append("\",")
            append("\"error\":\"").append(esc(error)).append('"')
            append('}')
        }
    }

    // ---------------- 内部 ----------------

    private fun notifyState() {
        stateListener?.run()
    }

    private fun notifyGuard() {
        guardListener?.run()
    }

    internal fun requestGuard(kind: String, email: String): CompletableFuture<String> {
        guardType = kind
        guardEmail = email
        guardNeeded = true
        val f = CompletableFuture<String>()
        guardFuture = f
        notifyGuard()
        notifyState()
        return f
    }

    /**
     * Steam 允许「手机确认」时由 javaSteam 调用。
     *
     * 关键：返回 true 后 javaSteam 会进入无限轮询（pollDeviceConfirmation 的 while(true)），
     * 不会再有别的回调。必须在这一刻让 UI 知道「去手机上点确认」，否则用户只会看到一个
     * 一直不变的「等待 Steam 验证…」。
     *
     * 用户体验过「没用」而点了「改用验证码」时 [forceCodeLogin] 为 true，这里直接拒绝，
     * 让 javaSteam 降级到 allowedConfirmations[1]（DeviceCode），由 UI 收 5 位令牌码。
     */
    internal fun requestDeviceConfirmation(): CompletableFuture<Boolean> {
        guardType = "device_confirm"
        guardEmail = ""
        guardNeeded = true
        status = "请在手机 Steam App 中确认本次登录"
        fileLog("device confirmation requested")
        notifyGuard()
        notifyState()
        return CompletableFuture.completedFuture(true)
    }

    /** javaSteam 在决定「等手机确认」还是「降级到验证码」前会调一次，用来读当前意图。 */
    internal fun forceCodeLoginActive(): Boolean = forceCodeLogin

    // ---------------- 创意工坊查询（与 Steam 客户端一致的数据源） ----------------

    /**
     * 通过 Steam 统一消息调用 PublishedFileService.QueryFiles。
     *
     * Wallpaper Engine 客户端浏览创意工坊时走的就是这套 Steam 客户端 API；
     * 网页匿名抓取（WorkshopParser.parseBrowse）会被 Steam 过滤掉部分条目，
     * 因此登录后优先使用本方法。
     */
    @JvmStatic
    fun queryPublishedFiles(queryJson: String): String {
        try {
            return queryPublishedFilesInner(queryJson)
        } catch (t: Throwable) {
            Log.e(TAG, "queryPublishedFiles failed", t)
            throw t
        }
    }

    private fun queryPublishedFilesInner(queryJson: String): String {
        val c = client ?: throw IllegalStateException("Steam 尚未连接")
        if (!isLoggedIn) throw IllegalStateException("请先在设置中登录 Steam")
        if (!c.isConnected) throw IllegalStateException("Steam 连接已断开，请稍后重试")

        val q = JSONObject(queryJson)
        val queryType = q.optInt("queryType", EPublishedFileQueryType.RankedByTrend.code())
        val page = q.optInt("page", 1).coerceAtLeast(1)
        val numPerPage = q.optInt("numPerPage", 30).coerceIn(1, 50)
        val search = q.optString("search", "")
        val tagsJson = q.optJSONArray("tags") ?: JSONArray()

        val builder = CPublishedFile_QueryFiles_Request.newBuilder()
            .setQueryType(queryType)
            .setPage(page)
            .setNumperpage(numPerPage)
            .setAppid(431960)
            .setFiletype(0)
            .setReturnTags(true)
            .setReturnPreviews(true)
            .setReturnShortDescription(true)
            .setReturnVoteData(true)
            .setReturnMetadata(true)
            // 多选标签取并集（OR）：命中任意一个标签即可，符合用户期望的『并集』语义
            .setMatchAllTags(true)
        for (i in 0 until tagsJson.length()) {
            val t = tagsJson.optString(i, "")
            if (t.isNotBlank()) builder.addRequiredtags(t)
        }
        if (search.isNotBlank()) builder.setSearchText(search)

        val unified = c.getHandler(SteamUnifiedMessages::class.java)
            ?: throw IllegalStateException("Steam 客户端不支持统一消息")
        val service = unified.createService(PublishedFile::class.java)
        val resp = service.queryFiles(builder.build()).toFuture().get(90, TimeUnit.SECONDS)
        if (resp.result != EResult.OK) {
            throw IllegalStateException("工坊查询失败：${resp.result}")
        }
        val body = resp.body.build()
        val items = JSONArray()
        val details = body.publishedfiledetailsList
        var di = 0
        while (di < details.size) {
            val d = details[di]
            di++
            if (d.result != EResult.OK.code()) continue
            val previewUrl: String = d.previewUrl
            val imageUrl: String = d.imageUrl
            val img: String = if (previewUrl.length > 0) previewUrl else imageUrl
            val shortDesc: String = d.shortDescription
            val fullDesc: String = d.fileDescription
            val desc: String = if (shortDesc.length > 0) shortDesc else fullDesc

            // 字段名与网页详情接口（GetPublishedFileDetails）保持一致，前端可通用渲染
            val o = JSONObject()
            o.put("id", d.publishedfileid.toString())
            o.put("img", img)
            o.put("preview_url", img)
            o.put("title", d.title)
            o.put("author", "")
            o.put("creator", d.creator.toString())
            o.put("description", desc)
            o.put("subscriptions", d.subscriptions)
            o.put("favorited", d.favorited)
            o.put("views", d.views)
            o.put("time_created", d.timeCreated)
            o.put("time_updated", d.timeUpdated)
            o.put("file_size", d.fileSize)
            o.put("hcontent_file", d.hcontentFile.toString())
            val tags = JSONArray()
            val tagList = d.tagsList
            var ti = 0
            while (ti < tagList.size) {
                val tagName: String = tagList[ti].tag
                val tagObj = JSONObject()
                tagObj.put("name", tagName)
                tagObj.put("display", tagName)
                tags.put(tagObj)
                ti++
            }
            o.put("tags", tags)
            items.put(o)
        }
        val out = JSONObject()
        out.put("ok", true)
        out.put("items", items)
        out.put("total", body.total)
        return out.toString()
    }

    /** 把诊断写进 App 外部私有目录日志（logcat 在部分 ROM 不可读）。 */
    internal fun fileLog(msg: String) {
        runCatching {
            val dir = appContext?.getExternalFilesDir(null) ?: return
            java.io.File(dir, "weworkshop.log").appendText(
                "[" + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "] " + msg + "\n"
            )
        }
    }

    /** 读取用户在设置里保存的 HTTP 代理（host:port），没有则返回 null。 */
    private fun savedProxy(): java.net.Proxy? {
        val raw = appContext?.getSharedPreferences("weworkshop", Context.MODE_PRIVATE)
            ?.getString("proxy", "")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val idx = raw.lastIndexOf(':')
        if (idx <= 0 || idx == raw.length - 1) return null
        val host = raw.substring(0, idx)
        val port = raw.substring(idx + 1).toIntOrNull() ?: return null
        return java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
    }

    @Synchronized
    private fun ensureClient(): SteamClient {
        client?.let { return it }
        // javaSteam 用 OkHttp 下载 CDN 内容，不会读系统代理，必须显式配置，
        // 否则手机在代理/VPN 环境下 CDN 请求直连会 403。
        val proxy = savedProxy()
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            // javaSteam 的 CDN manifest/chunk 下载走这个 client，挂同一个 hosts DNS，
            // 让 Steam CDN 域名也能用上测速优选出来的边缘 IP。
            .dns(com.prototypejust.weworkshop.HostsAccelerator.dns())
            .apply { if (proxy != null) proxy(proxy) }
            .addInterceptor { chain ->
                val req = chain.request()
                val resp = chain.proceed(req)
                if (req.url.encodedPath.contains("/depot/")) {
                    fileLog("CDN ${resp.code} proxy=${proxy ?: "direct"} ${req.url}")
                }
                resp
            }
            .build()
        Log.i(TAG, "steam okhttp proxy: ${proxy ?: "direct"}")
        fileLog("steam client init, proxy=${proxy ?: "direct"}")
        val configuration = SteamConfiguration.create { builder ->
            builder.withConnectionTimeout(15_000L)
            builder.withHttpClient(http)
        }
        val c = SteamClient(configuration)
        val cm = CallbackManager(c)
        client = c
        callbackManager = cm
        user = c.getHandler(SteamUser::class.java)
        apps = c.getHandler(SteamApps::class.java)
        content = c.getHandler(SteamContent::class.java)
        cm.subscribe(ConnectedCallback::class.java) { Log.i(TAG, "steam connected") }
        cm.subscribe(DisconnectedCallback::class.java) {
            Log.i(TAG, "steam disconnected (loggedIn=$isLoggedIn, hasToken=${refreshToken.isNotBlank()})")
            if (isLoggedIn) {
                isLoggedIn = false
                status = "网络断开，正在重连 Steam…"
                notifyState()
                scheduleReconnect()
            }
        }
        cm.subscribe(LoggedOnCallback::class.java) { cb -> logonCallback = cb }
        scope.launch {
            while (true) {
                runCatching { cm.runWaitCallbacks(1000L) }
                    .onFailure { Log.e(TAG, "callback loop error", it) }
                delay(10)
            }
        }
        return c
    }

    private suspend fun connectAndWait() {
        val c = ensureClient()
        if (c.isConnected) return
        c.connect()
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!c.isConnected) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException(
                    "连接 Steam 网络超时。" +
                        "Steam 登录走的是长连接（WebSocket/TCP），不受「 App 内 HTTP 代理」设置控制；" +
                        "请开启 VPN 的 TUN/全局模式，或在 VPN 分流规则里把「WE 工坊」加入代理。"
                )
            }
            delay(200)
        }
    }

    private suspend fun logOnWithToken(account: String, token: String) {
        logonCallback = null
        val u = user ?: throw IllegalStateException("SteamUser 未就绪")
        u.logOn(LogOnDetails().apply {
            this.username = account
            this.accessToken = token
            this.shouldRememberPassword = true
            this.loginID = (System.nanoTime() and 0x3FFFFFFF).toInt().coerceAtLeast(1)
            this.machineName = "WE Workshop Android"
            this.chatMode = ChatMode.NEW_STEAM_CHAT
        })

        val deadline = System.currentTimeMillis() + LOGON_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val cb = logonCallback
            if (cb != null) {
                logonCallback = null
                when (cb.result) {
                    EResult.OK -> {
                        steamId64 = cb.clientSteamID?.convertToUInt64() ?: 0L
                        return
                    }
                    EResult.InvalidPassword -> throw IllegalStateException("Steam 账号或密码错误")
                    EResult.AccountLogonDenied -> throw IllegalStateException("需要 Steam Guard 验证码，请重新登录")
                    EResult.AccessDenied -> throw IllegalStateException("Steam 拒绝登录（AccessDenied）")
                    EResult.LoggedInElsewhere -> throw IllegalStateException("账号已在其他设备登录，请先退出")
                    EResult.RateLimitExceeded -> throw IllegalStateException("登录尝试过于频繁，请稍后再试")
                    else -> throw IllegalStateException("Steam 登录失败：${cb.result}")
                }
            }
            delay(200)
        }
        throw IllegalStateException("Steam 登录超时")
    }

    /**
     * 把异常链拍平成一行，写日志用。
     *
     * javaSteam 的 .get() 抛的是 ExecutionException，真正原因埋在 cause 里；
     * 只记外层 message 会得到「java.lang.Exception: xxx」这种看不出所以然的东西。
     */
    private fun describeChain(t: Throwable): String {
        val parts = ArrayList<String>()
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard < 6) {
            parts.add(cur.javaClass.simpleName + (cur.message?.let { ": $it" } ?: ""))
            cur = cur.cause
            guard++
        }
        return parts.joinToString(" <- ")
    }

    private fun friendlyError(t: Throwable): String {
        val chain = ArrayList<Throwable>()
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard < 6) {
            chain.add(cur)
            cur = cur.cause
            guard++
        }
        val msg = chain.joinToString(" / ") { it.message ?: "" }
        val timedOut = chain.any { it is java.util.concurrent.TimeoutException }
        val cancelled = chain.any { it is java.util.concurrent.CancellationException }

        return when {
            timedOut ->
                "Steam 验证超时（10 分钟没等到确认）。若手机没弹出确认框，请重试并点「改用验证码登录」。"
            cancelled ->
                "登录已中断：等待确认时与 Steam 的连接断开了。请重试，期间尽量让 App 留在前台。"
            msg.contains("Cancelled", true) ->
                "登录请求在手机上被取消了。请重新登录，并在 Steam App 里点「允许」"
            msg.contains("Expired", true) || msg.contains("Revoked", true) ->
                "这次登录请求已过期，请重新点登录"
            msg.contains("InvalidPassword", true) || msg.contains("InvalidLoginAuthCode", true) ->
                "Steam 账号或密码错误"
            msg.contains("TwoFactorCodeMismatch", true) ->
                "令牌验证码不正确，请输入手机 Steam App 上当前的 5 位码"
            msg.contains("AccountLoginDeniedNeedTwoFactor", true) -> "该账号需要 Steam Guard 令牌验证码"
            msg.contains("AccountLogonDenied", true) -> "需要邮箱验证码"
            msg.contains("RateLimitExceeded", true) -> "登录尝试过于频繁，请过几分钟再试"
            msg.contains("AccessDenied", true) -> "Steam 拒绝了本次登录（AccessDenied）"
            msg.contains("no other confirmation types", true) ->
                "该账号只允许手机确认登录，请在手机 Steam App 中操作"
            msg.contains("connect", true) || msg.contains("UnknownHost", true) ||
                msg.contains("SocketTimeout", true) ->
                "连接 Steam 失败：$msg"
            msg.isBlank() -> "登录失败（${t.javaClass.simpleName}，详见日志）"
            else -> msg
        }
    }
}

/** Steam Guard 验证码输入：把异步 future 交给 UI，等待用户输入。 */
private class GuardAuthenticator : IAuthenticator {
    override fun getDeviceCode(prior: Boolean): CompletableFuture<String> =
        SteamService.requestGuard("device", "")

    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        SteamService.requestGuard("email", email.orEmpty())

    override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
        // 用户明确要求改用验证码：拒绝，让 javaSteam 降级到 allowedConfirmations[1]（DeviceCode）。
        if (SteamService.forceCodeLoginActive()) {
            return CompletableFuture.completedFuture(false)
        }
        return SteamService.requestDeviceConfirmation()
    }
}
