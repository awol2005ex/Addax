/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package com.wgzhao.addax.plugin.writer.starrockswriter.manager;

import java.util.List;

public class StarRocksFlushTuple
{

    private String label;
    private final Long bytes;
    private final List<byte[]> rows;

    public StarRocksFlushTuple(String label, Long bytes, List<byte[]> rows)
    {
        this.label = label;
        this.bytes = bytes;
        this.rows = rows;
    }

    public String getLabel() {return label;}

    public void setLabel(String label) {this.label = label;}

    public Long getBytes() {return bytes;}

    public List<byte[]> getRows() {return rows;}
}
