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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.junit.jupiter.api.Test;

public class FLELeaderRetirementTest extends ZKTestCase {

    @Test
    public void testReceiverKeepsLeaderWhenPeerClearsIt() throws Exception {
        receiveLookingNotification(true, true);
    }

    @Test
    public void testReceiverReportsToCurrentLeader() throws Exception {
        receiveLookingNotification(true, false);
    }

    @Test
    public void testReceiverRepliesWithoutLeader() throws Exception {
        receiveLookingNotification(false, false);
    }

    private void receiveLookingNotification(boolean hasLeader, boolean clearDuringReport) throws Exception {
        QuorumPeer peer = mock(QuorumPeer.class);
        QuorumCnxManager manager = mock(QuorumCnxManager.class);
        Map<Long, QuorumServer> voters = new HashMap<>();
        for (long sid = 1; sid <= 3; sid++) {
            voters.put(sid, new QuorumServer(sid, new InetSocketAddress("127.0.0.1", (int) sid)));
        }
        when(peer.getMyId()).thenReturn(1L);
        when(peer.getQuorumVerifier()).thenReturn(new QuorumMaj(voters));
        when(peer.getCurrentAndNextConfigVoters()).thenReturn(voters.keySet());
        when(peer.getVotingView()).thenReturn(voters);
        when(peer.getLearnerType()).thenReturn(QuorumPeer.LearnerType.PARTICIPANT);
        when(peer.getLastLoggedZxid()).thenReturn(1L);
        when(peer.getCurrentEpoch()).thenReturn(1L);
        AtomicReference<ServerState> state = new AtomicReference<>(ServerState.LOOKING);
        when(peer.getPeerState()).thenAnswer(invocation -> state.get());
        doAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            return null;
        }).when(peer).setPeerState(any(ServerState.class));

        FastLeaderElection election = new FastLeaderElection(peer, manager);
        for (long sid = 1; sid <= 2; sid++) {
            FastLeaderElection.Notification vote = new FastLeaderElection.Notification();
            vote.sid = sid;
            vote.leader = 1;
            vote.zxid = 1;
            vote.electionEpoch = 1;
            vote.peerEpoch = 1;
            vote.state = ServerState.LOOKING;
            election.recvqueue.offer(vote);
        }
        Vote elected = election.lookForLeader();
        assertEquals(1L, elected.getId());
        assertEquals(ServerState.LEADING, state.get());
        when(peer.getCurrentVote()).thenReturn(elected);
        election.sendqueue.clear();

        Leader leader = mock(Leader.class);
        peer.leader = hasLeader ? leader : null;
        doAnswer(invocation -> {
            if (clearDuringReport) {
                peer.leader = null;
            }
            return null;
        }).when(leader).setLeadingVoteSet(any(SyncedLearnerTracker.class));

        FastLeaderElection.Messenger.WorkerReceiver receiver = election.messenger.wr;
        QuorumCnxManager.Message message = new QuorumCnxManager.Message(
            FLETestUtils.createMsg(ServerState.LOOKING.ordinal(), 1, 1, 1), 2);
        when(manager.pollRecvQueue(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(message).thenAnswer(invocation -> {
            receiver.stop = true;
            return null;
        });
        assertDoesNotThrow(receiver::run);
        if (hasLeader) {
            verify(leader).setLeadingVoteSet(any(SyncedLearnerTracker.class));
            verify(leader).reportLookingSid(2);
        } else {
            verify(leader, never()).reportLookingSid(anyLong());
        }
        FastLeaderElection.ToSend reply = election.sendqueue.poll();
        assertNotNull(reply);
        assertEquals(2L, reply.sid);
        assertEquals(1L, reply.leader);
    }

}
