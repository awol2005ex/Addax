/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */

package com.wgzhao.addax.plugin.reader.excelreader;

import com.wgzhao.addax.core.base.Key;
import com.wgzhao.addax.core.element.Record;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordSender;
import com.wgzhao.addax.core.spi.Reader;
import com.wgzhao.addax.core.util.Configuration;
import com.wgzhao.addax.storage.util.FileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.REQUIRED_VALUE;

public class ExcelReader
        extends Reader
{
    public static class Job
            extends Reader.Job
    {

        private static final Logger LOG = LoggerFactory.getLogger(Job.class);

        private Configuration originConfig = null;
        private List<String> sourceFiles;

        @Override
        public void init()
        {
            this.originConfig = this.getPluginJobConf();
            // Compatible with the old version, path is a string before
            String pathInString = this.originConfig.getNecessaryValue(Key.PATH, REQUIRED_VALUE);
            List<String> path;
            if (!pathInString.startsWith("[") && !pathInString.endsWith("]")) {
                path = new ArrayList<>();
                path.add(pathInString);
            }
            else {
                path = this.originConfig.getList(Key.PATH, String.class);
                if (null == path || path.isEmpty()) {
                    throw AddaxException.asAddaxException(REQUIRED_VALUE, "the path is required");
                }
            }

            this.sourceFiles = FileHelper.buildSourceTargets(path);
            if (sourceFiles.isEmpty()) {
                throw AddaxException.asAddaxException(CONFIG_ERROR,
                        "Cannot find any file in path: " + path + ", assuring the path(s) exists and has right permission");
            }
            LOG.info("The number of files to read is: [{}]", this.sourceFiles.size());
        }

        @Override
        public void destroy()
        {
            //
        }

        @Override
        public List<Configuration> split(int adviceNumber)
        {
            LOG.debug("Begin to split...");
            List<Configuration> readerSplitConfigs = new ArrayList<>();

            int splitNumber = Math.min(this.sourceFiles.size(), adviceNumber);

            List<List<String>> splitSourceFiles = FileHelper.splitSourceFiles(this.sourceFiles, splitNumber);
            for (List<String> files : splitSourceFiles) {
                Configuration splitConfig = this.originConfig.clone();
                splitConfig.set(Key.SOURCE_FILES, files);
                readerSplitConfigs.add(splitConfig);
            }
            LOG.debug("Split finished...");
            return readerSplitConfigs;
        }
    }

    public static class Task
            extends Reader.Task
    {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);

        private List<String> sourceFiles;
        private boolean header = false;
        private int skipRows = 0;

        @Override
        public void init()
        {
            Configuration readerSliceConfig = this.getPluginJobConf();
            this.sourceFiles = readerSliceConfig.getList(Key.SOURCE_FILES, String.class);
            this.header = readerSliceConfig.getBool("header", false);
            if (this.header) {
                LOG.info("The first row is skipped as a table header");
            }
            this.skipRows = readerSliceConfig.getInt("skipRows", 0);
            if (this.skipRows > 0) {
                LOG.info("The first {} rows is skipped", this.skipRows);
            }
        }

        @Override
        public void destroy()
        {
            //
        }

        @Override
        public void startRead(RecordSender recordSender)
        {
            for (String file : sourceFiles) {
                LOG.info("begin read file {}", file);
                ExcelHelper excelHelper = new ExcelHelper(header, skipRows);
                excelHelper.open(file);
                Record record = excelHelper.readLine(recordSender.createRecord());
                while (record != null) {
                    recordSender.sendToWriter(record);
                    record = excelHelper.readLine(recordSender.createRecord());
                }
                excelHelper.close();
            }
        }
    }
}
