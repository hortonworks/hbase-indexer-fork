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
package com.ngdata.hbaseindexer.model.api;

import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * Represents a single Indexer process for a single index on a single host.
 */
public class IndexerProcess {
    
    private String indexerName;
    private String hostName;
    private String error;
    
    public IndexerProcess(String indexerName, String hostName, String error) {
        this.indexerName = indexerName;
        this.hostName = hostName;
        this.error = error;
    }
    
    /**
     * Get the name of the indexer that this process is running for.
     */
    public String getIndexerName() {
        return indexerName;
    }
    
    /**
     * Get the name of the host where this indexer process is running.
     */
    public String getHostName() {
        return hostName;
    }

    /**
     * Returns true if the indexer process was properly started up, otherwise false.
     */
    public boolean isRunning() {
        return error == null;
    }
    
    /**
     * Get the error string that occurred when starting up this indexer process. If the process
     * is running correctly, this method will return null.
     */
    public String getError() {
        return error;
    }

    
    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
    }
    
}
