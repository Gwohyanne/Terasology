/*
 * Copyright 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.network.internal;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.config.Config;
import org.terasology.identity.ClientIdentity;
import org.terasology.identity.IdentityConstants;
import org.terasology.identity.PrivateIdentityCertificate;
import org.terasology.identity.PublicIdentityCertificate;
import org.terasology.identity.storageServiceClient.StorageServiceWorker;
import org.terasology.identity.storageServiceClient.StorageServiceWorkerStatus;
import org.terasology.protobuf.NetData;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Authentication handler for the client end of the authentication handshake.
 */
public class ClientHandshakeHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandshakeHandler.class);
    private static final String AUTHENTICATION_FAILURE = "Authentication failure";

    private Config config;
    private JoinStatusImpl joinStatus;
    private StorageServiceWorker storageServiceWorker;

    private byte[] serverRandom;
    private byte[] clientRandom;
    private byte[] masterSecret;
    private NetData.HandshakeHello serverHello;
    private NetData.HandshakeHello clientHello;

    private boolean requestedCertificate;
    private ClientIdentity identity;
    private PublicIdentityCertificate serverCertificate;

    public ClientHandshakeHandler(Config config, StorageServiceWorker storageServiceWorker, JoinStatusImpl joinStatus) {
        this.config = config;
        this.storageServiceWorker = storageServiceWorker;
        this.joinStatus = joinStatus;
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        super.channelOpen(ctx, e);
        joinStatus.setCurrentActivity("Authenticating with server");
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
        NetData.NetMessage message = (NetData.NetMessage) e.getMessage();
        if (message.hasHandshakeHello()) {
            processServerHello(message.getHandshakeHello(), ctx);
        } else if (message.hasProvisionIdentity()) {
            processNewIdentity(message.getProvisionIdentity(), ctx);
        } else if (message.hasHandshakeVerification()) {
            processHandshakeVerification(message.getHandshakeVerification(), ctx);
        }
    }

    /**
     * Process the handshake verification, checking that both the server and client have attempted it. If successful marks the channel as Authenticated.
     * @param handshakeVerification
     * @param ctx Channel Handler Context.
     */
    private void processHandshakeVerification(NetData.HandshakeVerification handshakeVerification, ChannelHandlerContext ctx) {
        logger.info("Received server verification");
        if (serverHello == null || clientHello == null) {
            logger.error("Received server verification without requesting it: cancelling authentication");
            joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
            ctx.getChannel().close();
            return;
        }

        if (!serverCertificate.verify(HandshakeCommon.getSignatureData(serverHello, clientHello), handshakeVerification.getSignature().toByteArray())) {
            logger.error("Server failed verification: cancelling authentication");
            joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
            ctx.getChannel().close();
            return;
        }

        // And we're authenticated.
        ctx.getPipeline().remove(this);
        channelAuthenticated(ctx);
    }

    /**
     * Generates a new secret key for a user and then decrypts the certificate into a byte array. Storing the certificate to the user ID.
     * @param provisionIdentity
     * @param ctx Channel Handler Context.
     */
    private void processNewIdentity(NetData.ProvisionIdentity provisionIdentity, ChannelHandlerContext ctx) {
        logger.info("Received identity from server");
        if (!requestedCertificate) {
            logger.error("Received identity without requesting it: cancelling authentication");
            joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
            ctx.getChannel().close();
            return;
        }

        try {
            byte[] decryptedCert = null;
            try {
                SecretKeySpec key = HandshakeCommon.generateSymmetricKey(masterSecret, clientRandom, serverRandom);
                Cipher cipher = Cipher.getInstance(IdentityConstants.SYMMETRIC_ENCRYPTION_ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, key);
                decryptedCert = cipher.doFinal(provisionIdentity.getEncryptedCertificates().toByteArray());
            } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
                logger.error("Unexpected error decrypting received certificate, ending connection attempt", e);
                joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
                ctx.getChannel().close();
                return;
            }

            NetData.CertificateSet certificateSet = NetData.CertificateSet.parseFrom(decryptedCert);
            NetData.Certificate publicCertData = certificateSet.getPublicCertificate();

            PublicIdentityCertificate publicCert = NetMessageUtil.convert(publicCertData);

            if (!publicCert.verifySignedBy(serverCertificate)) {
                logger.error("Received invalid certificate, not signed by server: cancelling authentication");
                joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
                ctx.getChannel().close();
                return;
            }

            BigInteger exponent = new BigInteger(certificateSet.getPrivateExponent().toByteArray());
            PrivateIdentityCertificate privateCert = new PrivateIdentityCertificate(publicCert.getModulus(), exponent);

            // Store identity for later use
            identity = new ClientIdentity(publicCert, privateCert);
            config.getSecurity().addIdentity(serverCertificate, identity);
            config.save();

            //Try to upload the new identity to the identity storage service (if user is logged in)
            if (storageServiceWorker != null && storageServiceWorker.getStatus() == StorageServiceWorkerStatus.LOGGED_IN) {
                storageServiceWorker.putIdentity(serverCertificate, identity);
            }

            // And we're authenticated.
            ctx.getPipeline().remove(this);
            channelAuthenticated(ctx);
        } catch (InvalidProtocolBufferException e) {
            logger.error("Received invalid certificate data: cancelling authentication", e);
            joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
            ctx.getChannel().close();
        }
    }

    /**
     * Creates a new builder on the channel and sets join status.
     * @param ctx Channel Handler Context.
     */
    private void channelAuthenticated(ChannelHandlerContext ctx) {
        ctx.getChannel().write(NetData.NetMessage.newBuilder()
                .setServerInfoRequest(NetData.ServerInfoRequest.newBuilder()).build());
        joinStatus.setCurrentActivity("Requesting server info");
    }

    /**
     * Client checks to see if it received the server hello message, if so it processes it into a random key and the certificate.
     * @param helloMessage Message from server to client.
     * @param ctx Channel Handler Context.
     */
    private void processServerHello(NetData.HandshakeHello helloMessage, ChannelHandlerContext ctx) {
        if (serverHello == null) {
            logger.info("Received Server Hello");
            serverHello = helloMessage;
            serverRandom = helloMessage.getRandom().toByteArray();
            NetData.Certificate cert = helloMessage.getCertificate();
            serverCertificate = NetMessageUtil.convert(cert);

            if (!serverCertificate.verifySelfSigned()) {
                logger.error("Received invalid server certificate: cancelling authentication");
                joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
                ctx.getChannel().close();
                return;
            }

            clientRandom = new byte[IdentityConstants.SERVER_CLIENT_RANDOM_LENGTH];

            identity = config.getSecurity().getIdentity(serverCertificate);
            if (identity == null) {
                requestIdentity(ctx);
            } else {
                sendCertificate(helloMessage, ctx);
            }

        } else {
            logger.error("Received multiple hello messages from server: cancelling authentication");
            joinStatus.setErrorMessage(AUTHENTICATION_FAILURE);
            ctx.getChannel().close();
        }

    }

    /**
     * Generates a client hello from clientRandom file, time, and the public client certificate. Sends the clients hello and certificate back to the server via network channel.
     * @param helloMessage Message from server to client.
     * @param ctx Channel Handler Context.
     */
    private void sendCertificate(NetData.HandshakeHello helloMessage, ChannelHandlerContext ctx) {
        logger.info("Sending client certificate");
        PublicIdentityCertificate pubClientCert = identity.getPlayerPublicCertificate();

        clientHello = NetData.HandshakeHello.newBuilder()
                .setRandom(ByteString.copyFrom(clientRandom))
                .setCertificate(NetMessageUtil.convert(pubClientCert))
                .setTimestamp(System.currentTimeMillis())
                .build();

        byte[] dataToSign = Bytes.concat(helloMessage.toByteArray(), clientHello.toByteArray());
        byte[] signature = identity.getPlayerPrivateCertificate().sign(dataToSign);

        ctx.getChannel().write(NetData.NetMessage.newBuilder()
                .setHandshakeHello(clientHello)
                .setHandshakeVerification(NetData.HandshakeVerification.newBuilder()
                        .setSignature(ByteString.copyFrom(signature)))
                .build());
    }

    /**
     * Requests a new identity for a client if one doesn't already exist. Generated from the master secret key and server certificate.
     * @param ctx Channel Handler Context.
     */
    private void requestIdentity(ChannelHandlerContext ctx) {
        logger.info("No existing identity, requesting one");

        byte[] preMasterSecret = new byte[IdentityConstants.PREMASTER_SECRET_LENGTH];
        new SecureRandom().nextBytes(preMasterSecret);
        byte[] encryptedPreMasterSecret = serverCertificate.encrypt(preMasterSecret);

        masterSecret = HandshakeCommon.generateMasterSecret(preMasterSecret, clientRandom, serverRandom);

        ctx.getChannel().write(NetData.NetMessage.newBuilder()
                .setNewIdentityRequest(NetData.NewIdentityRequest.newBuilder()
                        .setPreMasterSecret(ByteString.copyFrom(encryptedPreMasterSecret))
                        .setRandom(ByteString.copyFrom(clientRandom)))
                .build());
        requestedCertificate = true;
    }


}
