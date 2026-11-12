package io.github.damienmiheev.monobank.installments

import java.net.Authenticator
import java.net.ConnectException
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/** An [HttpClient] that records each request's URI and refuses the connection, as if the host were down. */
class RefusingHttpClient : HttpClient() {
    val uris = mutableListOf<URI>()

    override fun <T> send(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        uris += request.uri()
        throw ConnectException("Connection refused")
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()
}
