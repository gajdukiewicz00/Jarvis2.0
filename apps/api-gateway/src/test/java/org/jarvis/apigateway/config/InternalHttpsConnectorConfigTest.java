package org.jarvis.apigateway.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InternalHttpsConnectorConfigTest {

    private final InternalHttpsConnectorConfig config = new InternalHttpsConnectorConfig();

    @Test
    void customizerBeanThrowsWhenKeyStoreIsMissing() {
        assertThatThrownBy(() -> config.internalHttpsConnectorCustomizer(8443, "", "changeit", "PKCS12", "alias"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-store");
    }

    @Test
    void customizerBeanThrowsWhenKeyStorePasswordIsMissing() {
        assertThatThrownBy(() -> config.internalHttpsConnectorCustomizer(8443, "/certs/ks.p12", "  ", "PKCS12", "alias"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-store-password");
    }

    @Test
    void customizerBeanAddsAdditionalConnectorToFactory() {
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer =
                config.internalHttpsConnectorCustomizer(8443, "/certs/ks.p12", "changeit", "PKCS12", "custom-alias");
        TomcatServletWebServerFactory factory = mock(TomcatServletWebServerFactory.class);

        customizer.customize(factory);

        verify(factory).addAdditionalTomcatConnectors(org.mockito.ArgumentMatchers.any(Connector.class));
    }

    @Test
    void buildInternalHttpsConnectorConfiguresSchemeAndSslCertificate() {
        Connector connector = InternalHttpsConnectorConfig.buildInternalHttpsConnector(
                8443, "/certs/ks.p12", "changeit", "PKCS12", "custom-alias");

        assertThat(connector.getScheme()).isEqualTo("https");
        assertThat(connector.getSecure()).isTrue();
        assertThat(connector.getPort()).isEqualTo(8443);

        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        assertThat(protocol.isSSLEnabled()).isTrue();

        SSLHostConfig[] sslHostConfigs = connector.findSslHostConfigs();
        assertThat(sslHostConfigs).hasSize(1);
        assertThat(sslHostConfigs[0].getHostName()).isEqualTo("_default_");

        SSLHostConfigCertificate certificate = sslHostConfigs[0].getCertificates().iterator().next();
        assertThat(certificate.getCertificateKeystoreFile()).isEqualTo("/certs/ks.p12");
        assertThat(certificate.getCertificateKeystorePassword()).isEqualTo("changeit");
        assertThat(certificate.getCertificateKeystoreType()).isEqualTo("PKCS12");
        assertThat(certificate.getCertificateKeyAlias()).isEqualTo("custom-alias");
    }

    @Test
    void buildInternalHttpsConnectorSkipsAliasWhenBlank() {
        Connector connector = InternalHttpsConnectorConfig.buildInternalHttpsConnector(
                8443, "/certs/ks.p12", "changeit", "PKCS12", "  ");

        SSLHostConfig sslHostConfig = connector.findSslHostConfigs()[0];
        SSLHostConfigCertificate certificate = sslHostConfig.getCertificates().iterator().next();
        assertThat(certificate.getCertificateKeyAlias()).isNull();
    }
}
