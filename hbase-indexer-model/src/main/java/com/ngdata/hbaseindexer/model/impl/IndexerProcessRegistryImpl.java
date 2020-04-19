/*
 * Copyright 2013 NGDATA nv
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
package com.ngdata.hbaseindexer.model.impl;

import java.util.List;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.ngdata.hbaseindexer.ConfKeys;
import com.ngdata.hbaseindexer.model.api.IndexerProcess;
import com.ngdata.hbaseindexer.model.api.IndexerProcessRegistry;
import com.ngdata.sep.util.zookeeper.ZkUtil;
import com.ngdata.sep.util.zookeeper.ZooKeeperItf;
import com.ngdata.sep.util.zookeeper.ZooKeeperOperation;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

/**
 * Persistence of the state of all indexer processes (per indexer) in ZooKeeper.
 */
public class IndexerProcessRegistryImpl implements IndexerProcessRegistry {

    private ZooKeeperItf zk;
    private final String zkBaseNode;

    public IndexerProcessRegistryImpl(ZooKeeperItf zk, Configuration conf) throws InterruptedException, KeeperException {
        this.zk = zk;
        this.zkBaseNode = conf.get(ConfKeys.ZK_ROOT_NODE) + "/indexerprocess";
        ZkUtil.createPath(zk, zkBaseNode);
    }
    
    @Override
    public String registerIndexerProcess(String indexerName, String hostName) {

        // TODO Each indexer should have its own parent node for all its processes
        // TODO Make sure that commas in an indexer name won't cause issues
        final String zkNodePathBase = String.format("%s/%s,%s,", zkBaseNode, indexerName, hostName);

        try {
            return zk.retryOperation(new ZooKeeperOperation<String>() {
                @Override
                public String execute() throws KeeperException, InterruptedException {
                    return zk.create(zkNodePathBase, new byte[0],
                            CreateMode.EPHEMERAL_SEQUENTIAL);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Error while registering indexer process", e);
        }
    }

    @Override
    public void setErrorStatus(final String indexerProcessId, Throwable error) {
        final String stackTrace = ExceptionUtils.getStackTrace(error);

        try {
            zk.retryOperation(new ZooKeeperOperation<Integer>() {

                @Override
                public Integer execute() throws KeeperException, InterruptedException {
                    zk.setData(indexerProcessId, Bytes.toBytes(stackTrace), -1);
                    return 0;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Error while setting error status on indexer node " + indexerProcessId, e);
        }
    }

    @Override
    public void unregisterIndexerProcess(final String indexerProcessId) {
        try {
            zk.retryOperation(new ZooKeeperOperation<Integer>() {

                @Override
                public Integer execute() throws KeeperException, InterruptedException {
                    zk.delete(indexerProcessId, -1);
                    return 0;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Error unregistering indexer process " + indexerProcessId, e);

        }
    }
    
    @Override
    public List<IndexerProcess> getIndexerProcesses(final String indexerName) {
        
        try {
            return zk.retryOperation(new ZooKeeperOperation<List<IndexerProcess>>(){

                @Override
                public List<IndexerProcess> execute() throws KeeperException, InterruptedException {

                    List<IndexerProcess> indexerProcesses = Lists.newArrayList();
                    for (String childNode : zk.getChildren(zkBaseNode, false)) {
                        List<String> nodeNameParts = Lists.newArrayList(Splitter.on(',').split(childNode));

                        if (indexerName.equals(nodeNameParts.get(0))) {
                            byte[] errorBytes = zk.getData(zkBaseNode + "/" + childNode, false, null);
                            IndexerProcess indexerProcess = new IndexerProcess(indexerName,
                                    nodeNameParts.get(1),
                                    errorBytes == null  || errorBytes.length == 0? null : Bytes.toString(errorBytes));
                            indexerProcesses.add(indexerProcess);
                        }
                    }
                    return indexerProcesses;
                    
                }});
        } catch (Exception e) {
            throw new RuntimeException("Error listing indexer processes for " + indexerName, e);
        }
    }

}
