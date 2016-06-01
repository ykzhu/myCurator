/**
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
package org.apache.curator.x.rpc;

import com.codahale.metrics.MetricRegistry;
import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.service.ThriftEventHandler;
import com.facebook.swift.service.ThriftServer;
import com.facebook.swift.service.ThriftServiceProcessor;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.apache.curator.x.rpc.configuration.Configuration;
import org.apache.curator.x.rpc.configuration.ConfigurationBuilder;
import org.apache.curator.x.rpc.connections.ConnectionManager;
import org.apache.curator.x.rpc.idl.discovery.DiscoveryService;
import org.apache.curator.x.rpc.idl.discovery.DiscoveryServiceLowLevel;
import org.apache.curator.x.rpc.idl.services.EventService;
import org.apache.curator.x.rpc.idl.services.CuratorProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

public class CuratorProjectionServer
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ConnectionManager connectionManager;
    private final ThriftServer server;
    private final AtomicReference<State> state = new AtomicReference<State>(State.LATENT);
    private final Configuration configuration;

    private enum State
    {
        LATENT,
        STARTED,
        STOPPED
    }

    public static void main(String[] args) throws Exception
    {
        if ( (args.length != 1) || args[0].equalsIgnoreCase("?") || args[0].equalsIgnoreCase("-h") || args[0].equalsIgnoreCase("--help") )
        {
            printHelp();
            return;
        }

        String configurationSource;
        File f = new File(args[0]);
        if ( f.exists() )
        {
            configurationSource = Files.toString(f, Charset.defaultCharset());
        }
        else
        {
            System.out.println("First argument is not a file. Treating the command line as a json/yaml object");
            configurationSource = args[0];
        }

        final CuratorProjectionServer server = startServer(configurationSource);

        Runnable shutdown = new Runnable()
        {
            @Override
            public void run()
            {
                server.stop();
            }
        };
        Thread hook = new Thread(shutdown);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    public static CuratorProjectionServer startServer(String configurationSource) throws Exception
    {
        Configuration configuration = new ConfigurationBuilder(configurationSource).build();

        final CuratorProjectionServer server = new CuratorProjectionServer(configuration);
        server.start();
        return server;
    }

    public CuratorProjectionServer(Configuration configuration)
    {
        this.configuration = configuration;
        connectionManager = new ConnectionManager(configuration.getConnections(), configuration.getProjectionExpiration().toMillis());
        EventService eventService = new EventService(connectionManager, configuration.getPingTime().toMillis());
        DiscoveryService discoveryService = new DiscoveryService(connectionManager);
        CuratorProjectionService projectionService = new CuratorProjectionService(connectionManager);
        DiscoveryServiceLowLevel discoveryServiceLowLevel = new DiscoveryServiceLowLevel(connectionManager);
        ThriftServiceProcessor processor = new ThriftServiceProcessor(new ThriftCodecManager(), Lists.<ThriftEventHandler>newArrayList(), projectionService, eventService, discoveryService, discoveryServiceLowLevel);
        server = new ThriftServer(processor, configuration.getThrift());
    }

    public void start()
    {
        Preconditions.checkState(state.compareAndSet(State.LATENT, State.STARTED), "Already started");

        configuration.getLogging().configure(new MetricRegistry(), "curator-rpc");
        connectionManager.start();
        server.start();

        log.info("Server listening on port: " + configuration.getThrift().getPort());
    }

    public void stop()
    {
        if ( state.compareAndSet(State.STARTED, State.STOPPED) )
        {
            log.info("Stopping...");

            server.close();
            connectionManager.close();
            configuration.getLogging().stop();

            log.info("Stopped");
        }
    }

    private static void printHelp() throws IOException
    {
        URL helpUrl = Resources.getResource("curator/help.txt");
        System.out.println(Resources.toString(helpUrl, Charset.defaultCharset()));

        System.out.println();
        System.out.println("======= Curator Thrift IDL =======");
        System.out.println();

        URL idlUrl = Resources.getResource("curator.thrift");
        System.out.println(Resources.toString(idlUrl, Charset.defaultCharset()));
    }
}
