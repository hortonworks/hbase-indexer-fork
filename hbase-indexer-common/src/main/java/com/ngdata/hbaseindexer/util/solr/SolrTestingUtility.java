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
package com.ngdata.hbaseindexer.util.solr;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.ngdata.sep.util.zookeeper.ZkUtil;
import com.ngdata.sep.util.zookeeper.ZooKeeperItf;
import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.RequestStatusState;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.OnReconnect;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.Diagnostics;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Helps booting up SolrCloud in test code.
 *
 * <p>It basically starts up an empty Solr (no cores, collections or configs).
 * A config can be uploaded using {@link #uploadConfig(String, byte[], byte[])} and a core/collection can be
 * added through {@link #createCore(String, String, String, int)}.</p>
 *
 * <p>The Solr ZooKeeper is chroot'ed to "/solr".</p>
 */
public class SolrTestingUtility {
    private final int solrPort;
    private JettySolrRunner jettySolrRunner;
    private File tmpDir;
    private File solrHomeDir;
    private int zkClientPort;
    private String zkConnectString;
    private Map<String, String> configProperties;

    public SolrTestingUtility(int zkClientPort, int solrPort) {
        this(zkClientPort, solrPort, ImmutableMap.<String, String>of());
    }

    public SolrTestingUtility(int zkClientPort, int solrPort, Map<String, String> configProperties) {
        this.zkClientPort = zkClientPort;
        this.zkConnectString = "localhost:" + zkClientPort + "/solr";
        this.solrPort = solrPort;
        this.configProperties = configProperties;
    }

    public void start() throws Exception {
        // Make the Solr home directory
        this.tmpDir = Files.createTempDir();
        this.solrHomeDir = new File(tmpDir, "home");
        if (!this.solrHomeDir.mkdir()) {
            throw new RuntimeException("Failed to create directory " + this.solrHomeDir.getAbsolutePath());
        }
        writeSolrXml();

        // Set required system properties
        System.setProperty("solr.solr.home", solrHomeDir.getAbsolutePath());
        System.setProperty("zkHost", zkConnectString);
        System.setProperty("solr.port", Integer.toString(solrPort));

        for (Entry<String, String> entry : configProperties.entrySet()) {
            System.setProperty(entry.getKey().toString(), entry.getValue());
        }

        jettySolrRunner = createServer();
        jettySolrRunner.start();
    }

    public File getSolrHomeDir() {
        return solrHomeDir;
    }

    public String getZkConnectString() {
        return zkConnectString;
    }

    private void writeSolrXml() throws FileNotFoundException {
        File solrXml = new File(solrHomeDir, "solr.xml");
        PrintWriter solrXmlWriter = new PrintWriter(solrXml);
        solrXmlWriter.println("<solr>");
        solrXmlWriter.println("  <solrcloud>");
        solrXmlWriter.println("    <str name=\"host\">127.0.0.1</str>");
        solrXmlWriter.println("    <int name=\"hostPort\">${solr.port}</int>");
        solrXmlWriter.println("    <str name=\"hostContext\">/solr</str>");
        solrXmlWriter.println("  </solrcloud>");
        solrXmlWriter.println("</solr>");
        solrXmlWriter.close();
    }

    private JettySolrRunner createServer() throws Exception {
        // create path on zookeeper for solr cloud
        ZooKeeperItf zk = ZkUtil.connect("localhost:" + zkClientPort, 10000);
        ZkUtil.createPath(zk, "/solr");
        zk.close();

        return new JettySolrRunner(solrHomeDir.toString(), "/solr", solrPort);
    }

    public void stop() throws Exception {
        if (jettySolrRunner != null) {
            jettySolrRunner.stop();
        }

        if (tmpDir != null) {
            FileUtils.deleteDirectory(tmpDir);
        }

        System.getProperties().remove("solr.solr.home");
        System.getProperties().remove("zkHost");
        for (String configPropertyKey : configProperties.keySet()) {
            System.getProperties().remove(configPropertyKey);
        }
    }


    /**
     * Utility method to upload a Solr config into ZooKeeper. This method only allows to supply schema and
     * solrconf, if you want to upload a full directory use {@link #uploadConfig(String, File)}.
     */
    public void uploadConfig(String confName, byte[] schema, byte[] solrconf)
            throws IOException {
        // Write schema & solrconf to temporary dir, upload dir, delete tmp dir
        File tmpConfDir = Files.createTempDir();
        Files.write(schema, new File(tmpConfDir, "managed-schema"));
        Files.write(solrconf, new File(tmpConfDir, "solrconfig.xml"));
        uploadConfig(confName, tmpConfDir);
        FileUtils.deleteDirectory(tmpConfDir);
    }

    /**
     * Utility method to upload a Solr config into ZooKeeper. If you don't have the config in the form of
     * a filesystem directory, you might want to use {@link #uploadConfig(String, byte[], byte[])}.
     */
    public void uploadConfig(String confName, File confDir) throws IOException {
        SolrZkClient zkClient = new SolrZkClient(zkConnectString, 30000, 30000,
                new OnReconnect() {
                    @Override
                    public void command() {
                    }
                });
        new ZkConfigManager(zkClient).uploadConfigDir(confDir.toPath(), confName);
        zkClient.close();
    }

    /**
     * Create a Solr collection with a given number of shards.
     *
     * @param collectionName name of the collection to be created
     * @param configName     name of the config for the collection
     * @param numShards      number of shards in the collection
     */
    public void createCollection(String collectionName, String configName, int numShards) throws IOException {
      int TIMEOUT = 30; // seconds
      CloudSolrClient solrClient = new CloudSolrClient.Builder().withZkHost(getZkConnectString()).build();
      solrClient.setDefaultCollection(collectionName);
      CollectionAdminRequest.Create request = CollectionAdminRequest.createCollection(
          collectionName, configName, numShards, 1);
      request.setMaxShardsPerNode(numShards);
      RequestStatusState state;
      try {
          state = request.processAndWait(solrClient, TIMEOUT);
      } catch (SolrServerException | InterruptedException e) {
          throw new RuntimeException(e);
      }
      if (state != RequestStatusState.COMPLETED && state != RequestStatusState.RUNNING) {
          throw new IllegalStateException("Unexpected state: " + state);
      }
      waitForRecoveriesToFinish(
          collectionName, solrClient.getZkStateReader(), true, true, TIMEOUT);
    }

    // copied from AbstractDistribZkTestBase.waitForRecoveriesToFinish()
    private static void waitForRecoveriesToFinish(String collection,
        ZkStateReader zkStateReader, boolean verbose, boolean failOnTimeout, int timeoutSeconds)
         {
      Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
      log.info("Wait for recoveries to finish - collection: " + collection + " failOnTimeout:" + failOnTimeout + " timeout (sec):" + timeoutSeconds);
      boolean cont = true;
      int cnt = 0;
      
      while (cont) {
        if (verbose) System.out.println("-");
        boolean sawLiveRecovering = false;
        ClusterState clusterState = zkStateReader.getClusterState();
        final DocCollection docCollection = clusterState.getCollectionOrNull(collection);
        if (docCollection == null) throw new IllegalStateException("Could not find collection:" + collection);
        Map<String,Slice> slices = docCollection.getSlicesMap();
        if (slices == null) throw new IllegalStateException("Could not find collection:" + collection);
        for (Map.Entry<String,Slice> entry : slices.entrySet()) {
          Slice slice = entry.getValue();
          if (slice.getState() == Slice.State.CONSTRUCTION) { // similar to replica recovering; pretend its the same thing
            if (verbose) System.out.println("Found a slice in construction state; will wait.");
            sawLiveRecovering = true;
          }
          Map<String,Replica> shards = slice.getReplicasMap();
          for (Map.Entry<String,Replica> shard : shards.entrySet()) {
            if (verbose) System.out.println("replica:" + shard.getValue().getName() + " rstate:"
                + shard.getValue().getStr(ZkStateReader.STATE_PROP)
                + " live:"
                + clusterState.liveNodesContain(shard.getValue().getNodeName()));
            final Replica.State state = shard.getValue().getState();
            if ((state == Replica.State.RECOVERING || state == Replica.State.DOWN || state == Replica.State.RECOVERY_FAILED)
                && clusterState.liveNodesContain(shard.getValue().getStr(ZkStateReader.NODE_NAME_PROP))) {
              sawLiveRecovering = true;
            }
          }
        }
        if (!sawLiveRecovering || cnt == timeoutSeconds) {
          if (!sawLiveRecovering) {
            if (verbose) System.out.println("no one is recoverying");
          } else {
            if (verbose) System.out.println("Gave up waiting for recovery to finish..");
            if (failOnTimeout) {
              Diagnostics.logThreadDumps("Gave up waiting for recovery to finish.  THREAD DUMP:");
              try {
                zkStateReader.getZkClient().printLayoutToStdOut();
              } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
              }
              throw new IllegalStateException("There are still nodes recoverying - waited for " + timeoutSeconds + " seconds");
              // won't get here
              //return;
            }
          }
          cont = false;
        } else {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        cnt++;
      }

      log.info("Recoveries finished - collection: " + collection);
    }

}
