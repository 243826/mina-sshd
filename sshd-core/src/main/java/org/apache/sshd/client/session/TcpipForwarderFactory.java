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
package org.apache.sshd.client.session;

import org.apache.sshd.ClientSession;

/**
 * A factory for creating TcpipForwarder objects for client Port forwarding
 */
public interface TcpipForwarderFactory {

    /**
     * Creates the TcpipForwarder to be used for TCP/IP port forwards for
     * this ClientSession.
     *
     * @param clientsession the ClientSession the connections are forwarded through
     * @return the TcpipForwarder that will listen for connections and set up forwarding
     */
    public TcpipForwarder createTcpipForwarder(ClientSession clientsession);

}