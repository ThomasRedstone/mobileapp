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
import coredevices.util.transcription.CactusModelPathProvider
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
    // No local Cactus STT/LM model support on desktop yet (see docs/ubuntu-touch-poc-plan.md).
    // A binding is still needed: several commonMain call sites do a plain get() rather than
    // getOrNull(), which would otherwise throw NoDefinitionFoundException during composition.
    single<CactusModelPathProvider> {
        object : CactusModelPathProvider {
            override suspend fun getSTTModelPath(): String = throw IllegalStateException("Cactus models not supported on desktop")
            override suspend fun getLMModelPath(): String = throw IllegalStateException("Cactus models not supported on desktop")
            override fun isModelDownloaded(modelName: String): Boolean = false
            override fun getDownloadedModels(): List<String> = emptyList()
            override fun getIncompatibleModels(): List<String> = emptyList()
            override fun deleteModel(modelName: String) {}
            override fun getModelSizeBytes(modelName: String): Long = 0L
            override fun initTelemetry() {}
        }
    }
}
