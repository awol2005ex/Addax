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

package com.wgzhao.addax.plugin.writer.streamwriter;

import com.wgzhao.addax.core.element.Column;
import com.wgzhao.addax.core.element.Record;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordReceiver;
import com.wgzhao.addax.core.spi.Writer;
import com.wgzhao.addax.core.util.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.ILLEGAL_VALUE;
import static com.wgzhao.addax.core.spi.ErrorCode.IO_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.PERMISSION_ERROR;

public class StreamWriter
        extends Writer
{
    private static String buildFilePath(String path, String fileName)
    {
        boolean isEndWithSeparator = false;
        switch (IOUtils.DIR_SEPARATOR) {
            case IOUtils.DIR_SEPARATOR_UNIX:
                isEndWithSeparator = path.endsWith(String
                        .valueOf(IOUtils.DIR_SEPARATOR));
                break;
            case IOUtils.DIR_SEPARATOR_WINDOWS:
                isEndWithSeparator = path.endsWith(String
                        .valueOf(IOUtils.DIR_SEPARATOR_WINDOWS));
                break;
            default:
                break;
        }
        if (!isEndWithSeparator) {
            path = path + IOUtils.DIR_SEPARATOR;
        }
        return String.format("%s%s", path, fileName);
    }

    public static class Job
            extends Writer.Job
    {

        private Configuration originalConfig;

        @Override
        public void init()
        {
            this.originalConfig = getPluginJobConf();

            String path = this.originalConfig.getString(StreamKey.PATH, null);
            String fileName = this.originalConfig.getString(StreamKey.FILE_NAME, null);

            if (StringUtils.isNoneBlank(path) && StringUtils.isNoneBlank(fileName)) {
                validateParameter(path, fileName);
            }
        }

        private void validateParameter(String path, String fileName)
        {
            try {
                File dir = new File(path);
                if (dir.isFile()) {
                    throw AddaxException
                            .asAddaxException(
                                    ILLEGAL_VALUE,
                                   "The path you configured is a file, not a directory.");
                }
                if (!dir.exists()) {
                    boolean createdOk = dir.mkdirs();
                    if (!createdOk) {
                        throw AddaxException
                                .asAddaxException(
                                        CONFIG_ERROR,
                                        "Failed to create directory: " + path);
                    }
                }

                String fileFullPath = buildFilePath(path, fileName);
                File newFile = new File(fileFullPath);
                if (newFile.exists()) {
                    try {
                        FileUtils.forceDelete(newFile);
                    }
                    catch (IOException e) {
                        throw AddaxException.asAddaxException(
                                IO_ERROR,
                                "Failed to delete file: ", e);
                    }
                }
            }
            catch (SecurityException se) {
                throw AddaxException.asAddaxException(
                        PERMISSION_ERROR,
                        "The permission is denied to create file", se);
            }
        }

        @Override
        public List<Configuration> split(int mandatoryNumber)
        {
            List<Configuration> writerSplitConfigs = new ArrayList<>();
            for (int i = 0; i < mandatoryNumber; i++) {
                writerSplitConfigs.add(this.originalConfig);
            }

            return writerSplitConfigs;
        }

        @Override
        public void destroy()
        {
            //
        }
    }

    public static class Task
            extends Writer.Task
    {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);
        private static final String NEWLINE_FLAG = System.getProperty("line.separator", "\n");

        private String fieldDelimiter;
        private boolean print;

        private String path;
        private String fileName;

        private long recordNumBeforeSleep;
        private long sleepTime;

        private String nullFormat;

        @Override
        public void init()
        {
            Configuration writerSliceConfig = getPluginJobConf();

            this.fieldDelimiter = writerSliceConfig.getString(StreamKey.FIELD_DELIMITER, "\t");
            this.print = writerSliceConfig.getBool(StreamKey.PRINT, true);

            this.path = writerSliceConfig.getString(StreamKey.PATH, null);
            this.fileName = writerSliceConfig.getString(StreamKey.FILE_NAME, null);
            this.recordNumBeforeSleep = writerSliceConfig.getLong(StreamKey.RECORD_NUM_BEFORE_SLEEP, 0);
            this.sleepTime = writerSliceConfig.getLong(StreamKey.SLEEP_TIME, 0);
            this.nullFormat = writerSliceConfig.getString(StreamKey.NULL_FORMAT, StreamKey.NULL_FLAG);
            if (recordNumBeforeSleep < 0) {
                throw AddaxException.asAddaxException(ILLEGAL_VALUE, "recordNumber must be greater than 0");
            }
            if (sleepTime < 0) {
                throw AddaxException.asAddaxException(ILLEGAL_VALUE, "sleep time must be greater than 0");
            }
        }

        @Override
        public void startWrite(RecordReceiver recordReceiver)
        {

            if (StringUtils.isNoneBlank(path) && StringUtils.isNoneBlank(fileName)) {
                writeToFile(recordReceiver, path, fileName, recordNumBeforeSleep, sleepTime);
            }
            else if (this.print) {
                try {
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

                    Record record;
                    while ((record = recordReceiver.getFromReader()) != null) {
                        writer.write(recordToString(record));
                    }
                    writer.flush();
                }
                catch (IOException e) {
                    throw AddaxException.asAddaxException(IO_ERROR, e);
                }
            }
        }

        private void writeToFile(RecordReceiver recordReceiver, String path, String fileName,
                long recordNumBeforeSleep, long sleepTime)
        {

            LOG.info("begin do write...");
            String fileFullPath = buildFilePath(path, fileName);
            LOG.info("write to file : [{}]", fileFullPath);
            File newFile = new File(fileFullPath);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(newFile, true), StandardCharsets.UTF_8))) {
                Record record;
                int count = 0;
                while ((record = recordReceiver.getFromReader()) != null) {
                    if (recordNumBeforeSleep > 0 && sleepTime > 0 && count == recordNumBeforeSleep) {
                        LOG.info("StreamWriter start to sleep ... recordNumBeforeSleep={},sleepTime={}", recordNumBeforeSleep, sleepTime);
                        TimeUnit.SECONDS.sleep(sleepTime);
                        count=0;
                    }
                    writer.write(recordToString(record));
                    count++;
                }
                writer.flush();
            }
            catch (IOException | InterruptedException e) {
                throw AddaxException.asAddaxException(IO_ERROR, e);
            }
        }

        @Override
        public void destroy()
        {
            //
        }

        private String recordToString(Record record)
        {
            int recordLength = record.getColumnNumber();
            if (0 == recordLength) {
                return NEWLINE_FLAG;
            }

            Column column;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < recordLength; i++) {
                column = record.getColumn(i);
                if (column != null && column.getRawData() != null) {
                    sb.append(column.asString());
                }
                else {
                    // use NULL FLAG to replace null value
                    sb.append(nullFormat);
                }
                sb.append(fieldDelimiter);
            }
            sb.setLength(sb.length() - 1);
            sb.append(NEWLINE_FLAG);

            return sb.toString();
        }
    }
}
