/*
 * Copyright 2014 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.query;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.client.KeyValueBuilder;
import org.apache.phoenix.compile.MutationPlan;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.SequenceKey;


public interface ConnectionQueryServices extends QueryServices, MetaDataMutated {
    /**
     * Get (and create if necessary) a child QueryService for a given tenantId.
     * The QueryService will be cached for the lifetime of the parent QueryService
     * @param tenantId the organization ID
     * @return the child QueryService
     */
    public ConnectionQueryServices getChildQueryServices(ImmutableBytesWritable tenantId);

    /**
     * Get an HTableInterface by the given name. It is the callers
     * responsibility to close the returned HTableInterface.
     * @param tableName the name of the HTable
     * @return the HTableInterface
     * @throws SQLException 
     */
    public HTableInterface getTable(byte[] tableName) throws SQLException;

    public HTableDescriptor getTableDescriptor(byte[] tableName) throws SQLException;

    public StatsManager getStatsManager();

    public List<HRegionLocation> getAllTableRegions(byte[] tableName) throws SQLException;

    public PhoenixConnection connect(String url, Properties info) throws SQLException;

    public MetaDataMutationResult getTable(byte[] tenantId, byte[] schemaName, byte[] tableName, long tableTimestamp, long clientTimetamp) throws SQLException;
    public MetaDataMutationResult createTable(List<Mutation> tableMetaData, byte[] tableName, PTableType tableType, Map<String,Object> tableProps, List<Pair<byte[],Map<String,Object>>> families, byte[][] splits) throws SQLException;
    public MetaDataMutationResult dropTable(List<Mutation> tableMetadata, PTableType tableType) throws SQLException;
    public MetaDataMutationResult addColumn(List<Mutation> tableMetaData, PTableType tableType, List<Pair<byte[],Map<String,Object>>> families) throws SQLException;
    public MetaDataMutationResult dropColumn(List<Mutation> tableMetadata, PTableType tableType) throws SQLException;
    public MetaDataMutationResult updateIndexState(List<Mutation> tableMetadata, String parentTableName) throws SQLException;
    public MutationState updateData(MutationPlan plan) throws SQLException;

    public void init(String url, Properties props) throws SQLException;

    public int getLowestClusterHBaseVersion();
    public HBaseAdmin getAdmin() throws SQLException;

    void clearTableRegionCache(byte[] tableName) throws SQLException;

    boolean hasInvalidIndexConfiguration();
    
    long createSequence(String tenantId, String schemaName, String sequenceName, long startWith, long incrementBy, int batchSize, long timestamp) throws SQLException;
    long dropSequence(String tenantId, String schemaName, String sequenceName, long timestamp) throws SQLException;
    void reserveSequenceValues(List<SequenceKey> sequenceKeys, long timestamp, long[] values, SQLException[] exceptions) throws SQLException;
    void incrementSequenceValues(List<SequenceKey> sequenceKeys, long timestamp, long[] values, SQLException[] exceptions) throws SQLException;
    long getSequenceValue(SequenceKey sequenceKey, long timestamp) throws SQLException;
    void returnSequenceValues(List<SequenceKey> sequenceKeys, long timestamp, SQLException[] exceptions) throws SQLException;
    void addConnection(PhoenixConnection connection) throws SQLException;
    void removeConnection(PhoenixConnection connection) throws SQLException;

    /**
     * @return the {@link KeyValueBuilder} that is valid for the locally installed version of HBase.
     */
    public KeyValueBuilder getKeyValueBuilder();
    
    public enum Feature {REVERSE_SCAN};
    public boolean supportsFeature(Feature feature);
}