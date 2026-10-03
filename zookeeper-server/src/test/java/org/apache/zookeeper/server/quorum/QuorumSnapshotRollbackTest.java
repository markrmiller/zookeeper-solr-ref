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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;

class QuorumSnapshotRollbackTest extends QuorumPeerTestBase {
    static final String PARENT = "/queue";
    static final String CHILD = PARENT + "/qn-0000000001";

    void await(BooleanSupplier state, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!state.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(state.getAsBoolean(), message);
    }

    @Test
    void divergentMemberJoinsThenRestartsBeforeSequentialEnqueue() throws Exception {
        MainThread[] members = new MainThread[3];
        ZooKeeper client = null;
        int[] ports = new int[3];
        StringBuilder config = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            ports[i] = PortAssignment.unique();
            config.append("server.").append(i + 1).append("=127.0.0.1:").append(PortAssignment.unique())
                .append(":").append(PortAssignment.unique()).append(":participant;127.0.0.1:").append(ports[i]).append("\n");
        }
        try {
            for (int i = 0; i < 3; i++) {
                members[i] = new MainThread(i + 1, ports[i], config.toString(), "admin.enableServer=false\n");
                Path data = members[i].tmpDir.toPath().resolve("data");
                FileTxnSnapLog store = new FileTxnSnapLog(data.toFile(), data.toFile());
                DataTree tree = new DataTree();
                ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
                store.save(tree, sessions, true);
                Record[] records = {
                    new CreateTxn(PARENT, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1),
                    new CreateTxn(PARENT + "/old", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1),
                    new DeleteTxn(PARENT + "/old")
                };
                for (int t = 0; t < records.length; t++) {
                    long zxid = 0x100000001L + t;
                    int type = t == 2 ? ZooDefs.OpCode.delete : ZooDefs.OpCode.create;
                    TxnHeader header = new TxnHeader(0L, t, zxid, System.currentTimeMillis(), type);
                    assertEquals(0, tree.processTxn(header, records[t]).err);
                    assertTrue(store.append(new Request(header, records[t], null)));
                }
                store.commit();
                if (i == 1) {
                    TxnHeader header = new TxnHeader(0L, 4, 0x100000004L, System.currentTimeMillis(), ZooDefs.OpCode.create);
                    assertEquals(0, tree.processTxn(header, new CreateTxn(CHILD,
                        "divergent".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 2)).err);
                    // A fuzzy snapshot can contain a node newer than its filename boundary.
                    tree.lastProcessedZxid = 0x100000003L;
                    store.save(tree, sessions, true);
                    tree.lastProcessedZxid = 0x100000004L;
                    store.save(tree, sessions, true);
                }
                ZKDatabase prepared = new ZKDatabase(store);
                try {
                    prepared.loadDataBase();
                    if (i == 1) {
                        assertEquals(0x100000004L, prepared.getDataTreeLastProcessedZxid());
                        assertEquals(0x100000004L, prepared.getNode(CHILD).stat.getCzxid());
                        assertEquals(2, prepared.getNode(PARENT).stat.getCversion());
                    } else {
                        assertEquals(0x100000003L, prepared.getDataTreeLastProcessedZxid());
                        assertNull(prepared.getNode(CHILD));
                        assertEquals(1, prepared.getNode(PARENT).stat.getCversion());
                        assertEquals(0x100000003L, prepared.getmaxCommittedLog());
                    }
                } finally {
                    prepared.close();
                }
            }
            // Establish authority with the consistent majority before admitting divergent history.
            members[0].start();
            members[2].start();
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + ports[0], 30000));
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + ports[2], 30000));
            QuorumPeer authority = members[0].getQuorumPeer().leader != null
                    ? members[0].getQuorumPeer() : members[2].getQuorumPeer();
            assertEquals(0x100000003L, authority.getZkDb().getmaxCommittedLog(), "Leader must retain the earlier committed history");
            members[1].start();
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + ports[1], 30000));
            await(() -> members[1].getQuorumPeer().getActiveServer() != null
                    && members[1].getQuorumPeer().getActiveServer().isRunning(), "Divergent follower must finish synchronization");
            LOG.info("Joined member childPresent={} parentInternalCversion={}",
                members[1].getQuorumPeer().getZkDb().getNode(CHILD) != null,
                members[1].getQuorumPeer().getZkDb().getNode(PARENT).stat.getCversion());
            members[1].shutdown();
            assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + ports[1], 30000));
            members[1].start();
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + ports[1], 30000));
            CountDownLatch connected = new CountDownLatch(1);
            client = new ZooKeeper("127.0.0.1:" + ports[1], 30000, event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    connected.countDown();
                }
            });
            assertTrue(connected.await(30, TimeUnit.SECONDS));
            CountDownLatch synced = new CountDownLatch(1);
            AtomicInteger syncResult = new AtomicInteger(Integer.MIN_VALUE);
            client.sync(PARENT, (rc, path, ctx) -> {
                syncResult.set(rc);
                synced.countDown();
            }, null);
            assertTrue(synced.await(30, TimeUnit.SECONDS));
            assertEquals(KeeperException.Code.OK.intValue(), syncResult.get());
            LOG.info("Restarted member childPresent={}", client.exists(CHILD, false) != null);
            Stat before = client.exists(PARENT, false);
            byte[] payload = "authoritative-sequential-enqueue-after-member-restart".getBytes(StandardCharsets.UTF_8);
            String created = client.create(PARENT + "/qn-", payload, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            assertEquals(CHILD, created);
            assertEquals(2, before.getCversion());
            Stat committed = new Stat();
            assertArrayEquals(payload, client.getData(created, false, committed));
            assertTrue(committed.getCzxid() > 0x100000004L);
            for (MainThread member : members) {
                await(() -> member.getQuorumPeer().getZkDb().getNode(CHILD) != null, "Committed child must reach every member");
                ZKDatabase db = member.getQuorumPeer().getZkDb();
                assertEquals(committed.getCzxid(), db.getNode(CHILD).stat.getCzxid());
                assertArrayEquals(payload, db.getData(CHILD, new Stat(), null));
                assertEquals(2, db.getNode(PARENT).stat.getCversion());
                assertEquals(committed.getCzxid(), db.getNode(PARENT).stat.getPzxid());
            }
            LOG.info("Sequential enqueue={} parentCversionBefore={} createZxid={}", created, before.getCversion(), committed.getCzxid());
        } finally {
            if (client != null) {
                client.close();
            }
            for (MainThread member : members) {
                if (member != null) {
                    member.shutdown();
                }
            }
        }
    }
}
