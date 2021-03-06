/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.nio.channels.CompletionHandler;
import java.util.concurrent.*;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
public class NioCompletionHandler<A> implements CompletionHandler<Integer, A>, Runnable {

    private final CompletionHandler<Integer, A> handler;

    private A attachment;

    public ScheduledFuture timeoutFuture;

    public NioCompletionHandler(CompletionHandler<Integer, A> handler, A attachment) {
        this.handler = handler;
        this.attachment = attachment;
    }

    public void setAttachment(A attachment) {
        this.attachment = attachment;
    }

    @Override
    public void completed(Integer result, A attach) {
        ScheduledFuture future = this.timeoutFuture;
        if (future != null) {
            this.timeoutFuture = null;
            future.cancel(true);
        }
        handler.completed(result, attachment);
    }

    @Override
    public void failed(Throwable exc, A attach) {
        ScheduledFuture future = this.timeoutFuture;
        if (future != null) {
            this.timeoutFuture = null;
            future.cancel(true);
        }
        handler.failed(exc, attachment);
    }

    @Override
    public void run() {
        handler.failed(new TimeoutException(), attachment);
    }

}
