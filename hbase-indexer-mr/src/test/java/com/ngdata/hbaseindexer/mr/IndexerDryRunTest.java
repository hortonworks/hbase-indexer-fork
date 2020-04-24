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
package com.ngdata.hbaseindexer.mr;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import com.ngdata.hbaseindexer.util.net.NetUtils;
import com.ngdata.hbaseindexer.util.solr.SolrTestingUtility;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.hadoop.dedup.RetainMostRecentUpdateConflictResolver;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import static org.junit.Assert.assertEquals;

public class IndexerDryRunTest {

    private static final byte[] TEST_TABLE_NAME = Bytes.toBytes("record");
    private static final byte[] TEST_COLFAM_NAME = Bytes.toBytes("info");
    
    private static final HBaseTestingUtility HBASE_TEST_UTILITY = HBaseTestingUtilityFactory.createTestUtility();
    private static SolrTestingUtility SOLR_TEST_UTILITY;
    
    
    private static CloudSolrClient COLLECTION;
    private static Admin HBASE_ADMIN;

    private Table recordTable;
    
    private HBaseIndexingOptions opts;
    
    @BeforeClass
    public static void setupBeforeClass() throws Exception {
        System.setProperty("jetty.testMode", "true");
        HBASE_TEST_UTILITY.startMiniCluster();
        
        int zkClientPort = HBASE_TEST_UTILITY.getZkCluster().getClientPort();
        
        SOLR_TEST_UTILITY = new SolrTestingUtility(zkClientPort, NetUtils.getFreePort());
        SOLR_TEST_UTILITY.start();
        SOLR_TEST_UTILITY.uploadConfig("config1",
                Resources.toByteArray(Resources.getResource(HBaseMapReduceIndexerToolDirectWriteTest.class, "managed-schema")),
                Resources.toByteArray(Resources.getResource(HBaseMapReduceIndexerToolDirectWriteTest.class, "solrconfig.xml")));
        SOLR_TEST_UTILITY.createCollection("collection1", "config1", 1);

        COLLECTION = new CloudSolrClient.Builder().withZkHost(SOLR_TEST_UTILITY.getZkConnectString()).build();
        COLLECTION.setDefaultCollection("collection1");
        
        HBASE_ADMIN = ConnectionFactory.createConnection(HBASE_TEST_UTILITY.getConfiguration()).getAdmin();

    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        SOLR_TEST_UTILITY.stop();
        HBASE_ADMIN.close();
        HBASE_ADMIN.getConnection().close();
        HBASE_TEST_UTILITY.shutdownMiniCluster();
    }
    
    @Before
    public void setUp() throws Exception {
        HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf(TEST_TABLE_NAME));
        tableDescriptor.addFamily(new HColumnDescriptor(TEST_COLFAM_NAME));
        HBASE_ADMIN.createTable(tableDescriptor);
        
        recordTable = HBASE_ADMIN.getConnection().getTable(TableName.valueOf(TEST_TABLE_NAME));
        
        opts = new HBaseIndexingOptions(new Configuration());
        opts.zkHost = SOLR_TEST_UTILITY.getZkConnectString();
        opts.hbaseTableName = Bytes.toString(TEST_TABLE_NAME);
        opts.hbaseIndexerConfigFile = new File(Resources.getResource(getClass(), "user_indexer.xml").toURI());
        opts.collection = "collection1";
        opts.shards = 1;
        opts.reducers = 1;
        opts.fanout = Integer.MAX_VALUE;
        opts.isDryRun = true;
       
        opts.updateConflictResolver = RetainMostRecentUpdateConflictResolver.class.getName();
        opts.isVerbose = true;

        opts.hBaseAdmin = HBASE_ADMIN;
    }
    
    @After
    public void tearDown() throws IOException {
        recordTable.close();
        HBASE_ADMIN.disableTable(TableName.valueOf(TEST_TABLE_NAME));
        HBASE_ADMIN.deleteTable(TableName.valueOf(TEST_TABLE_NAME));
    }
    
    /**
     * Write String values to HBase. Direct string-to-bytes encoding is used for
     * writing all values to HBase. All values are stored in the TEST_COLFAM_NAME
     * column family.
     * 
     * 
     * @param row row key under which are to be stored
     * @param qualifiersAndValues map of column qualifiers to cell values
     */
    private void writeHBaseRecord(String row, Map<String,String> qualifiersAndValues) throws IOException {
        Put put = new Put(Bytes.toBytes(row));
        for (Entry<String, String> entry : qualifiersAndValues.entrySet()) {
            put.addColumn(TEST_COLFAM_NAME, Bytes.toBytes(entry.getKey()), Bytes.toBytes(entry.getValue()));
        }
        recordTable.put(put);
    }
    
    @Test
    public void testDryRun() throws IOException {
        writeHBaseRecord("row1", ImmutableMap.of(
                "firstname", "John",
                "lastname", "Doe"));
        
        writeHBaseRecord("row2", ImmutableMap.of(
                "firstname", "Jane",
                "lastname", "Doe"));
        
        opts.evaluate();
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        IndexerDryRun dryRunner = new IndexerDryRun(opts, HBASE_TEST_UTILITY.getConfiguration(), outputStream);
        int exitCode = dryRunner.run();
        
        String output = new String(outputStream.toByteArray());
        
        assertEquals(3, Iterables.size(Splitter.on('\n').split(output)));
        
        assertEquals(0, exitCode);
        
        
        
    }

}
