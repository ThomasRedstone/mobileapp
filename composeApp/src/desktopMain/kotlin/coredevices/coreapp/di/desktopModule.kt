package coredevices.coreapp.di

import CoreAppVersion
import PlatformContext
import PlatformShareLauncher
import coredevices.analytics.AnalyticsBackend
import coredevices.coreapp.auth.RealAppleAuthUtil
import coredevices.coreapp.auth.RealGithubAuthUtil
import coredevices.coreapp.auth.RealGoogleAuthUtil
import coredevices.util.CommonBuildKonfig
import coredevices.util.CompanionDevice
import coredevices.util.PermissionRequester
import coredevices.util.Platform
import coredevices.util.RequiredPermissions
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.auth.GitHubAuthUtil
import coredevices.util.auth.GoogleAuthUtil
import coredevices.util.auth.SilentSignIn
import coredevices.util.integrations.OAuthLauncher
import coredevices.util.models.ModelDownloadManager
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.flowOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.toJavaDuration

val desktopModule = module {
    singleOf(::RealGoogleAuthUtil) binds arrayOf(GoogleAuthUtil::class, SilentSignIn::class)
    singleOf(::RealAppleAuthUtil) bind AppleAuthUtil::class
    singleOf(::RealGithubAuthUtil) bind GitHubAuthUtil::class
    factory { params ->
        OkHttp.create {
            config {
                readTimeout(params.get<Duration>().toJavaDuration())
            }
        }
    } bind HttpClientEngine::class
    singleOf(::PlatformShareLauncher)
    singleOf(::DesktopPlatform) bind Platform::class
    single<OAuthLauncher> { DesktopOAuthLauncher }
    single { CoreAppVersion(CommonBuildKonfig.GIT_HASH) }
    singleOf(::PlatformContext)
    singleOf(::DesktopPermissionRequester) bind PermissionRequester::class
    singleOf(::DesktopCompanionDevice) bind CompanionDevice::class
    single { RequiredPermissions(flowOf(emptySet())) }
    single<AnalyticsBackend> { DesktopAnalytics }
    singleOf(::ModelDownloadManager)
}
