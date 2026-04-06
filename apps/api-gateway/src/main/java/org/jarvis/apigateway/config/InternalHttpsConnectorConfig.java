package org.jarvis.apigateway.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class InternalHttpsConnectorConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jarvis.gateway.internal-https", name = "enabled", havingValue = "true")
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalHttpsConnectorCustomizer(
            @Value("${jarvis.gateway.internal-https.port}") int port,
            @Value("${jarvis.gateway.internal-https.key-store:}") String keyStore,
            @Value("${jarvis.gateway.internal-https.key-store-password:}") String keyStorePassword,
            @Value("${jarvis.gateway.internal-https.key-store-type:PKCS12}") String keyStoreType,
            @Value("${jarvis.gateway.internal-https.key-alias:api-gateway-internal}") String keyAlias) {

        if (keyStore == null || keyStore.isBlank()) {
            throw new IllegalStateException("jarvis.gateway.internal-https.key-store is required when internal HTTPS is enabled");
        }
        if (keyStorePassword == null || keyStorePassword.isBlank()) {
            throw new IllegalStateException("jarvis.gateway.internal-https.key-store-password is required when internal HTTPS is enabled");
        }

        return factory -> {
            log.info("Enabling internal HTTPS listener for api-gateway on port {}", port);
            factory.addAdditionalTomcatConnectors(buildInternalHttpsConnector(
                    port,
                    keyStore,
                    keyStorePassword,
                    keyStoreType,
                    keyAlias));
        };
    }

    static Connector buildInternalHttpsConnector(
            int port,
            String keyStore,
            String keyStorePassword,
            String keyStoreType,
            String keyAlias) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();

        connector.setScheme("https");
        connector.setSecure(true);
        connector.setPort(port);

        protocol.setSSLEnabled(true);
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setHostName("_default_");
        sslHostConfig.setSslProtocol("TLS");

        SSLHostConfigCertificate certificate =
                new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateKeystoreFile(keyStore);
        certificate.setCertificateKeystorePassword(keyStorePassword);
        certificate.setCertificateKeystoreType(keyStoreType);
        if (keyAlias != null && !keyAlias.isBlank()) {
            certificate.setCertificateKeyAlias(keyAlias);
        }

        sslHostConfig.addCertificate(certificate);
        connector.addSslHostConfig(sslHostConfig);

        return connector;
    }
}
