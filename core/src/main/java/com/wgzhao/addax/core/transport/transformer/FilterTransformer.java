/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.core.transport.transformer;

import com.wgzhao.addax.core.element.BoolColumn;
import com.wgzhao.addax.core.element.BytesColumn;
import com.wgzhao.addax.core.element.Column;
import com.wgzhao.addax.core.element.DateColumn;
import com.wgzhao.addax.core.element.DoubleColumn;
import com.wgzhao.addax.core.element.LongColumn;
import com.wgzhao.addax.core.element.Record;
import com.wgzhao.addax.core.element.StringColumn;
import com.wgzhao.addax.core.exception.AddaxException;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

import static com.wgzhao.addax.core.spi.ErrorCode.ILLEGAL_VALUE;
import static com.wgzhao.addax.core.spi.ErrorCode.RUNTIME_ERROR;

/**
 * no comments.
 * Created by liqiang on 16/3/4.
 */
public class FilterTransformer
        extends Transformer
{
    public FilterTransformer()
    {
        setTransformerName("dx_filter");
    }

    @Override
    public Record evaluate(Record record, Object... paras)
    {

        int columnIndex;
        String code;
        String value;

        try {
            if (paras.length != 3) {
                throw new RuntimeException("The dx_filter parameters must be 3");
            }

            columnIndex = (Integer) paras[0];
            code = paras[1].toString().toLowerCase();
            value = (String) paras[2];

            if (StringUtils.isEmpty(value)) {
                throw new RuntimeException("The second parameter of dx_filter cannot be null");
            }
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(ILLEGAL_VALUE,
                    "paras:" + Arrays.asList(paras) + " => " + e.getMessage());
        }

        Column column = record.getColumn(columnIndex);

        try {
            switch (code) {
                case "like":
                    return doLike(record, value, column);
                case "not like":
                    return doNotLike(record, value, column);
                case ">":
                    return doGreat(record, value, column, false);
                case "<":
                    return doLess(record, value, column, false);
                case "=":
                case "==":
                    return doEqual(record, value, column);
                case "!=":
                    return doNotEqual(record, value, column);
                case ">=":
                    return doGreat(record, value, column, true);
                case "<=":
                    return doLess(record, value, column, true);
                default:
                    throw new RuntimeException("dx_filter code:" + code + " is unsupported");
            }
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(
                    RUNTIME_ERROR, e.getMessage(), e);
        }
    }

    private Record doGreat(Record record, String value, Column column, boolean hasEqual)
    {

        //如果字段为空，直接不参与比较。即空也属于无穷小
        if (column.getRawData() == null) {
            return record;
        }
        if (column instanceof DoubleColumn) {
            Double ori = column.asDouble();
            double val = Double.parseDouble(value);

            if (hasEqual) {
                if (ori >= val) {
                    return null;
                }
                else {
                    return record;
                }
            }
            else {
                if (ori > val) {
                    return null;
                }
                else {
                    return record;
                }
            }
        }
        else if (column instanceof LongColumn || column instanceof DateColumn) {
            Long ori = column.asLong();
            long val = Long.parseLong(value);

            if (hasEqual) {
                if (ori >= val) {
                    return null;
                }
                else {
                    return record;
                }
            }
            else {
                if (ori > val) {
                    return null;
                }
                else {
                    return record;
                }
            }
        }
        else if (column instanceof StringColumn
                || column instanceof BytesColumn
                || column instanceof BoolColumn) {
            String ori = column.asString();
            if (hasEqual) {
                if (ori.compareTo(value) >= 0) {
                    return null;
                }
                else {
                    return record;
                }
            }
            else {
                if (ori.compareTo(value) > 0) {
                    return null;
                }
                else {
                    return record;
                }
            }
        }
        else {
            throw new RuntimeException(">=,> can't support this columnType:"
                    + column.getClass().getSimpleName());
        }
    }

    private Record doLess(Record record, String value, Column column, boolean hasEqual)
    {

        //如果字段为空，直接不参与比较。即空也属于无穷大
        if (column.getRawData() == null) {
            return record;
        }

        if (column instanceof DoubleColumn) {
            Double ori = column.asDouble();
            double val = Double.parseDouble(value);

            if (hasEqual) {
                if (ori <= val) {
                    return null;
                }
                else {
                    return record;
                }
            }
            else {
                if (ori < val) {
                    return null;
                }
                else {
                    return record;
                }
            }
        }
        else if (column instanceof LongColumn || column instanceof DateColumn) {
            Long ori = column.asLong();
            long val = Long.parseLong(value);

            if (hasEqual) {
                if (ori <= val) {
                    return null;
                }
                else {
                    return record;
                }
            }
            else {
                if (ori < val) {
                    return null;
                }
                else {
                    return record;
                }
            }
        }
        else if (column instanceof StringColumn
                || column instanceof BytesColumn
                || column instanceof BoolColumn) {
            String ori = column.asString();
            if (hasEqual) {
                if (ori.compareTo(value) <= 0) {
                    return null;
                }
                else {
                    return record;
                }
            }
            else {
                if (ori.compareTo(value) < 0) {
                    return null;
                }
                else {
                    return record;
                }
            }
        }
        else {
            throw new RuntimeException("<=,< can't support this columnType:"
                    + column.getClass().getSimpleName());
        }
    }

    /**
     * DateColumn将比较long值，StringColumn，ByteColumn以及BooleanColumn比较其String值
     *
     * @param record message record
     * @param value value to compared
     * @param column the column of record
     * @return Record
     */
    private Record doEqual(Record record, String value, Column column)
    {

        //如果字段为空，只比较目标字段为"null"，否则null字段均不过滤
        if (column.getRawData() == null) {
            if ("null".equalsIgnoreCase(value)) {
                return null;
            }
            else {
                return record;
            }
        }

        if (column instanceof DoubleColumn) {
            Double ori = column.asDouble();
            double val = Double.parseDouble(value);

            if (ori == val) {
                return null;
            }
            else {
                return record;
            }
        }
        else if (column instanceof LongColumn || column instanceof DateColumn) {
            Long ori = column.asLong();
            long val = Long.parseLong(value);

            if (ori == val) {
                return null;
            }
            else {
                return record;
            }
        }
        else if (column instanceof StringColumn
                || column instanceof BytesColumn
                || column instanceof BoolColumn) {
            String ori = column.asString();
            if (ori.compareTo(value) == 0) {
                return null;
            }
            else {
                return record;
            }
        }
        else {
            throw new RuntimeException("== can't support this columnType:"
                    + column.getClass().getSimpleName());
        }
    }

    /**
     * For DateColumn, it will compare long value, for StringColumn, ByteColumn and BooleanColumn, it will compare their String value.
     * @param record message record
     * @param value value to compare
     * @param column the column of record
     * @return Record
     */
    private Record doNotEqual(Record record, String value, Column column)
    {

        //如果字段为空，只比较目标字段为"null", 否则null字段均过滤。
        if (column.getRawData() == null) {
            if ("null".equalsIgnoreCase(value)) {
                return record;
            }
            else {
                return null;
            }
        }

        if (column instanceof DoubleColumn) {
            Double ori = column.asDouble();
            double val = Double.parseDouble(value);

            if (ori != val) {
                return null;
            }
            else {
                return record;
            }
        }
        else if (column instanceof LongColumn || column instanceof DateColumn) {
            Long ori = column.asLong();
            long val = Long.parseLong(value);

            if (ori != val) {
                return null;
            }
            else {
                return record;
            }
        }
        else if (column instanceof StringColumn
                || column instanceof BytesColumn
                || column instanceof BoolColumn) {
            String ori = column.asString();
            if (ori.compareTo(value) != 0) {
                return null;
            }
            else {
                return record;
            }
        }
        else {
            throw new RuntimeException("== can't support this columnType:"
                    + column.getClass().getSimpleName());
        }
    }

    private Record doLike(Record record, String value, Column column)
    {
        String originalValue = column.asString();
        if (originalValue != null && originalValue.matches(value)) {
            return null;
        }
        else {
            return record;
        }
    }

    private Record doNotLike(Record record, String value, Column column)
    {
        String originalValue = column.asString();
        if (originalValue != null && originalValue.matches(value)) {
            return record;
        }
        else {
            return null;
        }
    }
}
