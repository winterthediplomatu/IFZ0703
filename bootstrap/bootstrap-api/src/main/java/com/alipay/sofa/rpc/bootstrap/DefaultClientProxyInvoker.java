/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.bootstrap;

import com.alipay.sofa.rpc.client.ClientProxyInvoker;
import com.alipay.sofa.rpc.client.Cluster;
import com.alipay.sofa.rpc.codec.SerializerFactory;
import com.alipay.sofa.rpc.common.RemotingConstants;
import com.alipay.sofa.rpc.config.ConfigUniqueNameGenerator;
import com.alipay.sofa.rpc.context.BaggageResolver;
import com.alipay.sofa.rpc.context.RpcInternalContext;
import com.alipay.sofa.rpc.context.RpcInvokeContext;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.core.invoke.SendableResponseCallback;
import com.alipay.sofa.rpc.core.invoke.SofaResponseCallback;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.message.ResponseFuture;

import static com.alipay.sofa.rpc.common.RpcConstants.HIDDEN_KEY_INVOKE_CONTEXT;
import static com.alipay.sofa.rpc.common.RpcConstants.HIDDEN_KEY_PINPOINT;
import static com.alipay.sofa.rpc.common.RpcConstants.INTERNAL_KEY_APP_NAME;
import static com.alipay.sofa.rpc.common.RpcConstants.INTERNAL_KEY_PROTOCOL_NAME;
import static com.alipay.sofa.rpc.common.RpcConstants.INTERNAL_KEY_RESULT_CODE;

/**
 * ??????????????????????????????
 *
 * @author <a href="mailto:zhanggeng.zg@antfin.com">GengZhang</a>
 */
public class DefaultClientProxyInvoker extends ClientProxyInvoker {

    /**
     * ???????????????
     */
    protected String serviceName;

    /**
     * ?????????????????????
     */
    protected Byte   serializeType;

    /**
     * ???????????????
     *
     * @param bootstrap ???????????????
     */
    public DefaultClientProxyInvoker(ConsumerBootstrap bootstrap) {
        super(bootstrap);
        cacheCommonData();
    }

    protected void cacheCommonData() {
        // ????????????
        this.serviceName = ConfigUniqueNameGenerator.getServiceName(consumerConfig);
        this.serializeType = parseSerializeType(consumerConfig.getSerialization());
    }

    protected Byte parseSerializeType(String serialization) {
        Byte serializeType = SerializerFactory.getCodeByAlias(serialization);
        if (serializeType == null) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNSUPPORT_TYPE, serialization));
        }
        return serializeType;
    }

    @Override
    protected void decorateRequest(SofaRequest request) {
        // ???????????????
        super.decorateRequest(request);

        // ???????????????????????????
        request.setTargetServiceUniqueName(serviceName);
        request.setSerializeType(serializeType == null ? 0 : serializeType);

        if (!consumerConfig.isGeneric()) {
            // ????????????????????? generic??????????????????filter???????????????
            request.setInvokeType(consumerConfig.getMethodInvokeType(request.getMethodName()));
        }

        RpcInvokeContext invokeCtx = RpcInvokeContext.peekContext();
        RpcInternalContext internalContext = RpcInternalContext.getContext();
        if (invokeCtx != null) {
            // ?????????????????????????????????????????????
            SofaResponseCallback responseCallback = invokeCtx.getResponseCallback();
            if (responseCallback != null) {
                request.setSofaResponseCallback(responseCallback);
                invokeCtx.setResponseCallback(null); // ???????????????
                invokeCtx.put(RemotingConstants.INVOKE_CTX_IS_ASYNC_CHAIN,
                    isSendableResponseCallback(responseCallback));
            }
            // ?????????????????????????????????????????????
            Integer timeout = invokeCtx.getTimeout();
            if (timeout != null) {
                request.setTimeout(timeout);
                invokeCtx.setTimeout(null);// ???????????????
            }
            // ??????????????????????????????URL
            String targetURL = invokeCtx.getTargetURL();
            if (targetURL != null) {
                internalContext.setAttachment(HIDDEN_KEY_PINPOINT, targetURL);
                invokeCtx.setTargetURL(null);// ???????????????
            }
            // ?????????????????????????????????
            if (RpcInvokeContext.isBaggageEnable()) {
                // ????????????
                BaggageResolver.carryWithRequest(invokeCtx, request);
                internalContext.setAttachment(HIDDEN_KEY_INVOKE_CONTEXT, invokeCtx);
            }
        }
        if (RpcInternalContext.isAttachmentEnable()) {
            internalContext.setAttachment(INTERNAL_KEY_APP_NAME, consumerConfig.getAppName());
            internalContext.setAttachment(INTERNAL_KEY_PROTOCOL_NAME, consumerConfig.getProtocol());
        }

        // ??????????????????HEAD??????????????????
        request.addRequestProp(RemotingConstants.HEAD_APP_NAME, consumerConfig.getAppName());
        request.addRequestProp(RemotingConstants.HEAD_PROTOCOL, consumerConfig.getProtocol());
    }

    @Override
    protected void decorateResponse(SofaResponse response) {
        // ???????????????
        super.decorateResponse(response);
        // ??????????????????
        RpcInternalContext context = RpcInternalContext.getContext();
        ResponseFuture future = context.getFuture();
        RpcInvokeContext invokeCtx = null;
        if (future != null) {
            invokeCtx = RpcInvokeContext.getContext();
            invokeCtx.setFuture(future);
        }
        if (RpcInvokeContext.isBaggageEnable()) {
            BaggageResolver.pickupFromResponse(invokeCtx, response, true);
        }
        // bad code
        if (RpcInternalContext.isAttachmentEnable()) {
            String resultCode = (String) context.getAttachment(INTERNAL_KEY_RESULT_CODE);
            if (resultCode != null) {
                if (invokeCtx == null) {
                    invokeCtx = RpcInvokeContext.getContext();
                }
                invokeCtx.put(RemotingConstants.INVOKE_CTX_RPC_RESULT_CODE, resultCode);
            }
        }
    }

    /**
     * ???????????????Callback????????????classloader?????????????????????instanceof
     *
     * @param callback SofaResponseCallback
     * @return ????????????Callback
     */
    protected boolean isSendableResponseCallback(SofaResponseCallback callback) {
        return callback instanceof SendableResponseCallback;
    }

    @Override
    public Cluster setCluster(Cluster newCluster) {
        Cluster old = super.setCluster(newCluster);
        cacheCommonData();
        return old;
    }

    @Override
    public String toString() {
        return consumerConfig != null ? ConfigUniqueNameGenerator.getServiceName(consumerConfig) : super.toString();
    }
}
