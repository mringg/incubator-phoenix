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

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

import org.apache.phoenix.compile.MutationPlan;
import org.apache.phoenix.coprocessor.MetaDataProtocol;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MutationCode;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PMetaData;
import org.apache.phoenix.schema.PMetaDataImpl;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.SchemaUtil;


/**
 *
 * Implementation of ConnectionQueryServices used in testing where no connection to
 * an hbase cluster is necessary.
 * 
 * 
 * @since 0.1
 */
public class ConnectionlessQueryServicesImpl extends DelegateQueryServices implements ConnectionQueryServices  {
    private PMetaData metaData;

    public ConnectionlessQueryServicesImpl(QueryServices queryServices) {
        super(queryServices);
        metaData = PMetaDataImpl.EMPTY_META_DATA;
    }

    @Override
    public ConnectionQueryServices getChildQueryServices(ImmutableBytesWritable childId) {
        return this; // Just reuse the same query services
    }

    @Override
    public HTableInterface getTable(byte[] tableName) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public StatsManager getStatsManager() {
        return new StatsManager() {

            @Override
            public byte[] getMinKey(TableRef table) {
                return HConstants.EMPTY_START_ROW;
            }

            @Override
            public byte[] getMaxKey(TableRef table) {
                return HConstants.EMPTY_END_ROW;
            }

            @Override
            public void updateStats(TableRef table) throws SQLException {
            }
        };
    }

    @Override
    public List<HRegionLocation> getAllTableRegions(byte[] tableName) throws SQLException {
        return Collections.singletonList(new HRegionLocation(new HRegionInfo(tableName, HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW),"localhost",-1));
    }

    @Override
    public PMetaData addTable(PTable table) throws SQLException {
        return metaData = metaData.addTable(table);
    }

    @Override
    public PMetaData addColumn(String tableName, List<PColumn> columns, long tableTimeStamp, long tableSeqNum,
            boolean isImmutableRows) throws SQLException {
        return metaData = metaData.addColumn(tableName, columns, tableTimeStamp, tableSeqNum, isImmutableRows);
    }

    @Override
    public PMetaData removeTable(String tableName)
            throws SQLException {
        return metaData = metaData.removeTable(tableName);
    }

    @Override
    public PMetaData removeColumn(String tableName, String familyName, String columnName, long tableTimeStamp,
            long tableSeqNum) throws SQLException {
        return metaData = metaData.removeColumn(tableName, familyName, columnName, tableTimeStamp, tableSeqNum);
    }

    
    @Override
    public PhoenixConnection connect(String url, Properties info) throws SQLException {
        return new PhoenixConnection(this, url, info, metaData);
    }

    @Override
    public MetaDataMutationResult getTable(byte[] schemaBytes, byte[] tableBytes, long tableTimestamp, long clientTimestamp) throws SQLException {
        // Return result that will cause client to use it's own metadata instead of needing
        // to get anything from the server (since we don't have a connection)
        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, 0, null);
    }

    @Override
    public MetaDataMutationResult createTable(List<Mutation> tableMetaData, PTableType tableType, Map<String,Object> tableProps, List<Pair<byte[],Map<String,Object>>> families, byte[][] splits) throws SQLException {
        return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND, 0, null);
    }

    @Override
    public MetaDataMutationResult dropTable(List<Mutation> tableMetadata, PTableType tableType) throws SQLException {
        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, 0, null);
    }

    @Override
    public MetaDataMutationResult addColumn(List<Mutation> tableMetaData, PTableType readOnly, List<Pair<byte[],Map<String,Object>>> families) throws SQLException {
        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, 0, null);
    }

    @Override
    public MetaDataMutationResult dropColumn(List<Mutation> tableMetadata, PTableType tableType) throws SQLException {
        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, 0, null);
    }

    @Override
    public void init(String url, Properties props) throws SQLException {
        props = new Properties(props);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(MetaDataProtocol.MIN_SYSTEM_TABLE_TIMESTAMP));
        PhoenixConnection metaConnection = new PhoenixConnection(this, url, props, PMetaDataImpl.EMPTY_META_DATA);
        SQLException sqlE = null;
        try {
            metaConnection.createStatement().executeUpdate(QueryConstants.CREATE_METADATA);
        } catch (SQLException e) {
            sqlE = e;
        } finally {
            try {
                metaConnection.close();
            } catch (SQLException e) {
                if (sqlE != null) {
                    sqlE.setNextException(e);
                } else {
                    sqlE = e;
                }
            }
            if (sqlE != null) {
                throw sqlE;
            }
        }
    }

    @Override
    public MutationState updateData(MutationPlan plan) throws SQLException {
        return new MutationState(0, plan.getConnection());
    }

    @Override
    public int getLowestClusterHBaseVersion() {
        return Integer.MAX_VALUE; // Allow everything for connectionless
    }

    @Override
    public HBaseAdmin getAdmin() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public MetaDataMutationResult updateIndexState(List<Mutation> tableMetadata, String parentTableName) throws SQLException {
        byte[][] rowKeyMetadata = new byte[2][];
        SchemaUtil.getVarChars(tableMetadata.get(0).getRow(), rowKeyMetadata);
        KeyValue newKV = tableMetadata.get(0).getFamilyMap().get(TABLE_FAMILY_BYTES).get(0);
        PIndexState newState =  PIndexState.fromSerializedValue(newKV.getBuffer()[newKV.getValueOffset()]);
        String schemaName = Bytes.toString(rowKeyMetadata[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX]);
        String indexName = Bytes.toString(rowKeyMetadata[PhoenixDatabaseMetaData.TABLE_NAME_INDEX]);
        String indexTableName = SchemaUtil.getTableName(schemaName, indexName);
        PTable index = metaData.getTable(indexTableName);
        index = PTableImpl.makePTable(index,newState == PIndexState.USABLE ? PIndexState.ACTIVE : newState == PIndexState.UNUSABLE ? PIndexState.INACTIVE : newState);
        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, 0, index);
    }

    @Override
    public HTableDescriptor getTableDescriptor(byte[] tableName) throws SQLException {
        return null;
    }

    @Override
    public void clearTableRegionCache(byte[] tableName) throws SQLException {
    }

    @Override
    public boolean hasInvalidIndexConfiguration() {
        return false;
    }
}