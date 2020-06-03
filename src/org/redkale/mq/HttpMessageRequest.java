/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.mq;

import org.redkale.net.http.*;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
public class HttpMessageRequest extends HttpRequest {

    protected MessageRecord reqMessage;

    public HttpMessageRequest(HttpContext context, MessageRecord reqMessage) {
        super(context, reqMessage.decodeContent(HttpSimpleRequestCoder.getInstance()));
        this.reqMessage = reqMessage;
        this.currentUserid = reqMessage.getUserid();
    }

}