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
package com.alipay.sofa.rpc.server.http;

import com.alipay.sofa.rpc.common.cache.ReflectCache;
import com.alipay.sofa.rpc.common.struct.NamedThreadFactory;
import com.alipay.sofa.rpc.common.utils.ClassTypeUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.ServerConfig;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.event.EventBus;
import com.alipay.sofa.rpc.event.ServerStartedEvent;
import com.alipay.sofa.rpc.event.ServerStoppedEvent;
import com.alipay.sofa.rpc.invoke.Invoker;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.server.BusinessPool;
import com.alipay.sofa.rpc.server.Server;
import com.alipay.sofa.rpc.server.SofaRejectedExecutionHandler;
import com.alipay.sofa.rpc.transport.ServerTransport;
import com.alipay.sofa.rpc.transport.ServerTransportConfig;
import com.alipay.sofa.rpc.transport.ServerTransportFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * HttpServer for HTTP/1.1 and HTTP/2
 *
 * @author <a href="mailto:zhanggeng.zg@antfin.com">GengZhang</a>
 * @since 5.4.0
 */
public abstract class AbstractHttpServer implements Server {

    protected final String container;

    public AbstractHttpServer(String container) {
        this.container = container;
    }

    /**
     * ??????????????????
     */
    protected volatile boolean      started;

    /**
     * ???????????????
     */
    protected ServerConfig          serverConfig;

    /**
     * Server transport config
     */
    protected ServerTransportConfig serverTransportConfig;
    /**
     * Http Server handler
     */
    protected HttpServerHandler     serverHandler;

    /**
     * ??????????????????
     */
    private ServerTransport         serverTransport;
    /**
     * ???????????????
     */
    protected ThreadPoolExecutor    bizThreadPool;

    @Override
    public void init(ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
        this.serverTransportConfig = convertConfig(serverConfig);
        // ???????????????
        this.bizThreadPool = initThreadPool(serverConfig);
        // ??????????????????
        this.serverHandler = new HttpServerHandler();

        // set default transport config
        this.serverTransportConfig.setContainer(container);
        this.serverTransportConfig.setServerHandler(serverHandler);
    }

    protected ThreadPoolExecutor initThreadPool(ServerConfig serverConfig) {
        ThreadPoolExecutor threadPool = BusinessPool.initPool(serverConfig);
        threadPool.setThreadFactory(new NamedThreadFactory("SEV-" + serverConfig.getProtocol().toUpperCase()
            + "-BIZ-" + serverConfig.getPort(), serverConfig.isDaemon()));
        threadPool.setRejectedExecutionHandler(new SofaRejectedExecutionHandler());
        if (serverConfig.isPreStartCore()) { // ????????????????????????
            threadPool.prestartAllCoreThreads();
        }
        return threadPool;
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        synchronized (this) {
            if (started) {
                return;
            }
            try {
                // ???????????????
                this.bizThreadPool = initThreadPool(serverConfig);
                this.serverHandler.setBizThreadPool(bizThreadPool);
                serverTransport = ServerTransportFactory.getServerTransport(serverTransportConfig);
                started = serverTransport.start();

                if (started) {
                    if (EventBus.isEnable(ServerStartedEvent.class)) {
                        EventBus.post(new ServerStartedEvent(serverConfig, bizThreadPool));
                    }
                }
            } catch (SofaRpcRuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_START_SERVER, "HTTP/2"), e);
            }
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public boolean hasNoEntry() {
        return serverHandler.isEmpty();
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        synchronized (this) {
            if (!started) {
                return;
            }
            // ?????????????????????????????????
            serverTransport.stop();
            serverTransport = null;

            // ???????????????
            if (bizThreadPool != null) {
                bizThreadPool.shutdown();
                bizThreadPool = null;
                serverHandler.setBizThreadPool(null);
            }

            started = false;

            if (EventBus.isEnable(ServerStoppedEvent.class)) {
                EventBus.post(new ServerStoppedEvent(serverConfig));
            }
        }
    }

    @Override
    public void registerProcessor(ProviderConfig providerConfig, Invoker instance) {
        // ??????Invoker??????
        String serviceName = getUniqueName(providerConfig);
        serverHandler.getInvokerMap().put(serviceName, instance);
        // ????????????????????????????????????
        Class itfClass = providerConfig.getProxyClass();
        HashMap<String, Method> methodsLimit = new HashMap<String, Method>(16);
        for (Method method : itfClass.getMethods()) {
            String methodName = method.getName();
            if (methodsLimit.containsKey(methodName)) {
                // ???????????????
                throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_OVERLOADING_METHOD,
                    itfClass.getName(), methodName));
            }
            methodsLimit.put(methodName, method);
        }

        for (Map.Entry<String, Method> entry : methodsLimit.entrySet()) {
            // ?????????????????????
            ReflectCache.putMethodCache(serviceName, entry.getValue());
            ReflectCache.putMethodSigsCache(serviceName, entry.getKey(),
                ClassTypeUtils.getTypeStrs(entry.getValue().getParameterTypes(), true));
        }
    }

    @Override
    public void unRegisterProcessor(ProviderConfig providerConfig, boolean closeIfNoEntry) {
        if (!started) {
            return;
        }
        // ????????????Invoker??????
        String key = getUniqueName(providerConfig);
        serverHandler.getInvokerMap().remove(key);
        // ??????????????????????????????????????????
        if (closeIfNoEntry && serverHandler.getInvokerMap().size() == 0) {
            stop();
        }
    }

    private String getUniqueName(ProviderConfig providerConfig) {
        String uniqueId = providerConfig.getUniqueId();
        return providerConfig.getInterfaceId() + (StringUtils.isEmpty(uniqueId) ? StringUtils.EMPTY : ":" + uniqueId);
    }

    @Override
    public void destroy() {
        if (!started) {
            return;
        }

        stop();
        serverHandler = null;
    }

    @Override
    public void destroy(DestroyHook hook) {
        hook.preDestroy();
        destroy();
        hook.postDestroy();
    }

    public ThreadPoolExecutor getBizThreadPool() {
        return bizThreadPool;
    }

    /**
     * ServerConfig???ServerTransportConfig
     *
     * @param serverConfig ???????????????
     * @return ServerTransportConfig ?????????????????????
     */
    private static ServerTransportConfig convertConfig(ServerConfig serverConfig) {
        ServerTransportConfig serverTransportConfig = new ServerTransportConfig();
        serverTransportConfig.setPort(serverConfig.getPort());
        serverTransportConfig.setProtocolType(serverConfig.getProtocol());
        serverTransportConfig.setHost(serverConfig.getBoundHost());
        serverTransportConfig.setContextPath(serverConfig.getContextPath());
        serverTransportConfig.setBizMaxThreads(serverConfig.getMaxThreads());
        serverTransportConfig.setBizPoolType(serverConfig.getThreadPoolType());
        serverTransportConfig.setIoThreads(serverConfig.getIoThreads());
        serverTransportConfig.setChannelListeners(serverConfig.getOnConnect());
        serverTransportConfig.setMaxConnection(serverConfig.getAccepts());
        serverTransportConfig.setPayload(serverConfig.getPayload());
        serverTransportConfig.setTelnet(serverConfig.isTelnet());
        serverTransportConfig.setUseEpoll(serverConfig.isEpoll());
        serverTransportConfig.setBizPoolQueueType(serverConfig.getQueueType());
        serverTransportConfig.setBizPoolQueues(serverConfig.getQueues());
        serverTransportConfig.setDaemon(serverConfig.isDaemon());
        serverTransportConfig.setParameters(serverConfig.getParameters());
        serverTransportConfig.setContainer(serverConfig.getTransport());
        return serverTransportConfig;
    }

}
