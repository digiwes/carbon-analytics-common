/*
*  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.databridge.receiver.binary.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.databridge.commons.binary.BinaryMessageConstants;
import org.wso2.carbon.databridge.core.DataBridgeReceiverService;
import org.wso2.carbon.databridge.core.exception.DataBridgeException;
import org.wso2.carbon.databridge.receiver.binary.BinaryEventConverter;
import org.wso2.carbon.databridge.receiver.binary.conf.BinaryDataReceiverConfiguration;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.wso2.carbon.databridge.commons.binary.BinaryMessageConverterUtil.loadData;

/**
 * Binary Transport Receiver implementation.
 */
public class BinaryDataReceiver {
    private static final Log log = LogFactory.getLog(BinaryDataReceiver.class);
    private DataBridgeReceiverService dataBridgeReceiverService;
    private BinaryDataReceiverConfiguration binaryDataReceiverConfiguration;
    private ExecutorService sslReceiverExecutorService;
    private ExecutorService tcpReceiverExecutorService;

    public BinaryDataReceiver(BinaryDataReceiverConfiguration binaryDataReceiverConfiguration,
                              DataBridgeReceiverService dataBridgeReceiverService) {
        this.dataBridgeReceiverService = dataBridgeReceiverService;
        this.binaryDataReceiverConfiguration = binaryDataReceiverConfiguration;
        this.sslReceiverExecutorService = Executors.newFixedThreadPool(binaryDataReceiverConfiguration.
                getSizeOfSSLThreadPool());
        this.tcpReceiverExecutorService = Executors.newFixedThreadPool(binaryDataReceiverConfiguration.
                getSizeOfTCPThreadPool());
    }

    public void start() throws IOException, DataBridgeException {
        startSecureTransmission();
        startEventTransmission();
    }

    public void stop() {
        sslReceiverExecutorService.shutdownNow();
        tcpReceiverExecutorService.shutdownNow();
    }

    private void startSecureTransmission() throws IOException, DataBridgeException {
        ServerConfiguration serverConfig = ServerConfiguration.getInstance();
        String keyStore = serverConfig.getFirstProperty("Security.KeyStore.Location");
        if (keyStore == null) {
            keyStore = System.getProperty("Security.KeyStore.Location");
            if (keyStore == null) {
                throw new DataBridgeException("Cannot start agent server, not valid Security.KeyStore.Location is null");
            }
        }
        String keyStorePassword = serverConfig.getFirstProperty("Security.KeyStore.Password");
        if (keyStorePassword == null) {
            keyStorePassword = System.getProperty("Security.KeyStore.Password");
            if (keyStorePassword == null) {
                throw new DataBridgeException("Cannot start agent server, not valid Security.KeyStore.Password is null ");
            }
        }
        System.setProperty("javax.net.ssl.keyStore", keyStore);
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
        SSLServerSocketFactory sslserversocketfactory =
                (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        SSLServerSocket sslserversocket =
                (SSLServerSocket) sslserversocketfactory.createServerSocket(binaryDataReceiverConfiguration.getSSLPort());
        sslserversocket.setEnabledCipherSuites(sslserversocket.getSupportedCipherSuites());
        for (int i = 0; i < binaryDataReceiverConfiguration.getSizeOfSSLThreadPool(); i++) {
            sslReceiverExecutorService.execute(new BinaryTransportReceiver(sslserversocket));
        }
        log.info("Started Binary SSL Transport on port : " + binaryDataReceiverConfiguration.getSSLPort());
    }

    private void startEventTransmission() throws IOException {
        ServerSocketFactory serversocketfactory = ServerSocketFactory.getDefault();
        ServerSocket serversocket = serversocketfactory.createServerSocket(binaryDataReceiverConfiguration.getTCPPort());
        for (int i = 0; i < binaryDataReceiverConfiguration.getSizeOfTCPThreadPool(); i++) {
            tcpReceiverExecutorService.submit(new BinaryTransportReceiver(serversocket));
        }
        log.info("Started Binary TCP Transport on port : " + binaryDataReceiverConfiguration.getTCPPort());
    }

    public class BinaryTransportReceiver implements Runnable {
        private ServerSocket serverSocket;

        public BinaryTransportReceiver(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }


        @Override
        public void run() {
            Socket socket;
            try {
                /*
                Always server needs to listen and accept the socket connection
                 */
                while (true) {
                    socket = this.serverSocket.accept();
                    InputStream inputstream = new BufferedInputStream(socket.getInputStream());
                    OutputStream outputStream = new BufferedOutputStream((socket.getOutputStream()));

                    int messageType = inputstream.read();
                    while (messageType!=-1) {
                        int messageSize = ByteBuffer.wrap(loadData(inputstream, new byte[4])).getInt();
                        byte[] message = loadData(inputstream, new byte[messageSize]);
                        processMessage(messageType, message, outputStream);
                        messageType = inputstream.read();
                    }
                }
            } catch (IOException ex) {
                log.error("Error while creating SSL socket on port : " + binaryDataReceiverConfiguration.getSizeOfTCPThreadPool(), ex);
            } catch (Throwable t) {
                log.error("Error while receiving messages " + t.getMessage(), t);
            }
        }

    }

    private String processMessage(int messageType, byte[] message, OutputStream outputStream) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(message);
        int sessionIdLength;
        String sessionId;

        switch (messageType) {
            case 0: //Login
                int userNameLength = byteBuffer.getInt();
                int passwordLength = byteBuffer.getInt();

                String userName = new String(message, 8, userNameLength);
                String password = new String(message, 8 + userNameLength, passwordLength);

                try {
                    sessionId = dataBridgeReceiverService.login(userName, password);

                    ByteBuffer buffer = ByteBuffer.allocate(5 + sessionId.length());
                    buffer.put((byte) 2);
                    buffer.putInt(sessionId.length());
                    buffer.put(sessionId.getBytes(BinaryMessageConstants.DEFAULT_CHARSET));

                    outputStream.write(buffer.array());
                    outputStream.flush();
                } catch (Exception e) {
                    try {
                        sendError(e, outputStream);
                    } catch (IOException e1) {
                        log.error("Error while sending response for login message: " + e1.getMessage(), e1);
                    }
                }
                break;
            case 1://Logout
                sessionIdLength = byteBuffer.getInt();
                sessionId = new String(message, 4, sessionIdLength);
                try {
                    dataBridgeReceiverService.logout(sessionId);

                    outputStream.write((byte) 0);
                    outputStream.flush();
                } catch (Exception e) {
                    try {
                        sendError(e, outputStream);
                    } catch (IOException e1) {
                        log.error("Error while sending response for login message: " + e1.getMessage(), e1);
                    }
                }
                break;
            case 2: //Publish
                sessionIdLength = byteBuffer.getInt();
                sessionId = new String(message, 4, sessionIdLength);
                try {
                    dataBridgeReceiverService.publish(message, sessionId, BinaryEventConverter.getConverter());

                    outputStream.write((byte) 0);
                    outputStream.flush();
                } catch (Exception e) {
                    try {
                        sendError(e, outputStream);
                    } catch (IOException e1) {
                        log.error("Error while sending response for login message: " + e1.getMessage(), e1);
                    }
                }
                break;
            default:
                log.error("Message Type " + messageType + " is not supported!");
        }
        return null;
    }

    private void sendError(Exception e, OutputStream outputStream) throws IOException {

        int errorClassNameLength = e.getClass().getCanonicalName().length();
        int errorMsgLength = e.getMessage().length();

        ByteBuffer bbuf = ByteBuffer.wrap(new byte[8]);
        bbuf.putInt(errorClassNameLength);
        bbuf.putInt(errorMsgLength);

        outputStream.write((byte) 1);//Error
        outputStream.write(bbuf.array());
        outputStream.write(e.getClass().getCanonicalName().getBytes(BinaryMessageConstants.DEFAULT_CHARSET));
        outputStream.write(e.getMessage().getBytes(BinaryMessageConstants.DEFAULT_CHARSET));
        outputStream.flush();
    }
}
