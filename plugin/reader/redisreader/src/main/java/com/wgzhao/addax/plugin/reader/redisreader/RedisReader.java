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

package com.wgzhao.addax.plugin.reader.redisreader;

import com.moilioncircle.redis.replicator.FileType;
import com.moilioncircle.redis.replicator.RedisReplicator;
import com.moilioncircle.redis.replicator.Replicator;
import com.moilioncircle.redis.replicator.event.PostRdbSyncEvent;
import com.moilioncircle.redis.replicator.event.PreRdbSyncEvent;
import com.moilioncircle.redis.replicator.io.RawByteListener;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueString;
import com.moilioncircle.redis.replicator.rdb.skip.SkipRdbVisitor;
import com.wgzhao.addax.core.element.BytesColumn;
import com.wgzhao.addax.core.element.LongColumn;
import com.wgzhao.addax.core.element.Record;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordSender;
import com.wgzhao.addax.core.spi.Reader;
import com.wgzhao.addax.core.util.Configuration;
import com.wgzhao.addax.plugin.reader.redisreader.impl.SentinelReplicator;
import org.apache.hc.client5.http.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.HostAndPort;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_HASH;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_HASH_ZIPLIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_HASH_ZIPMAP;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_LIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_LIST_QUICKLIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_LIST_ZIPLIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_MODULE;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_MODULE_2;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_SET;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_SET_INTSET;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_STREAM_LISTPACKS;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_STRING;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_ZSET;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_ZSET_2;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_ZSET_ZIPLIST;
import static com.wgzhao.addax.core.spi.ErrorCode.ILLEGAL_VALUE;
import static com.wgzhao.addax.core.spi.ErrorCode.REQUIRED_VALUE;

public class RedisReader
        extends Reader
{
    public static class Job
            extends Reader.Job
    {
        private Configuration conf;
        private List<String> uris;

        @Override
        public void init()
        {
            this.conf = getPluginJobConf();
            validateParam();
        }

        private void validateParam()
        {
            Configuration conConf = conf.getConfiguration(RedisKey.CONNECTION);
            uris = conConf.getList(RedisKey.URI, String.class);
            for (String uri : uris) {
                if (uri == null || uri.isEmpty()) {
                    throw AddaxException.asAddaxException(REQUIRED_VALUE, "uri is null or empty");
                }
                if (!(uri.startsWith("tcp") || uri.startsWith("file") || uri.startsWith("http") || uri.startsWith("https"))) {
                    throw AddaxException.asAddaxException(ILLEGAL_VALUE, "uri is not start with tcp, file, http or https");
                }
                String mode = conConf.getString(RedisKey.MODE, "standalone");
                if ("sentinel".equalsIgnoreCase(mode) && uri.startsWith("tcp")) {
                    // required other items
                    conConf.getNecessaryValue(RedisKey.MASTER_NAME, REQUIRED_VALUE);
                }
            }
        }

        @Override
        public void destroy()
        {

        }

        @Override
        public List<Configuration> split(int adviceNumber)
        {
            // ignore adviceNumber
            if (adviceNumber != uris.size() ) {
                throw AddaxException.asAddaxException(ILLEGAL_VALUE, "adviceNumber is not equal to uri size");
            }
            if (adviceNumber == 1) {
                conf.set(String.format("%s.%s", RedisKey.CONNECTION, RedisKey.URI), uris.get(0));
                return Collections.singletonList(conf);
            }

            List<Configuration> configurations = new ArrayList<>();
            for (String uri : uris) {
                Configuration clone = conf.clone();
                Configuration conConf = clone.getConfiguration(RedisKey.CONNECTION);
                conConf.set(RedisKey.URI, uri);
                clone.set(RedisKey.CONNECTION, conConf);
                configurations.add(clone);
            }
            return configurations;
        }
    }

    public static class Task
            extends Reader.Task
    {

        private static final Logger LOG = LoggerFactory.getLogger(Task.class);

        private final List<Pattern> includePatterns = new ArrayList<>();

        private final List<Pattern> excludePatterns = new ArrayList<>();

        private final Set<Integer> includeDB = new HashSet<>();

        private final Map<String, Integer> bigKey = new TreeMap<>();

        private final Map<String, Long> collectTypeMap = new HashMap<>();

        private int keyThresholdLength;

        @Override
        public void startRead(RecordSender recordSender)
        {
            Configuration pluginJobConf = getPluginJobConf();
            Configuration connection = pluginJobConf.getConfiguration(RedisKey.CONNECTION);
            try {
                String uri = connection.getString(RedisKey.URI);
                String mode = connection.getString(RedisKey.MODE, "standalone");
                String masterName = connection.getString(RedisKey.MASTER_NAME, null);
                File file = new File(UUID.randomUUID() + ".rdb");
                if (uri.startsWith("http") || uri.startsWith("https")) {
                    Request.get(uri).execute().saveContent(file);
                }
                else if (uri.startsWith("tcp")) {
                    this.dump(uriToHosts(uri), mode, connection.getString(RedisKey.AUTH), masterName, file);
                }
                else {
                    Files.copy(Paths.get(new URI(uri)), file.toPath());
                }

                LOG.info("loading {} ", file.getAbsolutePath());
                RedisReplicator r = new RedisReplicator(file, FileType.RDB, com.moilioncircle.redis.replicator.Configuration.defaultSetting());
                r.addEventListener((replicator, event) -> {
                    if (event instanceof KeyStringValueString) {
                        KeyStringValueString dkv = (KeyStringValueString) event;
                        long dbNumber = dkv.getDb().getDbNumber();
                        int rdbType = dkv.getValueRdbType();
                        byte[] key = dkv.getKey();
                        byte[] value = dkv.getValue();
                        long expire = dkv.getExpiredMs() == null ? 0 : dkv.getExpiredMs();

                        recordBigKey(dbNumber, rdbType, key, value);

                        collectType(rdbType);

                        if (Task.this.matchDB((int) dbNumber) && Task.this.matchKey(key)) {
                            Record record = recordSender.createRecord();
                            record.addColumn(new LongColumn(dbNumber));
                            record.addColumn(new LongColumn(rdbType));
                            record.addColumn(new LongColumn(expire));
                            record.addColumn(new BytesColumn(key));
                            record.addColumn(new BytesColumn(value));
                            recordSender.sendToWriter(record);
                        }
                    }
                    else {
                        LOG.warn("The type is unsupported yet");
                    }
                });
                r.open();
                r.close();
                // delete temporary local file
                Files.deleteIfExists(Paths.get(file.getAbsolutePath()));
            }
            catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        @Override
        public void init()
        {
            Configuration pluginJobConf = this.getPluginJobConf();
            List<Object> include = pluginJobConf.getList(RedisKey.INCLUDE);
            List<Object> exclude = pluginJobConf.getList(RedisKey.EXCLUDE);
            List<Object> db = pluginJobConf.getList(RedisKey.DB);
            this.keyThresholdLength = pluginJobConf.getInt(RedisKey.KEY_THRESHOLD_LENGTH, 64 * 1024 * 1024);
            if (include != null) {
                for (Object reg : include) {
                    Pattern pattern = Pattern.compile(reg.toString());
                    includePatterns.add(pattern);
                }
            }

            if (exclude != null) {
                for (Object reg : exclude) {
                    Pattern pattern = Pattern.compile(reg.toString());
                    excludePatterns.add(pattern);
                }
            }

            if (db != null) {
                for (Object num : db) {
                    includeDB.add(Integer.parseInt(String.valueOf(num)));
                }
            }
        }

        private void recordBigKey(Long db, int type, byte[] key, byte[] value)
        {
            if (value.length > keyThresholdLength) {
                bigKey.put(db + "\t" + new String(key, StandardCharsets.UTF_8), value.length);
            }
        }

        @Override
        public void destroy()
        {
        }

        private boolean matchKey(byte[] bytes)
        {
            if (includePatterns.isEmpty() && excludePatterns.isEmpty()) {
                return true;
            }

            String key = new String(bytes, StandardCharsets.UTF_8);

            for (Pattern pattern : includePatterns) {
                boolean isMatch = pattern.matcher(key).find();
                if (isMatch) {
                    return true;
                }
            }

            for (Pattern pattern : excludePatterns) {
                boolean isMatch = pattern.matcher(key).find();
                if (isMatch) {
                    return false;
                }
            }

            return false;
        }

        private boolean matchDB(int db)
        {
            return this.includeDB.isEmpty() || this.includeDB.contains(db);
        }

        /**
         * download remote db via rsync
         *
         * @param hosts list of {@link HostAndPort}
         * @param mode redis running mode, cluster, master/slave, sentinel or cluster
         * @param auth auth password
         * @param masterName master name for sentinel mode
         * @param outFile file which dump to
         * @throws IOException file not found
         */
        private void dump(List<HostAndPort> hosts, String mode, String auth, String masterName, File outFile)
                throws IOException
        {
            LOG.info("mode = {}", mode);
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(outFile.toPath()));
            RawByteListener rawByteListener = rawBytes -> {
                try {
                    out.write(rawBytes);
                }
                catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            };
            com.moilioncircle.redis.replicator.Configuration conf = com.moilioncircle.redis.replicator.Configuration.defaultSetting();
            if (null != auth && !auth.isEmpty()) {
                if (auth.contains(":")) {
                    String[] auths = auth.split(":");
                    conf.setAuthUser(auths[0]);
                    conf.setAuthPassword(auths[1]);
                }
                else {
                    conf.setAuthPassword(auth);
                }
            }
            Replicator replicator;
            if ("sentinel".equalsIgnoreCase(mode)) {
                // convert uri to hosts
                replicator = new SentinelReplicator(hosts, masterName, conf);
            }
            else {
                // defaults to standalone
                replicator = new RedisReplicator(hosts.get(0).getHost(), hosts.get(0).getPort(), conf);
            }
            replicator.setRdbVisitor(new SkipRdbVisitor(replicator));
            replicator.addEventListener((replicator1, event) -> {
                if (event instanceof PreRdbSyncEvent) {
                    replicator1.addRawByteListener(rawByteListener);
                }

                if (event instanceof PostRdbSyncEvent) {
                    replicator1.removeRawByteListener(rawByteListener);

                    try {
                        out.close();
                        replicator1.close();
                    }
                    catch (IOException e) {
                        LOG.warn(e.getMessage(), e);
                    }
                }
            });
            replicator.open();
        }

        private List<HostAndPort> uriToHosts(String uris)
        {
            List<HostAndPort> result = new ArrayList<>();
            try {
                for (String uri : uris.split(",")) {
                    URI u = new URI(uri);
                    result.add(new HostAndPort(u.getHost(), u.getPort()));
                }
            }
            catch (URISyntaxException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            return result;
        }

        private void collectType(int type)
        {
            String name = getTypeName(type);
            Long count = collectTypeMap.get(name);
            if (count == null) {
                collectTypeMap.put(name, 1L);
            }
            else {
                collectTypeMap.put(name, count + 1);
            }
        }

        private String getTypeName(int type)
        {
            switch (type) {
                case RDB_TYPE_STRING:
                    return "string";
                case RDB_TYPE_LIST:
                    return "list";
                case RDB_TYPE_SET:
                    return "set";
                case RDB_TYPE_ZSET:
                    return "zset";
                case RDB_TYPE_ZSET_2:
                    return "zset2";
                case RDB_TYPE_HASH:
                    return "hash";
                case RDB_TYPE_HASH_ZIPMAP:
                    return "hash_zipmap";
                case RDB_TYPE_LIST_ZIPLIST:
                    return "list_ziplist";
                case RDB_TYPE_SET_INTSET:
                    return "set_intset";
                case RDB_TYPE_ZSET_ZIPLIST:
                    return "zset_ziplist";
                case RDB_TYPE_HASH_ZIPLIST:
                    return "hash_ziplist";
                case RDB_TYPE_LIST_QUICKLIST:
                    return "list_quicklist";
                case RDB_TYPE_MODULE:
                    return "module";
                case RDB_TYPE_MODULE_2:
                    return "module2";
                case RDB_TYPE_STREAM_LISTPACKS:
                    return "stream_listpacks";
                default:
                    return "other";
            }
        }
    }
}
