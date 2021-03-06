/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sshd.client.subsystem.sftp.extensions;

import java.io.IOException;

import org.apache.sshd.client.subsystem.sftp.SftpClient.Handle;

/**
 * Implements the &quot;copy-data&quot; extension
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 * @see    <A HREF="http://tools.ietf.org/id/draft-ietf-secsh-filexfer-extensions-00.txt">DRAFT 00 section 7</A>
 */
public interface CopyDataExtension extends SftpClientExtension {
    void copyData(Handle readHandle, long readOffset, long readLength, Handle writeHandle, long writeOffset) throws IOException;
}
