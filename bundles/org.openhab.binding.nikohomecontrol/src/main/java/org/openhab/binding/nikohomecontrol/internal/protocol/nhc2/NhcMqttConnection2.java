/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.nikohomecontrol.internal.protocol.nhc2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.io.transport.mqtt.MqttActionCallback;
import org.eclipse.smarthome.io.transport.mqtt.MqttBrokerConnection;
import org.eclipse.smarthome.io.transport.mqtt.MqttConnectionObserver;
import org.eclipse.smarthome.io.transport.mqtt.MqttConnectionState;
import org.eclipse.smarthome.io.transport.mqtt.MqttException;
import org.eclipse.smarthome.io.transport.mqtt.MqttMessageSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NhcMqttConnection2} manages the MQTT connections to the Connected Controller. The initial secured connection
 * is used for general system communication. This communication also communicates the profile uuid's needed as username
 * for touch profile specific communication. The touch profile specific communication uses the same secure communication
 * with added username and password. It allows receiving state information about specific devices and sending updates to
 * specific devices.
 *
 * @author Mark Herwege - Initial Contribution
 */
@NonNullByDefault
public class NhcMqttConnection2 implements MqttActionCallback {

    private final Logger logger = LoggerFactory.getLogger(NhcMqttConnection2.class);

    private volatile @Nullable MqttBrokerConnection mqttPublicConnection;
    private volatile @Nullable MqttBrokerConnection mqttProfileConnection;

    private volatile @Nullable CompletableFuture<Boolean> publicSubscribedFuture;
    private volatile @Nullable CompletableFuture<Boolean> profileSubscribedFuture;

    private volatile @Nullable CompletableFuture<Boolean> publicStoppedFuture;
    private volatile @Nullable CompletableFuture<Boolean> profileStoppedFuture;

    private MqttMessageSubscriber messageSubscriber;
    private MqttConnectionObserver connectionObserver;

    private Path persistenceBasePath;
    private TrustManager trustManagers[];
    private @Nullable String clientId;

    private volatile String cocoAddress = "";
    private volatile int port;

    NhcMqttConnection2(String clientId, String persistencePath, MqttMessageSubscriber messageSubscriber,
            MqttConnectionObserver connectionObserver) throws CertificateException {
        persistenceBasePath = Paths.get(persistencePath).resolve("nikohomecontrol");
        trustManagers = getTrustManagers();
        this.clientId = clientId;
        this.messageSubscriber = messageSubscriber;
        this.connectionObserver = connectionObserver;
    }

    private TrustManager[] getTrustManagers() throws CertificateException {
        ResourceBundle certificatesBundle = ResourceBundle.getBundle("nikohomecontrol/certificates");

        try {
            // Load server public certificates into key store
            CertificateFactory cf = CertificateFactory.getInstance("X509");
            InputStream certificateStream;
            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            for (String certName : certificatesBundle.keySet()) {
                certificateStream = new ByteArrayInputStream(
                        certificatesBundle.getString(certName).getBytes(StandardCharsets.UTF_8));
                X509Certificate certificate = (X509Certificate) cf.generateCertificate(certificateStream);
                keyStore.setCertificateEntry(certName, certificate);
            }

            ResourceBundle.clearCache();

            // Create trust managers used to validate server
            TrustManagerFactory tmFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmFactory.init(keyStore);
            return tmFactory.getTrustManagers();
        } catch (CertificateException | KeyStoreException | NoSuchAlgorithmException | IOException e) {
            logger.warn("Niko Home Control: error with SSL context creation: {} ", e.getMessage());
            throw new CertificateException("SSL context creation exception", e);
        } finally {
            ResourceBundle.clearCache();
        }
    }

    /**
     * Start a secure MQTT connection and subscribe to all topics. This is the general connection, not touch profile
     * specific.
     *
     * @param subscriber MqttMessageSubscriber that will handle received messages
     * @param cocoAddress IP Address of the Niko Connected Controller
     * @param port Port for MQTT communication with the Niko Connected Controller
     * @throws MqttException
     */
    synchronized void startPublicConnection(String cocoAddress, int port) throws MqttException {
        CompletableFuture<Boolean> future = publicStoppedFuture;
        if (future != null) {
            try {
                future.get(5000, TimeUnit.MILLISECONDS);
                logger.debug("Niko Home Control: finished stopping public connection");
            } catch (InterruptedException | ExecutionException | TimeoutException ignore) {
                logger.debug("Niko Home Control: error stopping public connection");
            }
            publicStoppedFuture = null;
        }

        logger.debug("Niko Home Control: starting public connection...");
        this.cocoAddress = cocoAddress;
        this.port = port;
        String clientId = this.clientId + "-public";
        MqttBrokerConnection connection = createMqttConnection(null, null, clientId);
        connection.addConnectionObserver(connectionObserver);
        mqttPublicConnection = connection;
        try {
            if (connection.start().get(5000, TimeUnit.MILLISECONDS)) {
                if (publicSubscribedFuture == null) {
                    publicSubscribedFuture = connection.subscribe("#", messageSubscriber);
                }
            } else {
                logger.debug("Niko Home Control: error connecting");
                throw new MqttException(32103);
            }
        } catch (InterruptedException e) {
            logger.debug("Niko Home Control: public connection interrupted exception");
            throw new MqttException(0);
        } catch (ExecutionException e) {
            logger.debug("Niko Home Control: public connection execution exception", e.getCause());
            throw new MqttException(32103);
        } catch (TimeoutException e) {
            logger.debug("Niko Home Control: public connection timeout exception");
            throw new MqttException(32000);
        }
    }

    /**
     * Start a secure MQTT connection and subscribe to all topics. This is the touch profile specific connection.
     * Note that {@link startConnection} must be called before this method. This method does not have cocoAddress and
     * port as parameters. The class fields will already have been set by {@link startConnection}.
     *
     * @param subscriber MqttMessageSubscriber that will handle received messages
     * @param username MQTT username that identifies the specific touch profile. It should be the uuid retrieved from
     *            the profile list in the general communication that matches the touch profile name.
     * @param password Password for the touch profile
     * @throws MqttException
     */
    synchronized void startProfileConnection(String username, String password) throws MqttException {
        CompletableFuture<Boolean> future = profileStoppedFuture;
        if (future != null) {
            try {
                future.get(5000, TimeUnit.MILLISECONDS);
                logger.debug("Niko Home Control: finished stopping profile connection");
            } catch (InterruptedException | ExecutionException | TimeoutException ignore) {
                logger.debug("Niko Home Control: error stopping profile connection");
            }
            profileStoppedFuture = null;
        }

        if (isProfileConnected()) {
            logger.debug("Niko Home Control: profile already connected, no need to connect again");
            return;
        }

        logger.debug("Niko Home Control: starting profile connection...");
        String clientId = this.clientId + "-profile";
        MqttBrokerConnection connection = createMqttConnection(username, password, clientId);
        connection.addConnectionObserver(connectionObserver);
        mqttProfileConnection = connection;
        try {
            if (connection.start().get(5000, TimeUnit.MILLISECONDS)) {
                if (profileSubscribedFuture == null) {
                    profileSubscribedFuture = connection.subscribe("#", messageSubscriber);
                }
            } else {
                logger.warn("Niko Home Control: error with profile password");
                throw new MqttException(4);
            }
        } catch (InterruptedException e) {
            logger.debug("Niko Home Control: profile connection interrupted exception ");
            throw new MqttException(0);
        } catch (ExecutionException e) {
            logger.debug("Niko Home Control: profile connection execution exception", e.getCause());
            throw new MqttException(32103);
        } catch (TimeoutException e) {
            logger.debug("Niko Home Control: public connection timeout exception");
            throw new MqttException(32000);
        }
    }

    private MqttBrokerConnection createMqttConnection(@Nullable String username, @Nullable String password,
            @Nullable String clientId) throws MqttException {
        Path persistencePath = persistenceBasePath.resolve(clientId);
        MqttBrokerConnection connection = new MqttBrokerConnection(cocoAddress, port, true, clientId);
        connection.setPersistencePath(persistencePath);
        connection.setTrustManagers(trustManagers);
        connection.setCredentials(username, password);
        connection.setQos(1);
        return connection;
    }

    /**
     * Stop the public MQTT connection.
     */
    void stopPublicConnection() {
        logger.debug("Niko Home Control: stopping public connection...");
        MqttBrokerConnection connection = mqttPublicConnection;
        if (connection != null) {
            connection.removeConnectionObserver(connectionObserver);
        }
        publicStoppedFuture = stopConnection(connection);
        mqttPublicConnection = null;

        CompletableFuture<Boolean> future = publicSubscribedFuture;
        if (future != null) {
            future.complete(false);
            publicSubscribedFuture = null;
        }
    }

    /**
     * Stop the profile specific MQTT connection.
     */
    void stopProfileConnection() {
        logger.debug("Niko Home Control: stopping profile connection...");
        MqttBrokerConnection connection = mqttProfileConnection;
        if (connection != null) {
            connection.removeConnectionObserver(connectionObserver);
        }
        profileStoppedFuture = stopConnection(mqttProfileConnection);
        mqttProfileConnection = null;

        CompletableFuture<Boolean> future = profileSubscribedFuture;
        if (future != null) {
            future.complete(false);
            profileSubscribedFuture = null;
        }
    }

    private CompletableFuture<Boolean> stopConnection(@Nullable MqttBrokerConnection connection) {
        if (connection != null) {
            return connection.stop();
        } else {
            return CompletableFuture.completedFuture(true);
        }
    }

    /**
     * @return true if connection established and subscribed to all topics
     */
    private boolean isPublicConnected() {
        return isConnected(mqttPublicConnection, publicSubscribedFuture);
    }

    /**
     * @return true if touch profile specific connection is established and subscribed to all topics
     */
    private boolean isProfileConnected() {
        return isConnected(mqttProfileConnection, profileSubscribedFuture);
    }

    private boolean isConnected(@Nullable MqttBrokerConnection brokerConnection,
            @Nullable CompletableFuture<Boolean> completableFuture) {
        MqttBrokerConnection connection = brokerConnection;
        CompletableFuture<Boolean> future = completableFuture;

        if (connection != null) {
            try {
                if ((future != null) && future.get(5000, TimeUnit.MILLISECONDS)) {
                    MqttConnectionState state = connection.connectionState();
                    logger.debug("Niko Home Control: connection state {} for {}", state, connection.getClientId());
                    return state == MqttConnectionState.CONNECTED;
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Publish a message on the general connection.
     *
     * @param topic
     * @param payload
     * @throws MqttException
     */
    void publicConnectionPublish(String topic, String payload) throws MqttException {
        MqttBrokerConnection connection = mqttPublicConnection;
        if (connection == null) {
            logger.debug("Niko Home Control: cannot publish, no public connection");
            throw new MqttException(32104);
        }

        if (isPublicConnected()) {
            publish(connection, topic, payload);
        } else {
            logger.debug("Niko Home Control: cannot publish, not subscribed to public connection messages");
        }
    }

    /**
     * Publish a message on the touch profile specific connection.
     *
     * @param topic
     * @param payload
     * @throws MqttException
     */
    void profileConnectionPublish(String topic, String payload) throws MqttException {
        MqttBrokerConnection connection = mqttProfileConnection;
        if (connection == null) {
            logger.debug("Niko Home Control: cannot publish, no profile connection");
            throw new MqttException(32104);
        }

        if (isProfileConnected()) {
            publish(connection, topic, payload);
        } else {
            logger.debug("Niko Home Control: cannot publish, not subscribed to profile connection messages");
        }
    }

    private void publish(MqttBrokerConnection connection, String topic, String payload) {
        logger.debug("Niko Home Control: publish {}, {}", topic, payload);
        connection.publish(topic, payload.getBytes());
    }

    @Override
    public void onSuccess(String topic) {
        logger.debug("Niko Home Control: publish succeeded {}", topic);
    }

    @Override
    public void onFailure(String topic, Throwable error) {
        logger.debug("Niko Home Control: publish failed {}, {}", topic, error.getMessage(), error);
    }
}
