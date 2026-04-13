package org.jarvis.desktop.controller

import org.jarvis.desktop.config.ResolvedDesktopConfig
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object ServerConnectionErrorFormatter {
    fun format(config: ResolvedDesktopConfig, throwable: Throwable): String {
        val chain = generateSequence(throwable) { it.cause }.toList()
        val endpoint = config.apiGatewayBaseUrl
        val manualHint = if (config.usesManualEndpointOverride) {
            " Используется ручной endpoint из настроек."
        } else {
            ""
        }

        return when {
            chain.any { it is ConnectException } ->
                "Не удалось подключиться к серверу $endpoint.$manualHint Сервер недоступен или endpoint устарел."

            chain.any { it is UnknownHostException } ->
                "Не удалось найти сервер $endpoint.$manualHint Проверьте URL endpoint и DNS."

            chain.any { it is SocketTimeoutException } ->
                "Сервер $endpoint не ответил вовремя.$manualHint"

            chain.any { it is SSLException } ->
                "Не удалось установить защищённое соединение с $endpoint.$manualHint Проверьте TLS/сертификат и truststore."

            else -> {
                val detail = chain.firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotBlank) }
                if (detail != null) {
                    "Ошибка подключения к серверу $endpoint: $detail"
                } else {
                    val errorType = chain.firstOrNull()?.javaClass?.simpleName?.takeIf { it.isNotBlank() } ?: "UnknownError"
                    "Не удалось подключиться к серверу $endpoint.$manualHint Тип ошибки: $errorType."
                }
            }
        }
    }
}
