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
package com.ngdata.hbaseindexer.conf;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import com.google.common.collect.Maps;
import com.ngdata.hbaseindexer.conf.FieldDefinition.ValueSource;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * Represents a document extractor that functions on the content of one or more cells in an HBase {@code Result}.
 */
public class DocumentExtractDefinition {

    private String prefix;
    private ValueSource valueSource;
    private String valueExpression;
    private String mimeType;
    private Map<String,String> params;

    public DocumentExtractDefinition(String prefix, String valueExpression, ValueSource valueSource, String mimeType) {
        this(prefix, valueExpression, valueSource, mimeType, Maps.<String,String>newHashMap());
    }
    
    public DocumentExtractDefinition(String prefix, String valueExpression, ValueSource valueSource, String mimeType, Map<String,String> params) {
        checkNotNull(valueExpression, "valueExpression");
        checkNotNull(valueSource, "valueSource");
        checkNotNull(mimeType, "mimeType");
        checkNotNull(params, "params");
        this.prefix = prefix == null ? "" : prefix;
        this.valueSource = valueSource;
        this.valueExpression = valueExpression;
        this.mimeType = mimeType;
        this.params = params;
    }

    /**
     * Get the optional prefix for field names in extracted documents.
     * 
     * @return the prefix, which may be an empty string
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Get the location from which data will be taken from HBase {@code Result} objects.
     */
    public ValueSource getValueSource() {
        return valueSource;
    }

    /**
     * Get the HBase columnfamily:columnqualifier expression to be used for extracting data from HBase {@code Result}
     * objects.
     */
    public String getValueExpression() {
        return valueExpression;
    }

    /**
     * Get the MIME type to be used by Tika for parsing binary content.
     */
    public String getMimeType() {
        return mimeType;
    }
    
    /**
     * Get the configuration parameters for the extract definition.
     */
    public Map<String, String> getParams() {
        return params;
    }
    
    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }
    
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
    
    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
    }

}
