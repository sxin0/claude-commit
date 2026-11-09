package com.github.claudecommit

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.AttributeKey
import org.jetbrains.ide.HttpRequestHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/** FullHttpRequest is auto-released by the IDE immediately after process returns. Never capture it. */
internal class TerminalIconHttpHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean {
        val connection = TerminalIconApi.getInstance().current() ?: return false
        return request.uri() == connection.path
    }

    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        if (context.channel().attr(IN_FLIGHT).getAndSet(true) == true) {
            context.close()
            return true
        }
        fun reject(status: Int, code: String, message: String): Boolean {
            respond(context, status, TerminalIconApi.error(code = code, message = message))
            return true
        }
        val remote = context.channel().remoteAddress() as? InetSocketAddress
        if (remote?.address?.isLoopbackAddress != true || request.headers().contains("Forwarded") ||
            request.headers().contains("X-Forwarded-For") || request.headers().contains(HttpHeaderNames.ORIGIN)) {
            return reject(403, "forbidden", "Only direct, non-browser loopback requests are supported")
        }
        val api = TerminalIconApi.getInstance()
        val connection = api.current() ?: return reject(503, "disabled", "API is disabled")
        if (!api.authorize(connection, request.headers().get(HttpHeaderNames.AUTHORIZATION))) return reject(401, "unauthorized", "Invalid bearer credential")
        if (request.method() != HttpMethod.POST) return reject(405, "invalid_request", "POST is required")
        if (request.headers().get(HttpHeaderNames.CONTENT_TYPE)?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            return reject(415, "invalid_request", "application/json is required")
        }
        if (!request.decoderResult().isSuccess || request.content().readableBytes() > TerminalIconProtocol.MAX_BODY_BYTES) {
            return reject(413, "invalid_request", "Invalid or oversized request")
        }
        // Copy while on the channel event loop. No request or ByteBuf is retained by workers.
        val body = ByteArray(request.content().readableBytes())
        request.content().getBytes(request.content().readerIndex(), body)
        val cancel = api.submit(connection, body, { context.channel().isActive }) { status, response ->
            context.executor().execute { respond(context, status, response) }
        }
        val timeout = context.executor().schedule({ cancel() }, 30, TimeUnit.SECONDS)
        context.channel().closeFuture().addListener {
            timeout.cancel(false)
            cancel()
        }
        return true
    }

    private fun respond(context: ChannelHandlerContext, status: Int, body: String) {
        if (!context.channel().isActive) return
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
        response.headers().set("X-Content-Type-Options", "nosniff")
        HttpUtil.setContentLength(response, bytes.size.toLong())
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    companion object {
        private val IN_FLIGHT = AttributeKey.valueOf<Boolean>("claude-commit.terminal-icon.in-flight")
    }
}
