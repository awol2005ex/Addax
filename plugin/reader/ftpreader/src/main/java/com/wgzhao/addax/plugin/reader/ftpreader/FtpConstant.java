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

package com.wgzhao.addax.plugin.reader.ftpreader;

import com.wgzhao.addax.core.base.Constant;

public class FtpConstant extends Constant
{

    public FtpConstant() {super();}

    public static final int DEFAULT_FTP_PORT = 21;
    public static final int DEFAULT_SFTP_PORT = 22;
    public static final int DEFAULT_TIMEOUT_MS = 60000;
    public static final int DEFAULT_MAX_TRAVERSAL_LEVEL = 100;
    public static final String DEFAULT_FTP_CONNECT_PATTERN = "PASV";
}
