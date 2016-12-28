/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.redhat.hacep.configuration;

import it.redhat.hacep.cache.session.*;
import it.redhat.hacep.model.Fact;
import it.redhat.hacep.model.Key;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.eviction.EvictionType;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.TransactionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataGridManager {

    private final static Logger LOGGER = LoggerFactory.getLogger(DataGridManager.class);

    private static final String FACT_CACHE_NAME = "___events";

    private static final String SESSION_CACHE_NAME = "___sessions";

    private static final String REPLICATED_CACHE_NAME = "___infos";

    private final AtomicBoolean started = new AtomicBoolean(false);

    private DefaultCacheManager manager;

    public void start(HAKieSessionBuilder builder) {
        if (started.compareAndSet(false, true)) {
            GlobalConfiguration globalConfiguration = new GlobalConfigurationBuilder().clusteredDefault()
                    .transport().addProperty("configurationFile", System.getProperty("jgroups.configuration", "jgroups-tcp.xml"))
                    .clusterName("HACEP")
                    .globalJmxStatistics().allowDuplicateDomains(true).enable()
                    .serialization()
                    .addAdvancedExternalizer(new HAKieSession.HASessionExternalizer(builder))
                    .addAdvancedExternalizer(new HAKieSerializedSession.HASerializedSessionExternalizer(builder))
                    .addAdvancedExternalizer(new HAKieSessionDeltaEmpty.HASessionDeltaEmptyExternalizer(builder))
                    .addAdvancedExternalizer(new HAKieSessionDeltaFact.HASessionDeltaFactExternalizer())
                    .build();

            ConfigurationBuilder commonConfigurationBuilder = new ConfigurationBuilder();
            CacheMode cacheMode = getCacheMode();
            if (cacheMode.isDistributed()) {
                commonConfigurationBuilder
                        .clustering().cacheMode(cacheMode)
                        .hash().numOwners(getNumOwners())
                        .groups().enabled();
            } else {
                commonConfigurationBuilder.clustering().cacheMode(cacheMode);
            }

            Configuration commonConfiguration = commonConfigurationBuilder.build();
            this.manager = new DefaultCacheManager(globalConfiguration, commonConfiguration, false);

            ConfigurationBuilder factCacheConfigurationBuilder = new ConfigurationBuilder().read(commonConfiguration);

            factCacheConfigurationBuilder
                    .expiration()
                    .maxIdle(factsExpiration(), TimeUnit.MILLISECONDS);

            ConfigurationBuilder sessionCacheConfigurationBuilder = new ConfigurationBuilder().read(commonConfiguration);
            ConfigurationBuilder replicatedInfos = new ConfigurationBuilder();
            replicatedInfos
                    .clustering().cacheMode(CacheMode.REPL_SYNC)
                    .transaction().transactionMode(TransactionMode.TRANSACTIONAL).autoCommit(true);

            if (persistence()) {
                sessionCacheConfigurationBuilder
                        .persistence()
                        .passivation(isPassivated())
                        .addSingleFileStore()
                        .shared(shared())
                        .preload(preload())
                        .fetchPersistentState(fetchPersistentState())
                        .purgeOnStartup(purgeOnStartup())
                        .location(location())
                        .async().threadPoolSize(threadPoolSize()).enabled(false)
                        .singleton().enabled(false)
                        .eviction()
                        .strategy(EvictionStrategy.LRU).type(EvictionType.COUNT).size(evictionSize());

                replicatedInfos
                        .persistence()
                        .passivation(false)
                        .addSingleFileStore()
                        .shared(false)
                        .preload(true)
                        .fetchPersistentState(true)
                        .purgeOnStartup(false)
                        .location(location())
                        .async().threadPoolSize(threadPoolSize()).enabled(false)
                        .singleton().enabled(false);
            }

            this.manager.defineConfiguration(FACT_CACHE_NAME, factCacheConfigurationBuilder.build());
            this.manager.defineConfiguration(SESSION_CACHE_NAME, sessionCacheConfigurationBuilder.build());
            this.manager.defineConfiguration(REPLICATED_CACHE_NAME, replicatedInfos.build());

            this.manager.start();
        }
    }

    public boolean waitForMinimumOwners(long timeout, TimeUnit unit) {
        checkStatus();
        ExecutorService service = Executors.newSingleThreadExecutor();
        Future<Boolean> future = service.submit(() -> {
            int numOwners = getNumOwners();
            if(LOGGER.isDebugEnabled()) LOGGER.debug("Waiting for minimum {} owners", numOwners);
            while (manager.getClusterSize() < numOwners) {
                Thread.sleep(100);
            }
            if(LOGGER.isDebugEnabled()) LOGGER.debug("Cluster minimum nodes are connected");
            return true;
        });

        try {
            return future.get(timeout, unit);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.warn("WaitForMinimumOwners exception", e);
            return false;
        } finally {
            service.shutdown();
        }
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            this.manager.stop();
        }
    }

    public Cache<Key, Fact> getFactCache() {
        checkStatus();
        return this.manager.getCache(FACT_CACHE_NAME, true);
    }

    public Cache<String, String> getReplicatedCache() {
        checkStatus();
        return this.manager.getCache(REPLICATED_CACHE_NAME, true);
    }

    public Cache<String, Object> getSessionCache() {
        checkStatus();
        return this.manager.getCache(SESSION_CACHE_NAME, true);
    }

    public EmbeddedCacheManager getCacheManager() {
        return manager;
    }

    private void checkStatus() {
        if (!started.get()) {
            throw new IllegalStateException("Datagrid manager needs to be started!");
        }
    }

    public void removeSession(Key key) {
        checkStatus();
        this.getSessionCache().remove(key);
    }

    private CacheMode getCacheMode() {
        try {
            return CacheMode.valueOf(System.getProperty("grid.mode", "DIST_SYNC"));
        } catch (IllegalArgumentException e) {
            return CacheMode.DIST_SYNC;
        }
    }

    private int getNumOwners() {
        try {
            return Integer.valueOf(System.getProperty("grid.owners", "2"));
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    private boolean persistence() {
        try {
            return Boolean.valueOf(System.getProperty("grid.persistence", "false"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int factsExpiration() {
        try {
            return Integer.valueOf(System.getProperty("grid.facts.expirationMaxIdle", "500"));
        } catch (IllegalArgumentException e) {
            return 500;
        }
    }

    private boolean isPassivated() {
        try {
            return Boolean.valueOf(System.getProperty("grid.persistence.passivation", "true"));
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private boolean shared() {
        try {
            return Boolean.valueOf(System.getProperty("grid.persistence.shared", "false"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean preload() {
        try {
            return Boolean.valueOf(System.getProperty("grid.persistence.preload", "true"));
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private boolean fetchPersistentState() {
        try {
            return Boolean.valueOf(System.getProperty("grid.persistence.fetchPersistentState", "true"));
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private boolean purgeOnStartup() {
        try {
            return Boolean.valueOf(System.getProperty("grid.persistence.purgeOnStartup", "false"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int threadPoolSize() {
        try {
            return Integer.valueOf(System.getProperty("grid.persistence.threadPoolSize", "5"));
        } catch (IllegalArgumentException e) {
            return 5;
        }
    }

    private String location() {
        try {
            return System.getProperty("grid.persistence.location", System.getProperty("java.io.tmpdir"));
        } catch (IllegalArgumentException e) {
            return System.getProperty("java.io.tmpdir");
        }
    }

    private int evictionSize() {
        try {
            return Integer.valueOf(System.getProperty("grid.persistence.evictionSize", "100"));
        } catch (IllegalArgumentException e) {
            return 100;
        }
    }
}
