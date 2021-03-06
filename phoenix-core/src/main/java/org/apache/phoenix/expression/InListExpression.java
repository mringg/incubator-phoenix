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
package org.apache.phoenix.expression;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.index.util.ImmutableBytesPtr;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.WritableUtils;
import org.apache.phoenix.expression.visitor.ExpressionVisitor;
import org.apache.phoenix.schema.ConstraintViolationException;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.util.ByteUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/*
 * Implementation of a SQL foo IN (a,b,c) expression. Other than the first
 * expression, child expressions must be constants.
 *
 */
public class InListExpression extends BaseSingleExpression {
    private LinkedHashSet<ImmutableBytesPtr> values;
    private ImmutableBytesPtr minValue;
    private ImmutableBytesPtr maxValue;
    private int valuesByteLength;
    private boolean containsNull;
    private int fixedWidth = -1;
    private ImmutableBytesPtr value = new ImmutableBytesPtr();
    private List<Expression> keyExpressions; // client side only

    public static Expression create (List<Expression> children, boolean isNegate, ImmutableBytesWritable ptr) throws SQLException {
        Expression firstChild = children.get(0);
        PDataType firstChildType = firstChild.getDataType();
        
        if (firstChild.isStateless() && (!firstChild.evaluate(null, ptr) || ptr.getLength() == 0)) {
            return LiteralExpression.newConstant(null, PDataType.BOOLEAN, firstChild.isDeterministic());
        }
        if (children.size() == 2) {
            return ComparisonExpression.create(isNegate ? CompareOp.NOT_EQUAL : CompareOp.EQUAL, children, ptr);
        }
        
        boolean addedNull = false;
        List<Expression> keys = Lists.newArrayListWithExpectedSize(children.size());
        List<Expression> coercedKeyExpressions = Lists.newArrayListWithExpectedSize(children.size());
        keys.add(firstChild);
        coercedKeyExpressions.add(firstChild);
        for (int i = 1; i < children.size(); i++) {
            Expression rhs = children.get(i);
            if (rhs.evaluate(null, ptr)) {
                if (ptr.getLength() == 0) {
                    if (!addedNull) {
                        addedNull = true;
                        keys.add(LiteralExpression.newConstant(null, PDataType.VARBINARY, true));
                        coercedKeyExpressions.add(LiteralExpression.newConstant(null, firstChildType, true));
                    }
                } else {
                    // Don't specify the firstChild column modifier here, as we specify it in the LiteralExpression creation below
                    try {
                        firstChildType.coerceBytes(ptr, rhs.getDataType(), rhs.getColumnModifier(), null);
                        keys.add(LiteralExpression.newConstant(ByteUtil.copyKeyBytesIfNecessary(ptr), PDataType.VARBINARY, firstChild.getColumnModifier(), true));
                        if(rhs.getDataType() == firstChildType) {
                            coercedKeyExpressions.add(rhs);
                        } else {
                            coercedKeyExpressions.add(CoerceExpression.create(rhs, firstChildType));    
                        }
                    } catch (ConstraintViolationException e) { // Ignore and continue
                    }
                }
            }
            
        }
        if (keys.size() == 1) {
            return LiteralExpression.newConstant(false, PDataType.BOOLEAN, true);
        }
        if (keys.size() == 2 && addedNull) {
            return LiteralExpression.newConstant(null, PDataType.BOOLEAN, true);
        }
        // TODO: if inChildren.isEmpty() then Oracle throws a type mismatch exception. This means
        // that none of the list elements match in type and there's no null element. We'd return
        // false in this case. Should we throw?
        Expression expression = new InListExpression(keys, coercedKeyExpressions);
        if (isNegate) { 
            expression = NotExpression.create(expression, ptr);
        }
        if (expression.isStateless()) {
            if (!expression.evaluate(null, ptr) || ptr.getLength() == 0) {
                return LiteralExpression.newConstant(null,expression.getDataType(), expression.isDeterministic());
            }
            Object value = expression.getDataType().toObject(ptr);
            return LiteralExpression.newConstant(value, expression.getDataType(), expression.isDeterministic());
        }
        return expression;
    }
    
    public InListExpression() {
    }

    private InListExpression(List<Expression> keys, List<Expression> keyExpressions) throws SQLException {
        super(keyExpressions.get(0));
        this.keyExpressions = keyExpressions.subList(1, keyExpressions.size());
        Set<ImmutableBytesPtr> values = Sets.newHashSetWithExpectedSize(keys.size()-1);
        int fixedWidth = -1;
        boolean isFixedLength = true;
        for (int i = 1; i < keys.size(); i++) {
            ImmutableBytesPtr ptr = new ImmutableBytesPtr();
            Expression child = keys.get(i);
            assert(child.getDataType() == PDataType.VARBINARY);
            child.evaluate(null, ptr);
            if (ptr.getLength() == 0) {
                containsNull = true;
            } else {
                if (values.add(ptr)) {
                    int length = ptr.getLength();
                    if (fixedWidth == -1) {
                        fixedWidth = length;
                    } else {
                        isFixedLength &= fixedWidth == length;
                    }
                    
                    valuesByteLength += ptr.getLength();
                }
            }
        }
        this.fixedWidth = isFixedLength ? fixedWidth : -1;
        // Sort values by byte value so we can get min/max easily
        ImmutableBytesPtr[] valuesArray = values.toArray(new ImmutableBytesPtr[values.size()]);
        Arrays.sort(valuesArray, ByteUtil.BYTES_PTR_COMPARATOR);
        this.minValue = valuesArray[0];
        this.maxValue = valuesArray[valuesArray.length-1];
        this.values = new LinkedHashSet<ImmutableBytesPtr>(Arrays.asList(valuesArray));
    }

    @Override
    public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
        if (!getChild().evaluate(tuple, ptr)) {
            return false;
        }
        value.set(ptr);
        if (values.contains(value)) {
            ptr.set(PDataType.TRUE_BYTES);
            return true;
        }
        if (containsNull) { // If any null value and value not found
            ptr.set(ByteUtil.EMPTY_BYTE_ARRAY);
            return true;
        }
        ptr.set(PDataType.FALSE_BYTES);
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (containsNull ? 1231 : 1237);
        result = prime * result + values.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        InListExpression other = (InListExpression)obj;
        if (containsNull != other.containsNull) return false;
        if (!values.equals(other.values)) return false;
        return true;
    }

    @Override
    public PDataType getDataType() {
        return PDataType.BOOLEAN;
    }

    @Override
    public boolean isNullable() {
        return super.isNullable() || containsNull;
    }

    private int readValue(DataInput input, byte[] valuesBytes, int offset, ImmutableBytesPtr ptr) throws IOException {
        int valueLen = fixedWidth == -1 ? WritableUtils.readVInt(input) : fixedWidth;
        values.add(new ImmutableBytesPtr(valuesBytes,offset,valueLen));
        return offset + valueLen;
    }
    
    @Override
    public void readFields(DataInput input) throws IOException {
        super.readFields(input);
        containsNull = input.readBoolean();
        fixedWidth = WritableUtils.readVInt(input);
        byte[] valuesBytes = Bytes.readByteArray(input);
        valuesByteLength = valuesBytes.length;
        int len = fixedWidth == -1 ? WritableUtils.readVInt(input) : valuesByteLength / fixedWidth;
        values = Sets.newLinkedHashSetWithExpectedSize(len);
        int offset = 0;
        int i  = 0;
        if (i < len) {
            offset = readValue(input, valuesBytes, offset, minValue = new ImmutableBytesPtr());
            while (++i < len-1) {
                offset = readValue(input, valuesBytes, offset, new ImmutableBytesPtr());
            }
            if (i < len) {
                offset = readValue(input, valuesBytes, offset, maxValue = new ImmutableBytesPtr());
            } else {
                maxValue = minValue;
            }
        } else {
            minValue = maxValue = new ImmutableBytesPtr(ByteUtil.EMPTY_BYTE_ARRAY);
        }
    }

    @Override
    public void write(DataOutput output) throws IOException {
        super.write(output);
        output.writeBoolean(containsNull);
        WritableUtils.writeVInt(output, fixedWidth);
        WritableUtils.writeVInt(output, valuesByteLength);
        for (ImmutableBytesPtr ptr : values) {
            output.write(ptr.get(), ptr.getOffset(), ptr.getLength());
        }
        if (fixedWidth == -1) {
            WritableUtils.writeVInt(output, values.size());
            for (ImmutableBytesPtr ptr : values) {
                WritableUtils.writeVInt(output, ptr.getLength());
            }
        }
    }

    @Override
    public final <T> T accept(ExpressionVisitor<T> visitor) {
        List<T> l = acceptChildren(visitor, visitor.visitEnter(this));
        T t = visitor.visitLeave(this, l);
        if (t == null) {
            t = visitor.defaultReturn(this, l);
        }
        return t;
    }

    public List<Expression> getKeyExpressions() {
        return keyExpressions;
    }

    public ImmutableBytesWritable getMinKey() {
        return minValue;
    }

    public ImmutableBytesWritable getMaxKey() {
        return maxValue;
    }

    @Override
    public String toString() {
        int maxToStringLen = 200;
        Expression firstChild = children.get(0);
        PDataType type = firstChild.getDataType();
        StringBuilder buf = new StringBuilder(firstChild + " IN (");
        if (containsNull) {
            buf.append("null,");
        }
        for (ImmutableBytesPtr value : values) {
            if (firstChild.getColumnModifier() != null) {
                type.coerceBytes(value, type, firstChild.getColumnModifier(), null);
            }
            buf.append(type.toStringLiteral(value, null));
            buf.append(',');
            if (buf.length() >= maxToStringLen) {
                buf.append("... ");
                break;
            }
        }
        buf.setCharAt(buf.length()-1,')');
        return buf.toString();
    }
}
