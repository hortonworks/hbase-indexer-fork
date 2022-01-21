/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.sep.impl;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ClusterConnection;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.replication.regionserver.WALEntrySinkFilter;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKAuthentication;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.ngdata.sep.EventListener;
import com.ngdata.sep.PayloadExtractor;
import com.ngdata.sep.SepEvent;
import com.ngdata.sep.SepModel;
import com.ngdata.sep.util.concurrent.WaitPolicy;
import com.ngdata.sep.util.zookeeper.ZooKeeperItf;

/**
 * SepConsumer consumes the events for a certain SEP subscription and dispatches
 * them to an EventListener (optionally multi-threaded). Multiple SepConsumer's
 * can be started that take events from the same subscription: each consumer
 * will receive a subset of the events.
 *
 * <p>On a more technical level, SepConsumer is the remote process (the "fake
 * hbase regionserver") to which the regionservers in the hbase master cluster
 * connect to replicate log entries.</p>
 */
public class SepConsumer {
    private final String subscriptionId;
    private final long subscriptionTimestamp;
    private final EventListener listener;
    private final ZooKeeperItf zk;
    private final Configuration hbaseConf;
    private final HRegionServer regionServer;
    private final ServerName serverName;
    private final SepMetrics sepMetrics;
    private final PayloadExtractor payloadExtractor;
    private String zkNodePath;
    private final List<ThreadPoolExecutor> executors;
    private final Predicate<TableName> tableNamePredicate;
    private boolean running = false;
    private final Log log = LogFactory.getLog(getClass());
    private static final AtomicBoolean AT_MOST_ONCE = new AtomicBoolean(true);

    private static final String MASTERLESS_ROOT_ZK_PATH = "/ngdata/sep/hbase-masterless";
    private static final String MASTERLESS_ZK_DIR_SUFFIX = "hbaseindexer.masterless.zkDirSuffix";

    /**
     * @param subscriptionTimestamp timestamp of when the index subscription became active (or more accurately, not
     *                              inactive)
     * @param listener              listeners that will process the events
     * @param threadCnt             number of worker threads that will handle incoming SEP events
     * @param hostName              hostname, this is published in ZooKeeper and will be used by HBase to connect to this
     *                              consumer, so there should be only one SepConsumer instance for a (subscriptionId, host)
     *                              combination, and the hostname should definitely not be "localhost". This is also the hostname
     *                              that the RPC will bind to.
     */
    public SepConsumer(String subscriptionId, long subscriptionTimestamp, EventListener listener, int threadCnt,
            String hostName, ZooKeeperItf zk, Configuration hbaseConf) throws IOException, InterruptedException {
        this(subscriptionId, subscriptionTimestamp, listener, threadCnt, hostName, zk, hbaseConf, null, null);
    }

    /**
     * @param subscriptionTimestamp timestamp of when the index subscription became active (or more accurately, not
     *                              inactive)
     * @param listener              listeners that will process the events
     * @param threadCnt             number of worker threads that will handle incoming SEP events
     * @param hostName              hostname to bind to
     * @param payloadExtractor      extracts payloads to include in SepEvents
     */
    public SepConsumer(String subscriptionId, long subscriptionTimestamp, EventListener listener, int threadCnt,
            String hostName, ZooKeeperItf zk, Configuration hbaseConf, PayloadExtractor payloadExtractor, 
            Predicate<TableName> tableNamePredicate) throws IOException, InterruptedException {
        Preconditions.checkArgument(threadCnt > 0, "Thread count must be > 0");
        this.subscriptionId = SepModelImpl.toInternalSubscriptionName(subscriptionId);
        this.subscriptionTimestamp = subscriptionTimestamp;
        this.listener = listener;
        this.zk = zk;
        this.hbaseConf = hbaseConf;
        this.sepMetrics = new SepMetrics(subscriptionId);
        this.payloadExtractor = payloadExtractor;
        this.executors = Lists.newArrayListWithCapacity(threadCnt);
        if (tableNamePredicate == null) {
            tableNamePredicate = TableNamePredicates.getAlwaysMatchingTableNamePredicate();
        }
        this.tableNamePredicate = tableNamePredicate;

        Configuration masterlessConf = HBaseConfiguration.create();
        masterlessConf.addResource("hbase-indexer-site-masterless-defaults.xml");
        masterlessConf.addResource("hbase-indexer-site.xml"); // allow custom overrides, also see HBaseIndexerConfiguration.addHbaseIndexerResources
        masterlessConf.set(ClusterConnection.HBASE_CLIENT_CONNECTION_IMPL, SepConnection.class.getName());
        masterlessConf.set(WALEntrySinkFilter.WAL_ENTRY_FILTER_KEY, SepWALEntrySinkFilter.class.getName());        
        
        final String masterlessZkDirSuffix = masterlessConf.get(MASTERLESS_ZK_DIR_SUFFIX);
        final String masterlessZkPath;
        if (masterlessZkDirSuffix == null) {
            masterlessZkPath = MASTERLESS_ROOT_ZK_PATH;
        } else {
            masterlessZkPath = MASTERLESS_ROOT_ZK_PATH + masterlessZkDirSuffix.trim();
        }
        masterlessConf.set(HConstants.ZOOKEEPER_ZNODE_PARENT, masterlessZkPath);
        log.info("Creating masterless RegionServer using " + HConstants.ZOOKEEPER_ZNODE_PARENT + ":" + masterlessZkPath);
        
        masterlessConf.setInt(HConstants.REGIONSERVER_PORT, 0); // let the system pick up an ephemeral port
        
        // copy zk client port to enable testing on non-standard ports
        String zkClientPort = hbaseConf.get(HConstants.ZOOKEEPER_CLIENT_PORT);
        if (zkClientPort != null) {
          masterlessConf.set(HConstants.ZOOKEEPER_CLIENT_PORT, zkClientPort);              
        }
        
        // hacky way to pass non-primitive params to SepConnection, also see start() and stop()
        masterlessConf.set(SepConnection.SUBSCRIPTION_ID_PARAM_NAME, subscriptionId);
  
        // HBASE-19804 setMiniClusterMode(true) hack to allow multiple HRegionServer per JVM even in non-unit tests
        DefaultMetricsSystem.setMiniClusterMode(true);

        if (AT_MOST_ONCE.getAndSet(false)) {
            dumpConfiguration("Creating masterless RegionServer using", masterlessConf);
        }
        this.regionServer = new HRegionServer(masterlessConf);        
        this.serverName = regionServer.getServerName();

        // login the zookeeper client principal (if using security)
        ZKAuthentication.loginClient(hbaseConf, "hbase.zookeeper.client.keytab.file",
                "hbase.zookeeper.client.kerberos.principal", hostName);

        // login the server principal (if using secure Hadoop)
        User.login(hbaseConf, "hbase.regionserver.keytab.file",
                "hbase.regionserver.kerberos.principal", hostName);

        for (int i = 0; i < threadCnt; i++) {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(100));
            executor.setRejectedExecutionHandler(new WaitPolicy());
            executors.add(executor);
        }
    }

    public void start() throws InterruptedException, KeeperException {
        // hacky way to pass non-primitive params to SepConnection
        SepConnection.PARAMS_MAP.put(subscriptionId, 
            new SepConnectionParams(tableNamePredicate, subscriptionTimestamp, this));
        
        Thread thread = new Thread() {            
            @Override
            public void run() {
                regionServer.run();
            }
        };
        thread.setDaemon(true);
        thread.start();
        regionServer.waitForServerOnline();

        // Publish our existence in ZooKeeper
        zkNodePath = hbaseConf.get(SepModel.ZK_ROOT_NODE_CONF_KEY, SepModel.DEFAULT_ZK_ROOT_NODE)
                + "/" + subscriptionId + "/rs/" + serverName.getServerName();
        log.debug("Publishing our existence in zk at zkNodePathForSlave:" + zkNodePath);
        zk.create(zkNodePath, null, CreateMode.EPHEMERAL);

        this.running = true;
    }

    public void stop() {
        if (running) {
            running = false;
            if (regionServer != null) {
                regionServer.stop("Stopping masterless regionserver for subscriptionId:" + subscriptionId);
            }
            SepConnection.PARAMS_MAP.remove(subscriptionId);
            try {
                // This ZK node will likely already be gone if the index has been removed
                // from ZK, but we'll try to remove it here to be sure
                zk.delete(zkNodePath, -1);
            } catch (Exception e) {
                log.debug("Exception while removing zookeeper node", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        sepMetrics.shutdown();
        for (ThreadPoolExecutor executor : executors) {
            executor.shutdown();
        }
    }

    void replicateBatch(List<? extends Row> actions, Object[] results, TableName tableName) 
    throws IOException {
        if (log.isDebugEnabled()) {
          log.debug("replicateBatch() received " + actions.size() + " events from table " + tableName + " for subscriptionId "
              + subscriptionId);
        }
    
        // TODO Recording of last processed timestamp won't work if two batches of log entries are sent out of order
        long lastProcessedTimestamp = -1;
        
        SepEventExecutor eventExecutor = new SepEventExecutor(listener, executors, 100, sepMetrics);
        
        for (Row row : actions) {
            if (!(row instanceof Mutation)) {
                throw new RuntimeException("Unreachable code for row class: " + row.getClass().getName());
            }
            // Actually, ReplicationSink only feeds us Put and Delete objects anyway
            Mutation mutation = (Mutation) row;
            
            final Multimap<ByteBuffer, Cell> keyValuesPerRowKey = ArrayListMultimap.create();
            final Map<ByteBuffer, byte[]> payloadPerRowKey = Maps.newHashMap();
            
            CellScanner cells = mutation.cellScanner();
            while (cells.advance()) {
                Cell cell = cells.current();
                lastProcessedTimestamp = Math.max(lastProcessedTimestamp, cell.getTimestamp());
                ByteBuffer rowKey = ByteBuffer.wrap(cell.getRowArray(), cell.getRowOffset(), cell.getRowLength());
                byte[] payload;
                KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
                if (payloadExtractor != null && (payload = payloadExtractor.extractPayload(tableName.toBytes(), kv)) != null) {
                    if (payloadPerRowKey.containsKey(rowKey)) {
                        log.error("Multiple payloads encountered for row " + Bytes.toStringBinary(rowKey)
                                + ", choosing " + Bytes.toStringBinary(payloadPerRowKey.get(rowKey)));
                    } else {
                        payloadPerRowKey.put(rowKey, payload);
                    }
                }
                keyValuesPerRowKey.put(rowKey, kv);
            }
            for (final ByteBuffer rowKeyBuffer : keyValuesPerRowKey.keySet()) {
                final List<Cell> keyValues = (List<Cell>) keyValuesPerRowKey.get(rowKeyBuffer);
      
                final SepEvent sepEvent = new SepEvent(tableName.toBytes(), CellUtil.cloneRow(keyValues.get(0)), keyValues,
                        payloadPerRowKey.get(rowKeyBuffer));
                eventExecutor.scheduleSepEvent(sepEvent);
            }
          }
    
          List<Future<?>> futures = eventExecutor.flush();
          waitOnSepEventCompletion(futures);
    
          if (lastProcessedTimestamp > 0) {
              sepMetrics.reportSepTimestamp(lastProcessedTimestamp);
          }
    } 

    private void waitOnSepEventCompletion(List<Future<?>> futures) throws IOException {
        // We should wait for all operations to finish before returning, because otherwise HBase might
        // deliver a next batch from the same HLog to a different server. This becomes even more important
        // if an exception has been thrown in the batch, as waiting for all futures increases the back-off that
        // occurs before the next attempt
        List<Exception> exceptionsThrown = Lists.newArrayList();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted in processing events.", e);
            } catch (Exception e) {
                // While we throw the error higher up, to HBase, where it will also be logged, apparently the
                // nested exceptions don't survive somewhere, therefore log it client-side as well.
                log.warn("Error processing a batch of SEP events, the error will be forwarded to HBase for retry", e);
                exceptionsThrown.add(e);
            }
        }

        if (!exceptionsThrown.isEmpty()) {
            log.error("Encountered exceptions on " + exceptionsThrown.size() + " batches (out of " + futures.size()
                    + " total batches)");
            throw new RuntimeException(exceptionsThrown.get(0));
        }
    }

    private void dumpConfiguration(String msgPrefix, Configuration conf) throws IOException {
        StringWriter strWriter = new StringWriter();
        Configuration.dumpConfiguration(conf, strWriter);
        log.info(msgPrefix + " " + conf + " with properties: " + strWriter.toString());
    }
}
