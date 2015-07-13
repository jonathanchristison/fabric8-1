/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.kubernetes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.cfg.Annotations;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import io.fabric8.kubernetes.api.extensions.Configs;
import io.fabric8.kubernetes.api.model.AuthInfo;
import io.fabric8.kubernetes.api.model.Cluster;
import io.fabric8.kubernetes.api.model.Config;
import io.fabric8.kubernetes.api.model.Context;
import io.fabric8.utils.Strings;
import io.fabric8.utils.Systems;
import io.fabric8.utils.cxf.AuthorizationHeaderFilter;
import io.fabric8.utils.cxf.WebClients;
import org.apache.cxf.jaxrs.client.JAXRSClientFactory;
import org.apache.cxf.jaxrs.client.WebClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple helper class for creating instances of Kubernetes
 */
public class KubernetesFactory {
    public static final String KUBERNETES_SCHEMA_JSON = "schema/kube-schema.json";
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String KUBERNETES_TRUST_ALL_CERIFICATES = "KUBERNETES_TRUST_CERT";
    public static final String KUBERNETES_DISABLE_HOSTNAME_CHECK = "KUBERNETES_DISABLE_HOSTNAME_CHECK";

    public static final String KUBERNETES_SERVICE_HOST_ENV_VAR = "KUBERNETES_SERVICE_HOST";
    public static final String KUBERNETES_SERVICE_PORT_ENV_VAR = "KUBERNETES_SERVICE_PORT";
    public static final String KUBERNETES_MASTER_ENV_VAR = "KUBERNETES_MASTER";
    public static final String KUBERNETES_CA_CERTIFICATE_FILE_ENV_VAR = "KUBERNETES_CA_CERTIFICATE_FILE";
    public static final String KUBERNETES_CLIENT_CERTIFICATE_FILE_ENV_VAR = "KUBERNETES_CLIENT_CERTIFICATE_FILE";
    public static final String KUBERNETES_CLIENT_KEY_FILE_ENV_VAR = "KUBERNETES_CLIENT_KEY_FILE";
    public static final String KUBERNETES_CLIENT_KEY_DATA_ENV_VAR = "KUBERNETES_CLIENT_KEY_DATA";
    public static final String KUBERNETES_CA_CERTIFICATE_DATA_ENV_VAR = "KUBERNETES_CA_CERTIFICATE_DATA";
    public static final String KUBERNETES_CLIENT_CERTIFICATE_DATA_ENV_VAR = "KUBERNETES_CLIENT_CERTIFICATE_DATA";
    public static final String KUBERNETES_CLIENT_KEY_ALGO_ENV_VAR = "KUBERNETES_CLIENT_KEY_ALGO";
    public static final String KUBERNETES_CLIENT_KEY_PASSWORD_ENV_VAR = "KUBERNETES_CLIENT_KEY_PASSWORD";
    public static final String KUBERNETES_MASTER_SYSTEM_PROPERTY = "kubernetes.master";
    public static final String KUBERNETES_VERIFY_SYSTEM_PROPERTY = "kubernetes.verify";

    private String address;
    private boolean verifyAddress = true;
    private boolean trustAllCerts = false;
    private boolean disableHostNameChecks = false;

    private File caCertFile;
    private File clientCertFile;
    private File clientKeyFile;
    private String caCertData;
    private String clientCertData;
    private String clientKeyData;
    private String clientKeyAlgo = "RSA";
    private char[] clientKeyPassword = "changeit".toCharArray();
    private String username;
    private String password;

    public KubernetesFactory() {
        this(null);
    }

    public KubernetesFactory(String address) {
        this(address, Boolean.parseBoolean(System.getProperty(KUBERNETES_VERIFY_SYSTEM_PROPERTY, "true")));
    }

    public KubernetesFactory(String address, boolean verifyAddress) {
        this.verifyAddress = verifyAddress;
        init(address);
    }

    protected String findKubernetesMaster() {
        return resolveHttpKubernetesMaster();
    }

    private void init(String address) {
        if (Strings.isNullOrBlank(address)) {
            String kubeAddress = findKubernetesMaster();
            if (kubeAddress != null) {
                setAddress(kubeAddress);
            } else {
                configureFromKubeConfig();
            }
        } else {
            setAddress(address);
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_TRUST_ALL_CERIFICATES)) {
            this.trustAllCerts = Systems.getEnvVarOrSystemProperty(KUBERNETES_TRUST_ALL_CERIFICATES, false);
        } else if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CA_CERTIFICATE_FILE_ENV_VAR)) {
            File candidateCaCertFile = new File(Systems.getEnvVarOrSystemProperty(KUBERNETES_CA_CERTIFICATE_FILE_ENV_VAR));
            if (candidateCaCertFile.exists() && candidateCaCertFile.canRead()) {
                this.caCertFile = candidateCaCertFile;
            } else {
                log.error("Specified CA certificate file {} does not exist or is not readable", candidateCaCertFile);
            }
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_DISABLE_HOSTNAME_CHECK)) {
            this.disableHostNameChecks = Systems.getEnvVarOrSystemProperty(KUBERNETES_DISABLE_HOSTNAME_CHECK, false);
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CA_CERTIFICATE_DATA_ENV_VAR)) {
            this.caCertData = Systems.getEnvVarOrSystemProperty(KUBERNETES_CA_CERTIFICATE_DATA_ENV_VAR);
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CLIENT_CERTIFICATE_FILE_ENV_VAR)) {
            File candidateClientCertFile = new File(Systems.getEnvVarOrSystemProperty(KUBERNETES_CLIENT_CERTIFICATE_FILE_ENV_VAR));
            if (candidateClientCertFile.exists() && candidateClientCertFile.canRead()) {
                this.clientCertFile = candidateClientCertFile;
            } else {
                log.error("Specified client certificate file {} does not exist or is not readable", candidateClientCertFile);
            }
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CLIENT_CERTIFICATE_DATA_ENV_VAR)) {
            this.clientCertData = Systems.getEnvVarOrSystemProperty(KUBERNETES_CLIENT_CERTIFICATE_DATA_ENV_VAR);
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_FILE_ENV_VAR)) {
            File candidateClientKeyFile = new File(Systems.getEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_FILE_ENV_VAR));
            if (candidateClientKeyFile.exists() && candidateClientKeyFile.canRead()) {
                this.clientKeyFile = candidateClientKeyFile;
            } else {
                log.error("Specified client key file {} does not exist or is not readable", candidateClientKeyFile);
            }
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_DATA_ENV_VAR)) {
            this.clientKeyData = Systems.getEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_DATA_ENV_VAR);
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_ALGO_ENV_VAR)) {
            this.clientKeyAlgo = Systems.getEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_ALGO_ENV_VAR);
        }

        if (Systems.hasEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_PASSWORD_ENV_VAR)) {
            this.clientKeyPassword = Systems.getEnvVarOrSystemProperty(KUBERNETES_CLIENT_KEY_PASSWORD_ENV_VAR).toCharArray();
        }
    }

    private void configureFromKubeConfig() {
        Config kubeConfig = Configs.parseConfigs();

        if (kubeConfig != null) {
            Context currentContext = Configs.getCurrentContext(kubeConfig);
            if (currentContext != null) {
                Cluster currentCluster = Configs.getCluster(kubeConfig, currentContext);
                if (currentCluster.getServer() != null) {
                    setAddress(currentCluster.getServer());
                    if (currentCluster.getInsecureSkipTlsVerify() != null) {
                        this.trustAllCerts = currentCluster.getInsecureSkipTlsVerify();
                    }
                    if (currentCluster.getCertificateAuthority() != null) {
                        File candidateCaCertFile = new File(currentCluster.getCertificateAuthority());
                        if (candidateCaCertFile.exists() && candidateCaCertFile.canRead()) {
                            this.caCertFile = candidateCaCertFile;
                        } else {
                            log.error("Specified CA certificate file {} does not exist or is not readable", candidateCaCertFile);
                        }
                    }

                    if (currentCluster.getCertificateAuthorityData() != null) {
                        this.caCertData = currentCluster.getCertificateAuthorityData();
                    }

                }

                AuthInfo authInfo = Configs.getUserAuthInfo(kubeConfig, currentContext);
                if (authInfo != null) {
                    if (authInfo.getClientCertificate() != null) {
                        File candidateClientCertFile = new File(authInfo.getClientCertificate());
                        if (candidateClientCertFile.exists() && candidateClientCertFile.canRead()) {
                            this.clientCertFile = candidateClientCertFile;
                        } else {
                            log.error("Specified client certificate file {} does not exist or is not readable", candidateClientCertFile);
                        }
                    }

                    if (authInfo.getClientCertificateData() != null) {
                        this.clientCertData = authInfo.getClientCertificateData();
                    }

                    if (authInfo.getClientKey() != null) {
                        File candidateClientKeyFile = new File(authInfo.getClientKey());
                        if (candidateClientKeyFile.exists() && candidateClientKeyFile.canRead()) {
                            this.clientKeyFile = candidateClientKeyFile;
                        } else {
                            log.error("Specified client key file {} does not exist or is not readable", candidateClientKeyFile);
                        }
                    }

                    if (authInfo.getClientKeyData() != null) {
                        this.clientKeyData = authInfo.getClientKeyData();
                    }

                    if (authInfo.getUsername() != null) {
                        this.username = authInfo.getUsername();
                    }

                    if (authInfo.getPassword() != null) {
                        this.password = authInfo.getPassword();
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "KubernetesFactory{" + address + '}';
    }

    public Kubernetes createKubernetes() {
        return createWebClient(Kubernetes.class);
    }

    public KubernetesExtensions createKubernetesExtensions() {
        return createWebClient(KubernetesExtensions.class);
    }

    public KubernetesGlobalExtensions createKubernetesGlobalExtensions() {
        return createWebClient(KubernetesGlobalExtensions.class);
    }

    /**
     * Creates a JAXRS web client for the given JAXRS client
     */
    public <T> T createWebClient(Class<T> clientType) {
        WebClient webClient = createWebClient();
        return JAXRSClientFactory.fromClient(webClient, clientType);
    }

    public WebClient createWebClient() {
        return createWebClient(address);
    }

    public WebClient createWebClient(String serviceAddress) {
        List<Object> providers = createProviders();

        AuthorizationHeaderFilter authorizationHeaderFilter = new AuthorizationHeaderFilter();
        providers.add(authorizationHeaderFilter);

        WebClient webClient = WebClient.create(serviceAddress, providers);
        WebClients.configureUserAndPassword(webClient, this.username, this.password);
        boolean registeredCert = false;
        if (trustAllCerts) {
            WebClients.disableSslChecks(webClient);
        } else if (caCertFile != null || caCertData != null) {
            WebClients.configureCaCert(webClient, this.caCertData, this.caCertFile);

            // had host verification errors - this should avoid it
            if (disableHostNameChecks) {
                WebClients.disableHostNameChecks(webClient);
            }
        }
        if ((clientCertFile != null || clientCertData != null) && (clientKeyFile != null || clientKeyData != null)) {
            WebClients.configureClientCert(webClient, this.clientCertData, this.clientCertFile, this.clientKeyData, this.clientKeyFile, this.clientKeyAlgo, this.clientKeyPassword);
            registeredCert = true;
        }
        if (!registeredCert) {
            String token = findToken();
            if (Strings.isNotBlank(token)) {
                String authHeader = "Bearer " + token;
                authorizationHeaderFilter.setAuthorizationHeader(authHeader);
            }
        }
        return webClient;
    }

    public WebSocketClient createWebSocketClient() throws Exception {
        SslContextFactory sslContextFactory = null;
        if (trustAllCerts) {
            sslContextFactory = new SslContextFactory(trustAllCerts);
        } else if (caCertData != null || caCertFile != null) {
            KeyStore trustStore = WebClients.createTrustStore(caCertData, caCertFile);
            sslContextFactory = new SslContextFactory();
            sslContextFactory.setTrustStore(trustStore);
        }
        if ((clientCertFile != null || clientCertData != null) && (clientKeyFile != null || clientKeyData != null)) {
            if (sslContextFactory == null) {
                sslContextFactory = new SslContextFactory();
            }
            KeyStore keyStore = WebClients.createKeyStore(this.clientCertData, this.clientCertFile, this.clientKeyData, this.clientKeyFile, this.clientKeyAlgo, this.clientKeyPassword);
            sslContextFactory.setKeyStore(keyStore);
            sslContextFactory.setKeyStorePassword(new String(this.clientKeyPassword));
            sslContextFactory.setKeyManagerPassword(new String(this.clientKeyPassword));
        }

        sslContextFactory.setIncludeProtocols("TLSv1", "TLSv1.1", "TLSv1.2");

        WebSocketClient client = new WebSocketClient(sslContextFactory);

        return client;
    }

    public String findToken() {
        String token = getServiceAccountToken();
        if (Strings.isNotBlank(token)) {
            return token;
        }
        return findOpenShiftToken();
    }

    public String getServiceAccountToken() {
        try {
            return new String(Files.readAllBytes(Paths.get(Kubernetes.SERVICE_ACCOUNT_TOKEN_FILE)));
        } catch (IOException e) {
            log.debug("Cannot read service account token");
        }
        return null;
    }

    public String findOpenShiftToken() {
        Config config = Configs.parseConfigs();
        if (config != null) {
            Context context = Configs.getCurrentContext(config);
            if (context != null) {
                return Configs.getUserToken(config, context);
            }
        }
        return null;
    }

    protected List<Object> createProviders() {
        List<Object> providers = new ArrayList<Object>();
        Annotations[] annotationsToUse = JacksonJaxbJsonProvider.DEFAULT_ANNOTATIONS;
        ObjectMapper objectMapper = createObjectMapper();
        providers.add(new JacksonJaxbJsonProvider(objectMapper, annotationsToUse));
        providers.add(new PlainTextJacksonProvider(objectMapper, annotationsToUse));
        providers.add(new ExceptionResponseMapper());
        //providers.add(new JacksonIntOrStringConfig(objectMapper));
        return providers;
    }


    /**
     * Lets accept plain text too as if its JSON to work around some issues with the REST API and remote kube....
     */
    @javax.ws.rs.ext.Provider
    @javax.ws.rs.Consumes({"text/plain"})
    @javax.ws.rs.Produces({"text/plain"})
    public static class PlainTextJacksonProvider extends JacksonJaxbJsonProvider {
        public PlainTextJacksonProvider(ObjectMapper mapper, Annotations[] annotationsToUse) {
            super(mapper, annotationsToUse);
        }

        @Override
        protected boolean hasMatchingMediaType(MediaType mediaType) {
            boolean answer = super.hasMatchingMediaType(mediaType);
            String type = mediaType.getType();
            String subtype = mediaType.getSubtype();
            if (!answer && type.equals("text")) {
                answer = super.hasMatchingMediaType(MediaType.APPLICATION_JSON_TYPE);
            }
            return answer;
        }
    }

    public String getKubernetesMaster() {
        String answer = address;
        int idx = answer.lastIndexOf(":");
        if (idx > 0) {
            answer = answer.substring(0, idx);
        }
        idx = answer.lastIndexOf(":");
        if (idx > 0) {
            answer = answer.substring(idx + 1);
        }
        idx = answer.lastIndexOf("/");
        if (idx > 0) {
            answer = answer.substring(idx + 1);
        }
        return answer;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
        if (Strings.isNullOrBlank(address)) {
            this.address = findKubernetesMaster();
        }

        if (verifyAddress) {
            try {
                URL url = new URL(this.address);
                if (KubernetesHelper.isServiceSsl(url.getHost(), url.getPort(), true)) {
                    this.address = "https://" + url.getHost() + ":" + url.getPort();
                } else {
                    this.address = "http://" + url.getHost() + ":" + url.getPort();
                }
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid kubernetes master address", e);
            }
        }
    }

    // Helpers

    public static String resolveHttpKubernetesMaster() {
        String kubernetesMaster = resolveKubernetesMaster();
        if (kubernetesMaster != null && kubernetesMaster.startsWith("tcp:")) {
            return "https:" + kubernetesMaster.substring(4);
        }
        return kubernetesMaster;
    }

    public static String resolveKubernetesMaster() {
        String hostEnvVar = KUBERNETES_SERVICE_HOST_ENV_VAR;
        String portEnvVar = KUBERNETES_SERVICE_PORT_ENV_VAR;
        String proto = "https";

        // First let's check if it's available as a kubernetes service like it should be...
        String kubernetesMaster = System.getenv(hostEnvVar);
        if (Strings.isNotBlank(kubernetesMaster)) {
            kubernetesMaster = proto + "://" + kubernetesMaster + ":" + System.getenv(portEnvVar);
        } else {
            // If not then fall back to KUBERNETES_MASTER env var
            kubernetesMaster = Systems.getSystemPropertyOrEnvVar(KUBERNETES_MASTER_SYSTEM_PROPERTY, KUBERNETES_MASTER_ENV_VAR, null);
        }
        return kubernetesMaster;
    }

    /**
     * Creates a configured Jackson object mapper for parsing JSON
     */
    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}
