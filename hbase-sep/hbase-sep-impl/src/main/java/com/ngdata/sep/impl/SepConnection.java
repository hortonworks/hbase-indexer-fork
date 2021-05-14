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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.security.User;

/**
 * This class receives the replication stream/events of a region server of the HBase slave cluster
 * in method replicateBatch() and forwards the events to the {@link SepConsumer}, which can then
 * apply corresponding insert/update/delete operations to a Lucene/Solr index to keep the index in
 * sync with the state of the underlying HBase table.
 */ 
final class SepConnection extends ReplicationSlaveConnection { 
  
  public static final String SUBSCRIPTION_ID_PARAM_NAME = "SepConnection-subscriptionId";

  // hacky way to pass non-primitive params to a SepConnection instance associated with a given subscriberId
  static final SepConnectionParamsMap PARAMS_MAP = new SepConnectionParamsMap();
  
  private final SepConnectionParams params;

  /** This constructor will be called by the slave region server ReplicationSink.batch() */
  public SepConnection(Configuration conf, ExecutorService pool, User user) throws IOException {
    super(conf, pool, user);
    String subscriptionId = conf.get(SUBSCRIPTION_ID_PARAM_NAME);
    this.params = PARAMS_MAP.get(subscriptionId);
    Objects.requireNonNull(this.params);

    /*
     * The following approach to pass non-primitive params to a SepConnection doesn't work because
     * the ReplicationSink constructor decorates conf object into another conf object, so the
     * original conf object becomes unreachable to us here:
     * 
     * this.params = ((SepConfiguration) conf).getSepConnectionParams();
     * 
     * where SepConfiguration extends Configuration
     */
  }
  
  SepConnectionParams getParams() {
    return params;
  }
  
  @Override
  protected void replicateBatch(List<? extends Row> actions, Object[] results, TableName tableName) 
      throws IOException, InterruptedException {
    params.getSepConsumer().replicateBatch(actions, results, tableName);
  }
  
  
  /** Hacky way to pass non-primitive parameters to a {@link SepConnection} associated with a given subscriberId */
  static final class SepConnectionParamsMap { 

    private final Map<String, SepConnectionParams> map = Collections.synchronizedMap(new HashMap());

    public SepConnectionParams get(String subscriptionId) {
      return map.get(subscriptionId);
    }
    
    public void put(String subscriptionId, SepConnectionParams conf) {
      map.put(subscriptionId, conf);
    }
    
    public void remove(String subscriptionId) {
      map.remove(subscriptionId);
    }
    
  }

} 