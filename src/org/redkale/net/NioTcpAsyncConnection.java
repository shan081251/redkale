/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import javax.net.ssl.SSLContext;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
public class NioTcpAsyncConnection extends AsyncConnection {

    private int readTimeoutSeconds;

    private int writeTimeoutSeconds;

    private SocketAddress remoteAddress;

    final SocketChannel channel;

    final NioThread ioThread;

    final NioThreadGroup ioGroup;

    //连
    private Object connectAttachment;

    private CompletionHandler<Void, Object> connectCompletionHandler;

    private boolean connectPending;

    private SelectionKey connectKey;

    //读操作
    private ByteBuffer readByteBuffer;

    private CompletionHandler<Integer, ByteBuffer> readCompletionHandler;

    private boolean readPending;

    private SelectionKey readKey;

    //写操作, 二选一，要么writeByteBuffer有值，要么writeByteBuffers、writeOffset、writeLength有值
    private ByteBuffer writeByteBuffer;

    private ByteBuffer[] writeByteBuffers;

    private int writeOffset;

    private int writeLength;

    private Object writeAttachment;

    private CompletionHandler<Integer, Object> writeCompletionHandler;

    private boolean writePending;

    private SelectionKey writeKey;

    public NioTcpAsyncConnection(NioThreadGroup ioGroup, NioThread ioThread, SocketChannel ch,
        SSLContext sslContext, final SocketAddress addr0, AtomicLong livingCounter, AtomicLong closedCounter) {
        super(ioThread.getBufferSupplier(), ioThread.getBufferConsumer(), sslContext, livingCounter, closedCounter);
        this.ioGroup = ioGroup;
        this.ioThread = ioThread;
        this.channel = ch;
        SocketAddress addr = addr0;
        if (addr == null) {
            try {
                addr = ch.getRemoteAddress();
            } catch (Exception e) {
                //do nothing
            }
        }
        this.remoteAddress = addr;
    }

    public NioTcpAsyncConnection(NioThreadGroup ioGroup, NioThread ioThread,
        Supplier<ByteBuffer> bufferSupplier, Consumer<ByteBuffer> bufferConsumer,
        SocketChannel ch, SSLContext sslContext, final SocketAddress addr0,
        AtomicLong livingCounter, AtomicLong closedCounter) {
        super(bufferSupplier, bufferConsumer, sslContext, livingCounter, closedCounter);
        this.ioGroup = ioGroup;
        this.ioThread = ioThread;
        this.channel = ch;
        SocketAddress addr = addr0;
        if (addr == null) {
            try {
                addr = ch.getRemoteAddress();
            } catch (Exception e) {
                //do nothing
            }
        }
        this.remoteAddress = addr;
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public boolean isTCP() {
        return true;
    }

    @Override
    public boolean shutdownInput() {
        try {
            this.channel.shutdownInput();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean shutdownOutput() {
        try {
            this.channel.shutdownOutput();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public <T> boolean setOption(SocketOption<T> name, T value) {
        try {
            this.channel.setOption(name, value);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return this.channel.supportedOptions();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public SocketAddress getLocalAddress() {
        try {
            return channel.getLocalAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    @Override
    public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
        this.writeTimeoutSeconds = writeTimeoutSeconds;
    }

    @Override
    public int getReadTimeoutSeconds() {
        return this.readTimeoutSeconds;
    }

    @Override
    public int getWriteTimeoutSeconds() {
        return this.writeTimeoutSeconds;
    }

    @Override
    public ReadableByteChannel readableByteChannel() {
        return this.channel;
    }

    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        if (channel.isConnected()) {
            throw new AlreadyConnectedException();
        }
        if (connectPending) {
            throw new ConnectionPendingException();
        }
        connectPending = true;
        this.connectAttachment = attachment;
        this.connectCompletionHandler = (CompletionHandler<Void, Object>) handler;
        this.remoteAddress = remote;
        doConnect();
    }

    @Override
    public void read(CompletionHandler<Integer, ByteBuffer> handler) {
        Objects.requireNonNull(handler);
        if (!this.channel.isConnected()) {
            handler.failed(new NotYetConnectedException(), pollReadBuffer());
            return;
        }
        if (this.readPending) {
            handler.failed(new ReadPendingException(), pollReadBuffer());
            return;
        }
        this.readPending = true;
        if (this.readTimeoutSeconds > 0) {
            NioCompletionHandler newhandler = new NioCompletionHandler(handler, this.readByteBuffer);
            this.readCompletionHandler = newhandler;
            newhandler.timeoutFuture = ioGroup.scheduleTimeout(newhandler, this.readTimeoutSeconds, TimeUnit.SECONDS);
        } else {
            this.readCompletionHandler = handler;
        }
        doRead();
    }

    @Override
    public WritableByteChannel writableByteChannel() {
        return this.channel;
    }

    @Override
    public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
        Objects.requireNonNull(src);
        Objects.requireNonNull(handler);
        if (!this.channel.isConnected()) {
            handler.failed(new NotYetConnectedException(), attachment);
            return;
        }
        if (this.writePending) {
            handler.failed(new WritePendingException(), attachment);
            return;
        }
        this.writePending = true;
        this.writeByteBuffer = src;
        this.writeAttachment = attachment;
        if (this.writeTimeoutSeconds > 0) {
            NioCompletionHandler newhandler = new NioCompletionHandler(handler, attachment);
            this.writeCompletionHandler = newhandler;
            newhandler.timeoutFuture = ioGroup.scheduleTimeout(newhandler, this.writeTimeoutSeconds, TimeUnit.SECONDS);
        } else {
            this.writeCompletionHandler = (CompletionHandler) handler;
        }
        doWrite();
    }

    @Override
    public <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler) {
        Objects.requireNonNull(srcs);
        Objects.requireNonNull(handler);
        if (!this.channel.isConnected()) {
            handler.failed(new NotYetConnectedException(), attachment);
            return;
        }
        if (this.writePending) {
            handler.failed(new WritePendingException(), attachment);
            return;
        }
        this.writePending = true;
        this.writeByteBuffers = srcs;
        this.writeOffset = offset;
        this.writeLength = length;
        this.writeAttachment = attachment;
        if (this.writeTimeoutSeconds > 0) {
            NioCompletionHandler newhandler = new NioCompletionHandler(handler, attachment);
            this.writeCompletionHandler = newhandler;
            newhandler.timeoutFuture = ioGroup.scheduleTimeout(newhandler, this.writeTimeoutSeconds, TimeUnit.SECONDS);
        } else {
            this.writeCompletionHandler = (CompletionHandler) handler;
        }
        doWrite();
    }

    public void doConnect() {
        try {
            boolean connected = channel.isConnectionPending();
            if (connected || channel.connect(remoteAddress)) {
                connected = channel.finishConnect();
            }
            if (connected) {
                handleConnect(null);
            } else if (connectKey == null) {
                ioThread.register(selector -> {
                    try {
                        connectKey = channel.register(selector, SelectionKey.OP_CONNECT);
                        connectKey.attach(this);
                    } catch (ClosedChannelException e) {
                        handleConnect(e);
                    }
                });
            } else {
                handleConnect(new IOException());
            }
        } catch (IOException e) {
            handleConnect(e);
        }
    }

    private void handleConnect(Throwable t) {
        CompletionHandler handler = this.connectCompletionHandler;
        Object attach = this.connectAttachment;
        clearConnect();
        if (handler != null) {
            if (t == null) {
                handler.completed(null, attach);
            } else {
                handler.failed(t, attach);
            }
        }
    }

    private void clearConnect() {
        this.connectCompletionHandler = null;
        this.connectAttachment = null;
        this.connectPending = false;//必须放最后
    }

    public void doRead() {
        try {
            final boolean invokeDirect = this.ioThread.inCurrThread();
            int totalCount = 0;
            boolean hasRemain = true;
            if (invokeDirect && this.readByteBuffer == null) {
                this.readByteBuffer = pollReadBuffer();
                if (this.readTimeoutSeconds > 0) {
                    ((NioCompletionHandler) this.readCompletionHandler).setAttachment(this.readByteBuffer);
                }
            }
            while (invokeDirect && hasRemain) {
                int readCount = this.channel.read(readByteBuffer);
                hasRemain = readByteBuffer.hasRemaining();
                if (readCount <= 0) {
                    if (totalCount == 0) totalCount = readCount;
                    break;
                }
                totalCount += readCount;
            }
            if (totalCount != 0 || !hasRemain) {
                if (!readPending && readKey != null) readKey.interestOps(readKey.interestOps() & ~SelectionKey.OP_READ);
                handleRead(totalCount, null);
            } else if (readKey == null) {
                ioThread.register(selector -> {
                    try {
                        readKey = channel.register(selector, SelectionKey.OP_READ);
                        readKey.attach(this);
                    } catch (ClosedChannelException e) {
                        handleRead(0, e);
                    }
                });
            } else {
                ioGroup.interestOpsOr(ioThread, readKey, SelectionKey.OP_READ);
            }
        } catch (Exception e) {
            handleRead(0, e);
        }
    }

    private void handleRead(final int totalCount, Throwable t) {
        CompletionHandler<Integer, ByteBuffer> handler = this.readCompletionHandler;
        ByteBuffer attach = this.readByteBuffer;
        clearRead();
        if (handler != null) {
            if (t == null) {
                handler.completed(totalCount, attach);
            } else {
                handler.failed(t, attach);
            }
        }
    }

    private void clearRead() {
        this.readCompletionHandler = null;
        this.readByteBuffer = null;
        this.readPending = false; //必须放最后
    }

    public void doWrite() {
        try {
            final boolean invokeDirect = this.ioThread.inCurrThread();
            int totalCount = 0;
            boolean hasRemain = true;
            while (invokeDirect && hasRemain) {
                int writeCount;
                if (writeByteBuffer != null) {
                    writeCount = channel.write(writeByteBuffer);
                    hasRemain = writeByteBuffer.hasRemaining();
                } else {
                    writeCount = (int) channel.write(writeByteBuffers, writeOffset, writeLength);
                    boolean remain = false;
                    for (int i = writeByteBuffers.length - 1; i >= writeOffset; i--) {
                        if (writeByteBuffers[i].hasRemaining()) {
                            remain = true;
                            break;
                        }
                    }
                    hasRemain = remain;
                }
                if (writeCount <= 0) {
                    if (totalCount == 0) totalCount = writeCount;
                    break;
                }
                totalCount += writeCount;
            }

            if (totalCount > 0 || !hasRemain) {
                if (writeKey != null) writeKey.interestOps(writeKey.interestOps() & ~SelectionKey.OP_WRITE);
                CompletionHandler<Integer, Object> handler = this.writeCompletionHandler;
                Object attach = this.writeAttachment;
                clearWrite();
                if (handler != null) {
                    handler.completed(totalCount, attach);
                }
            } else if (writeKey == null) {
                ioThread.register(selector -> {
                    try {
                        writeKey = channel.register(selector, SelectionKey.OP_WRITE);
                        writeKey.attach(this);
                    } catch (ClosedChannelException e) {
                        CompletionHandler<Integer, Object> handler = this.writeCompletionHandler;
                        Object attach = this.writeAttachment;
                        clearWrite();
                        if (handler != null) {
                            handler.failed(e, attach);
                        }
                    }
                });
            } else {
                ioGroup.interestOpsOr(ioThread, writeKey, SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            CompletionHandler<Integer, Object> handler = this.writeCompletionHandler;
            Object attach = this.writeAttachment;
            clearWrite();
            if (handler != null) {
                handler.failed(e, attach);
            }
        }
    }

    private void clearWrite() {
        this.writeCompletionHandler = null;
        this.writeAttachment = null;
        this.writeByteBuffer = null;
        this.writeByteBuffers = null;
        this.writeOffset = 0;
        this.writeLength = 0;
        this.writePending = false; //必须放最后
    }

    @Override
    public final void close() throws IOException {
        super.close();
        if (this.connectKey != null) this.connectKey.cancel();
        if (this.readKey != null) this.readKey.cancel();
        if (this.writeKey != null) this.writeKey.cancel();
        channel.close();
    }
}
