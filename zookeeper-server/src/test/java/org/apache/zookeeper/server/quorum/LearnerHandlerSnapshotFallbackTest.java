/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LearnerHandlerSnapshotFallbackTest {
    LearnerHandlerTest.MockZKDatabase db;
    LearnerHandler handler;
    Leader leader;
    long forwardingZxid;
    boolean forwardingUnderLock;
    int packetsBeforeForwarding;

    @BeforeEach void setup() throws Exception {
        db = new LearnerHandlerTest().new MockZKDatabase(null);
        leader = mock(Leader.class);
        when(leader.getZKDatabase()).thenReturn(db);
        when(leader.startForwarding(any(LearnerHandler.class), anyLong())).thenAnswer(call -> {
            forwardingZxid = call.getArgument(1);
            forwardingUnderLock = db.lock.getReadHoldCount() > 0;
            packetsBeforeForwarding = handler.getQueuedPackets().size();
            handler.queuePacket(new QuorumPacket(Leader.PROPOSAL, 10L, null, null));
            return 10L;
        });
        Socket socket = mock(Socket.class);
        when(socket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("127.0.0.1", 1));
        handler = new LearnerHandler(socket, new BufferedInputStream(new ByteArrayInputStream(new byte[0])), leader) {
            @Override protected boolean shouldSendMarkerPacketForLogging() { return false; }
        };
    }

    Proposal proposal(long zxid) {
        return new Proposal(new QuorumPacket(Leader.PROPOSAL, zxid, null, null));
    }

    void snapshot(long peer) throws Exception {
        assertTrue(handler.syncFollower(peer, leader), "Divergent history requires authoritative SNAP");
        assertEquals(db.lastProcessedZxid, forwardingZxid);
        assertTrue(forwardingUnderLock);
        assertEquals(0, packetsBeforeForwarding, "Abandoned DIFF/TRUNC history must be cleared before forwarding");
        assertEquals(1, handler.getQueuedPackets().size(), "Forwarded proposal must survive SNAP selection");
        assertEquals(10L, handler.getQueuedPackets().peek().getZxid());
        assertEquals(LearnerHandler.packetSize(handler.getQueuedPackets().peek()),
                handler.getLearnerHandlerInfo().get("queued_packets_size"));
    }

    @Test void aheadOfLeaderUsesSnapshot() throws Exception {
        db.lastProcessedZxid = 5L;
        snapshot(6L);
    }

    @Test void divergentCommittedHistoryUsesSnapshot() throws Exception {
        db.lastProcessedZxid = 9L;
        db.committedLog.add(proposal(2L));
        db.committedLog.add(proposal(3L));
        db.committedLog.add(proposal(5L));
        snapshot(4L);
    }

    @Test void divergentDiskHistoryUsesSnapshot() throws Exception {
        db.lastProcessedZxid = 9L;
        for (long zxid : new long[]{2L, 3L, 5L, 6L, 7L, 8L, 9L}) db.txnLog.add(proposal(zxid));
        for (long zxid : new long[]{6L, 7L, 8L}) db.committedLog.add(proposal(zxid));
        snapshot(4L);
    }

    @Test void incompleteDiskHistoryClearsQueuedDiffBeforeForwarding() throws Exception {
        db.lastProcessedZxid = 9L;
        for (long zxid : new long[]{2L, 3L, 4L}) db.txnLog.add(proposal(zxid));
        for (long zxid : new long[]{7L, 8L}) db.committedLog.add(proposal(zxid));
        snapshot(3L);
    }

    @Test void matchingHistoryKeepsDiffAndExactProposalSequence() throws Exception {
        db.lastProcessedZxid = 9L;
        for (long zxid : new long[]{2L, 3L, 5L, 6L, 7L, 8L}) db.committedLog.add(proposal(zxid));
        assertFalse(handler.syncFollower(3L, leader));
        assertEquals(8L, forwardingZxid);
        assertTrue(forwardingUnderLock);
        assertEquals(9, packetsBeforeForwarding);
        java.util.Iterator<QuorumPacket> packets = handler.getQueuedPackets().iterator();
        QuorumPacket diff = packets.next();
        assertEquals(Leader.DIFF, diff.getType());
        assertEquals(8L, diff.getZxid());
        for (long zxid : new long[]{5L, 6L, 7L, 8L}) {
            QuorumPacket proposal = packets.next();
            assertEquals(Leader.PROPOSAL, proposal.getType());
            assertEquals(zxid, proposal.getZxid());
            QuorumPacket commit = packets.next();
            assertEquals(Leader.COMMIT, commit.getType());
            assertEquals(zxid, commit.getZxid());
        }
        assertEquals(10L, packets.next().getZxid());
        assertFalse(packets.hasNext());
    }
}
