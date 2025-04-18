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

package com.wgzhao.addax.core.transport.exchanger;

import com.wgzhao.addax.core.element.Record;
import com.wgzhao.addax.core.spi.ErrorCode;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordReceiver;
import com.wgzhao.addax.core.plugin.RecordSender;
import com.wgzhao.addax.core.plugin.TaskPluginCollector;
import com.wgzhao.addax.core.util.Configuration;
import com.wgzhao.addax.core.statistics.communication.Communication;
import com.wgzhao.addax.core.transport.channel.Channel;
import com.wgzhao.addax.core.transport.record.TerminateRecord;
import com.wgzhao.addax.core.transport.transformer.TransformerExecution;
import com.wgzhao.addax.core.util.container.CoreConstant;
import org.apache.commons.lang3.Validate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;

public class BufferedRecordTransformerExchanger
        extends TransformerExchanger
        implements RecordSender, RecordReceiver
{

    private static Class<? extends Record> RECORD_CLASS;
    protected final int byteCapacity;
    private final Channel channel;
    private final List<Record> buffer;
    private final AtomicInteger memoryBytes = new AtomicInteger(0);
    private int bufferSize;
    private int bufferIndex = 0;
    private volatile boolean shutdown = false;

    @SuppressWarnings("unchecked")
    public BufferedRecordTransformerExchanger(int taskGroupId, int taskId,
            Channel channel, Communication communication,
            TaskPluginCollector pluginCollector,
            List<TransformerExecution> tInfoExecs)
    {
        super(taskGroupId, taskId, communication, tInfoExecs, pluginCollector);
        assert null != channel;
        assert null != channel.getConfiguration();

        this.channel = channel;
        Configuration configuration = channel.getConfiguration();

        this.bufferSize = configuration.getInt(CoreConstant.CORE_TRANSPORT_EXCHANGER_BUFFER_SIZE, 32);
        this.buffer = new ArrayList<>(bufferSize);

        //channel的queue默认大小为8M，原来为64M
        this.byteCapacity = configuration.getInt(
                CoreConstant.CORE_TRANSPORT_CHANNEL_CAPACITY_BYTE, 8 * 1024 * 1024);

        try {
            BufferedRecordTransformerExchanger.RECORD_CLASS = ((Class<? extends Record>) Class
                    .forName(configuration.getString(
                            CoreConstant.CORE_TRANSPORT_RECORD_CLASS,
                            "com.wgzhao.addax.core.transport.record.DefaultRecord")));
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(CONFIG_ERROR, e);
        }
    }

    @Override
    public Record createRecord()
    {
        try {
            return BufferedRecordTransformerExchanger.RECORD_CLASS.getConstructor().newInstance();
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(CONFIG_ERROR, e);
        }
    }

    @Override
    public void sendToWriter(Record record)
    {
        if (shutdown) {
            throw AddaxException.asAddaxException(ErrorCode.SHUT_DOWN_TASK, "");
        }

        Validate.notNull(record, "The record cannot be empty.");

        record = doTransformer(record);

        if (record == null) {
            return;
        }

        if (record.getMemorySize() > this.byteCapacity) {
            this.pluginCollector.collectDirtyRecord(record,
                    new Exception(String.format("A single record exceeds the size limit. The current limit is %d", this.byteCapacity)));
            return;
        }

        boolean isFull = (this.bufferIndex >= this.bufferSize
                || this.memoryBytes.get() + record.getMemorySize() > this.byteCapacity);
        if (isFull) {
            flush();
        }

        this.buffer.add(record);
        this.bufferIndex++;
        memoryBytes.addAndGet(record.getMemorySize());
    }

    @Override
    public void flush()
    {
        if (shutdown) {
            throw AddaxException.asAddaxException(ErrorCode.SHUT_DOWN_TASK, "");
        }
        this.channel.pushAll(this.buffer);
        //和channel的统计保持同步
        doStat();
        this.buffer.clear();
        this.bufferIndex = 0;
        this.memoryBytes.set(0);
    }

    @Override
    public void terminate()
    {
        if (shutdown) {
            throw AddaxException.asAddaxException(ErrorCode.SHUT_DOWN_TASK, "");
        }
        flush();
        this.channel.pushTerminate(TerminateRecord.get());
    }

    @Override
    public Record getFromReader()
    {
        if (shutdown) {
            throw AddaxException.asAddaxException(ErrorCode.SHUT_DOWN_TASK, "");
        }
        boolean isEmpty = (this.bufferIndex >= this.buffer.size());
        if (isEmpty) {
            receive();
        }

        Record record = this.buffer.get(this.bufferIndex++);
        if (record instanceof TerminateRecord) {
            record = null;
        }
        return record;
    }

    @Override
    public void shutdown()
    {
        shutdown = true;
        try {
            buffer.clear();
            channel.clear();
        }
        catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void receive()
    {
        this.channel.pullAll(this.buffer);
        this.bufferIndex = 0;
        this.bufferSize = this.buffer.size();
    }
}
