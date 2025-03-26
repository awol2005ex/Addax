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

package com.wgzhao.addax.plugin.writer.hbase11xsqlwriter;

import com.wgzhao.addax.core.plugin.RecordReceiver;
import com.wgzhao.addax.core.spi.Writer;
import com.wgzhao.addax.core.util.Configuration;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

public class HbaseSQLWriter
        extends Writer
{
    public static class Job
            extends Writer.Job
    {
        private HbaseSQLWriterConfig config;

        @Override
        public void init()
        {
            config = HbaseSQLHelper.parseConfig(this.getPluginJobConf());
            HbaseSQLHelper.validateConfig(config);
        }

        @Override
        public void prepare()
        {
            if (config.truncate()) {
                Connection conn = HbaseSQLHelper.getJdbcConnection(config);
                HbaseSQLHelper.truncateTable(conn, config.getTableName());
            }
        }

        @Override
        public List<Configuration> split(int mandatoryNumber)
        {
            List<Configuration> splitResultConfigs = new ArrayList<>();
            for (int j = 0; j < mandatoryNumber; j++) {
                splitResultConfigs.add(config.getOriginalConfig().clone());
            }
            return splitResultConfigs;
        }

        @Override
        public void destroy()
        {
            // NOOP
        }
    }

    public static class Task
            extends Writer.Task
    {
        private HbaseSQLWriterTask hbaseSQLWriterTask;

        @Override
        public void init()
        {
            Configuration taskConfig = getPluginJobConf();
            this.hbaseSQLWriterTask = new HbaseSQLWriterTask(taskConfig);
        }

        @Override
        public void startWrite(RecordReceiver lineReceiver)
        {
            this.hbaseSQLWriterTask.startWriter(lineReceiver, getTaskPluginCollector());
        }

        @Override
        public void destroy()
        {
            //
        }
    }
}
