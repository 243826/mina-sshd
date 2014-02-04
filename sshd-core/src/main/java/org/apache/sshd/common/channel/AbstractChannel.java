/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.channel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.common.Channel;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.RequestHandler;
import org.apache.sshd.common.Session;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.DefaultCloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.common.util.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractChannel implements Channel {

    public static final int DEFAULT_WINDOW_SIZE = 0x200000;
    public static final int DEFAULT_PACKET_SIZE = 0x8000;

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Object lock = new Object();
    protected final Window localWindow = new Window(this, null, getClass().getName().contains(".client."), true);
    protected final Window remoteWindow = new Window(this, null, getClass().getName().contains(".client."), false);
    protected ConnectionService service;
    protected Session session;
    protected int id;
    protected int recipient;
    protected final CloseFuture closeFuture = new DefaultCloseFuture(lock);
    protected volatile boolean eof;
    protected final AtomicBoolean closing = new AtomicBoolean();
    protected boolean closedByOtherSide;
    protected final List<RequestHandler<Channel>> handlers = new ArrayList<RequestHandler<Channel>>();

    public void addRequestHandler(RequestHandler<Channel> handler) {
        handlers.add(handler);
    }

    public int getId() {
        return id;
    }

    public int getRecipient() {
        return recipient;
    }

    public Window getLocalWindow() {
        return localWindow;
    }

    public Window getRemoteWindow() {
        return remoteWindow;
    }

    public Session getSession() {
        return session;
    }

    public void handleRequest(Buffer buffer) throws IOException {
        String req = buffer.getString();
        boolean wantReply = buffer.getBoolean();
        log.debug("Received SSH_MSG_CHANNEL_REQUEST {} on channel {} (wantReply {})", new Object[] { req, id, wantReply });
        for (RequestHandler<Channel> handler : handlers) {
            RequestHandler.Result result;
            try {
                result = handler.process(this, req, wantReply, buffer);
            } catch (Exception e) {
                log.warn("Error processing channel request " + req, e);
                result = RequestHandler.Result.ReplyFailure;
            }
            switch (result) {
                case Replied:
                    return;
                case ReplySuccess:
                    if (wantReply) {
                        buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_SUCCESS);
                        buffer.putInt(recipient);
                        session.writePacket(buffer);
                    }
                    return;
                case ReplyFailure:
                    if (wantReply) {
                        buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_FAILURE);
                        buffer.putInt(recipient);
                        session.writePacket(buffer);
                    }
                    return;
            }
        }
        log.warn("Unknown channel request: {}", req);
        if (wantReply) {
            buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_FAILURE);
            buffer.putInt(recipient);
            session.writePacket(buffer);
        }
    }

    public void init(ConnectionService service, Session session, int id) {
        this.service = service;
        this.session = session;
        this.id = id;
        configureWindow();
    }

    protected void notifyStateChanged() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public CloseFuture close(boolean immediately) {
        if (closing.compareAndSet(false, true)) {
            if (immediately) {
                log.debug("Closing channel {} immediately", id);
                preClose(immediately).addListener(new SshFutureListener<CloseFuture>() {
                    public void operationComplete(CloseFuture future) {
                        postClose();
                        closeFuture.setClosed();
                        notifyStateChanged();
                        service.unregisterChannel(AbstractChannel.this);
                    }
                });
            } else {
                log.debug("Closing channel {} gracefully", id);
                preClose(immediately).addListener(new SshFutureListener<CloseFuture>() {
                    public void operationComplete(CloseFuture future) {
                        log.debug("Send SSH_MSG_CHANNEL_CLOSE on channel {}", id);
                        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_CLOSE);
                        buffer.putInt(recipient);
                        try {
                            session.writePacket(buffer).addListener(new SshFutureListener<IoWriteFuture>() {
                                public void operationComplete(IoWriteFuture future) {
                                    if (closedByOtherSide) {
                                        log.debug("Message SSH_MSG_CHANNEL_CLOSE written on channel {}", id);
                                        postClose();
                                        closeFuture.setClosed();
                                        notifyStateChanged();
                                    }
                                }
                            });
                        } catch (IOException e) {
                            log.debug("Exception caught while writing SSH_MSG_CHANNEL_CLOSE packet on channel " + id, e);
                            postClose();
                            closeFuture.setClosed();
                            notifyStateChanged();
                        }
                    }
                });
            }
        }
        return closeFuture;
    }

    public void handleClose() throws IOException {
        log.debug("Received SSH_MSG_CHANNEL_CLOSE on channel {}", id);
        closedByOtherSide = !closing.get();
        if (closedByOtherSide) {
            close(false);
        } else {
            postClose();
            closeFuture.setClosed();
            notifyStateChanged();
        }
    }

    protected CloseFuture preClose(boolean immediately) {
        CloseFuture future = new DefaultCloseFuture(lock);
        future.setClosed();
        return future;
    }

    protected void postClose() {
    }

    protected void writePacket(Buffer buffer) throws IOException {
        if (!closing.get()) {
            session.writePacket(buffer);
        } else {
            log.debug("Discarding output packet because channel is being closed");
        }
    }

    public void handleData(Buffer buffer) throws IOException {
        int len = buffer.getInt();
        if (len < 0 || len > Buffer.MAX_LEN) {
            throw new IllegalStateException("Bad item length: " + len);
        }
        log.debug("Received SSH_MSG_CHANNEL_DATA on channel {}", id);
        if (log.isTraceEnabled()) {
            log.trace("Received channel data: {}", BufferUtils.printHex(buffer.array(), buffer.rpos(), len));
        }
        doWriteData(buffer.array(), buffer.rpos(), len);
    }

    public void handleExtendedData(Buffer buffer) throws IOException {
        int ex = buffer.getInt();
        // Only accept extended data for stderr
        if (ex != 1) {
            log.debug("Send SSH_MSG_CHANNEL_FAILURE on channel {}", id);
            buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_FAILURE);
            buffer.putInt(recipient);
            writePacket(buffer);
            return;
        }
        int len = buffer.getInt();
        if (len < 0 || len > Buffer.MAX_LEN) {
            throw new IllegalStateException("Bad item length: " + len);
        }
        log.debug("Received SSH_MSG_CHANNEL_EXTENDED_DATA on channel {}", id);
        if (log.isTraceEnabled()) {
            log.trace("Received channel extended data: {}", BufferUtils.printHex(buffer.array(), buffer.rpos(), len));
        }
        doWriteExtendedData(buffer.array(), buffer.rpos(), len);
    }

    public void handleEof() throws IOException {
        log.debug("Received SSH_MSG_CHANNEL_EOF on channel {}", id);
        eof = true;
        notifyStateChanged();
    }

    public void handleWindowAdjust(Buffer buffer) throws IOException {
        log.debug("Received SSH_MSG_CHANNEL_WINDOW_ADJUST on channel {}", id);
        int window = buffer.getInt();
        remoteWindow.expand(window);
    }

    public void handleFailure() throws IOException {
        log.debug("Received SSH_MSG_CHANNEL_FAILURE on channel {}", id);
        // TODO: do something to report failed requests?
    }

    protected abstract void doWriteData(byte[] data, int off, int len) throws IOException;

    protected abstract void doWriteExtendedData(byte[] data, int off, int len) throws IOException;

    protected void sendEof() throws IOException {
        log.debug("Send SSH_MSG_CHANNEL_EOF on channel {}", id);
        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_EOF);
        buffer.putInt(recipient);
        writePacket(buffer);
    }

    protected void configureWindow() {
        int window = session.getIntProperty(FactoryManager.WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
        int packet = session.getIntProperty(FactoryManager.MAX_PACKET_SIZE, DEFAULT_PACKET_SIZE);
        localWindow.init(window, packet);
    }

    protected void sendWindowAdjust(int len) throws IOException {
        log.debug("Send SSH_MSG_CHANNEL_WINDOW_ADJUST on channel {}", id);
        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_WINDOW_ADJUST);
        buffer.putInt(recipient);
        buffer.putInt(len);
        writePacket(buffer);
    }
}
