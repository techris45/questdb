/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.wal.seq;

import io.questdb.cairo.*;
import io.questdb.cairo.pool.ex.PoolClosedException;
import io.questdb.griffin.engine.ops.AlterOperation;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.FilesFacade;
import io.questdb.std.QuietCloseable;
import io.questdb.std.str.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.questdb.cairo.TableUtils.META_FILE_NAME;
import static io.questdb.cairo.wal.WalUtils.*;
import static io.questdb.cairo.wal.seq.TableTransactionLog.MAX_TXN_OFFSET;

public class TableSequencerAPI implements QuietCloseable {
    private static final Log LOG = LogFactory.getLog(TableSequencerAPI.class);
    private final CairoConfiguration configuration;
    private final CairoEngine engine;
    private final long inactiveTtlUs;
    private final Function<TableToken, TableSequencerEntry> openSequencerInstanceLambda;
    private final int recreateDistressedSequencerAttempts;
    private final ConcurrentHashMap<TableToken, TableSequencerEntry> seqRegistry = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TableSequencerAPI(CairoEngine engine, CairoConfiguration configuration) {
        this.configuration = configuration;
        this.engine = engine;
        this.openSequencerInstanceLambda = this::openSequencerInstance;
        this.inactiveTtlUs = configuration.getInactiveWalWriterTTL() * 1000;
        this.recreateDistressedSequencerAttempts = configuration.getWalRecreateDistressedSequencerAttempts();
    }

    @Override
    public void close() {
        closed = true;
        releaseAll();
    }

    public void dropTable(CharSequence tableName, TableToken tableToken, boolean failedCreate) {
        LOG.info().$("dropping wal table [name=").utf8(tableName).$(", privateTableName=").utf8(tableToken.getPrivateTableName()).I$();
        try (TableSequencerImpl seq = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                seq.dropTable();
            } finally {
                seq.unlockWrite();
            }
        } catch (CairoException e) {
            LOG.info().$("failed to drop wal table [name=").utf8(tableName).$(", privateTableName=").utf8(tableToken.getPrivateTableName()).I$();
            if (!failedCreate) {
                throw e;
            }
        }
    }

    public void forAllWalTables(final RegisteredTable callback) {
        final CharSequence root = configuration.getRoot();
        final FilesFacade ff = configuration.getFilesFacade();
        Path path = Path.PATH.get();

        for (TableToken tableToken : engine.getTableTokens()) {
            if (tableToken.isWal() || engine.isTableDropped(tableToken)) {
                long lastTxn;
                int tableId;

                String publicTableName = tableToken.getLoggingName();
                try {
                    if (!seqRegistry.containsKey(tableToken)) {
                        // Fast path.
                        // The following calls are racy, i.e. there might be a sequencer modifying both
                        // metadata and log concurrently as we read the values. It's ok since we iterate
                        // through the WAL tables periodically, so eventually we should see the updates.
                        path.of(root).concat(tableToken.getPrivateTableName()).concat(SEQ_DIR);
                        long fdMeta = -1;
                        long fdTxn = -1;
                        try {
                            fdMeta = openFileRO(ff, path, META_FILE_NAME);
                            fdTxn = openFileRO(ff, path, TXNLOG_FILE_NAME);
                            tableId = ff.readNonNegativeInt(fdMeta, SEQ_META_TABLE_ID);
                            lastTxn = ff.readNonNegativeLong(fdTxn, MAX_TXN_OFFSET);
                        } finally {
                            if (fdMeta > -1) {
                                ff.close(fdMeta);
                            }
                            if (fdTxn > -1) {
                                ff.close(fdTxn);
                            }
                        }
                    } else {
                        // Slow path.
                        try (TableSequencer tableSequencer = openSequencerLocked(tableToken, SequencerLockType.NONE)) {
                            lastTxn = tableSequencer.lastTxn();
                            tableId = tableSequencer.getTableId();
                        }
                    }
                } catch (CairoException ex) {
                    LOG.critical().$("could not read WAL table metadata [table=").utf8(publicTableName).$(", errno=").$(ex.getErrno())
                            .$(", error=").$((Throwable) ex).I$();
                    continue;
                }

                if (tableId < 0 || lastTxn < 0) {
                    LOG.critical().$("could not read WAL table metadata [table=").utf8(publicTableName).$(", tableId=").$(tableId)
                            .$(", lastTxn=").$(lastTxn).I$();
                    continue;
                }

                try {
                    callback.onTable(tableId, tableToken, lastTxn);
                } catch (CairoException ex) {
                    LOG.critical().$("could not process table sequencer [table=").utf8(publicTableName).$(", errno=").$(ex.getErrno())
                            .$(", error=").$((Throwable) ex).I$();
                }
            }
        }
    }

    public @NotNull TransactionLogCursor getCursor(final TableToken tableToken, long seqTxn) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            TransactionLogCursor cursor;
            try {
                cursor = tableSequencer.getTransactionLogCursor(seqTxn);
            } finally {
                tableSequencer.unlockRead();
            }
            return cursor;
        }
    }

    public @NotNull TableMetadataChangeLog getMetadataChangeLogCursor(final TableToken tableToken, long structureVersionLo) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            TableMetadataChangeLog metadataChangeLog;
            try {
                metadataChangeLog = tableSequencer.getMetadataChangeLogCursor(structureVersionLo);
            } finally {
                tableSequencer.unlockRead();
            }
            return metadataChangeLog;
        }
    }

    public int getNextWalId(final TableToken tableToken) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            int walId;
            try {
                walId = tableSequencer.getNextWalId();
            } finally {
                tableSequencer.unlockRead();
            }
            return walId;
        }
    }

    public long getTableMetadata(final TableToken tableToken, final TableRecordMetadataSink sink) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            try {
                return tableSequencer.getTableMetadata(sink);
            } finally {
                tableSequencer.unlockRead();
            }
        }
    }

    @TestOnly
    public boolean isSuspended(final TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            boolean isSuspended;
            try {
                isSuspended = sequencer.isSuspended();
            } finally {
                sequencer.unlockRead();
            }
            return isSuspended;
        }
    }

    public long lastTxn(final TableToken tableName) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableName, SequencerLockType.READ)) {
            long lastTxn;
            try {
                lastTxn = sequencer.lastTxn();
            } finally {
                sequencer.unlockRead();
            }
            return lastTxn;
        }
    }

    public long nextStructureTxn(final TableToken tableToken, long structureVersion, AlterOperation operation) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            long txn;
            try {
                txn = tableSequencer.nextStructureTxn(structureVersion, operation);
            } finally {
                tableSequencer.unlockWrite();
            }
            return txn;
        }
    }

    public long nextTxn(final TableToken tableToken, int walId, long expectedSchemaVersion, int segmentId, long segmentTxn) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            long txn;
            try {
                txn = tableSequencer.nextTxn(expectedSchemaVersion, walId, segmentId, segmentTxn);
            } finally {
                tableSequencer.unlockWrite();
            }
            return txn;
        }
    }

    public void registerTable(int tableId, final TableStructure tableStructure, final TableToken tableToken) {
        try (
                TableSequencerImpl tableSequencer = getTableSequencerEntry(tableToken, SequencerLockType.WRITE, (key) -> {
                    TableSequencerEntry sequencer = new TableSequencerEntry(this, this.engine, tableToken);
                    sequencer.create(tableId, tableStructure);
                    sequencer.open();
                    return sequencer;
                })
        ) {
            tableSequencer.unlockWrite();
        }
    }

    public boolean releaseAll() {
        return releaseAll(Long.MAX_VALUE);
    }

    public boolean releaseInactive() {
        return releaseAll(configuration.getMicrosecondClock().getTicks() - inactiveTtlUs);
    }

    public void reloadMetadataConditionally(
            final TableToken tableToken,
            long expectedStructureVersion,
            TableRecordMetadataSink sink
    ) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            try {
                if (tableSequencer.getStructureVersion() != expectedStructureVersion) {
                    tableSequencer.getTableMetadata(sink);
                }
            } finally {
                tableSequencer.unlockRead();
            }
        }
    }

    public void renameWalTable(TableToken tableToken, TableToken newTableToken) {
        assert tableToken.getPrivateTableName().equals(newTableToken.getPrivateTableName());
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                tableSequencer.rename(newTableToken);
            } finally {
                tableSequencer.unlockWrite();
            }
        }
        LOG.advisory().$("renamed wal table [table=")
                .utf8(tableToken.getLoggingName()).$(", newName=").utf8(newTableToken.getLoggingName())
                .$(", privateTableName=").utf8(newTableToken.getPrivateTableName()).I$();
    }


    @TestOnly
    public void setDistressed(TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                sequencer.setDistressed();
            } finally {
                sequencer.unlockWrite();
            }
        }
    }

    public void suspendTable(final TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                sequencer.suspendTable();
            } finally {
                sequencer.unlockWrite();
            }
        }
    }

    private static long openFileRO(FilesFacade ff, Path path, CharSequence fileName) {
        final int rootLen = path.length();
        path.concat(fileName).$();
        try {
            return TableUtils.openRO(ff, path, LOG);
        } finally {
            path.trimTo(rootLen);
        }
    }

    @NotNull
    private TableSequencerEntry getTableSequencerEntry(TableToken tableToken, SequencerLockType lock, Function<TableToken, TableSequencerEntry> getSequencerLambda) {
        TableSequencerEntry entry;
        int attempt = 0;
        while (attempt < recreateDistressedSequencerAttempts) {
            throwIfClosed();
            entry = seqRegistry.computeIfAbsent(tableToken, getSequencerLambda);
            if (lock == SequencerLockType.READ) {
                entry.readLock();
            } else if (lock == SequencerLockType.WRITE) {
                entry.writeLock();
            }

            boolean isDistressed = entry.isDistressed();
            if (!isDistressed && !entry.isClosed()) {
                return entry;
            } else {
                if (lock == SequencerLockType.READ) {
                    entry.unlockRead();
                } else if (lock == SequencerLockType.WRITE) {
                    entry.unlockWrite();
                }
            }
            if (isDistressed) {
                attempt++;
            }
        }

        throw CairoException.critical(0).put("sequencer is distressed [table=").put(tableToken.getPrivateTableName()).put(']');
    }

    private TableSequencerEntry openSequencerInstance(TableToken tableToken) {
        TableSequencerEntry sequencer = new TableSequencerEntry(this, this.engine, tableToken);
        sequencer.open();
        return sequencer;
    }

    @NotNull
    private TableSequencerEntry openSequencerLocked(TableToken tableName, SequencerLockType lock) {
        return getTableSequencerEntry(tableName, lock, this.openSequencerInstanceLambda);
    }

    private boolean releaseEntries(long deadline) {
        if (seqRegistry.size() == 0) {
            // nothing to release
            return true;
        }
        boolean removed = false;
        for (TableToken tableSystemName : seqRegistry.keySet()) {
            final TableSequencerEntry sequencer = seqRegistry.get(tableSystemName);
            if (sequencer != null && deadline >= sequencer.releaseTime && !sequencer.isClosed()) {
                // Remove from registry only if this thread closed the instance
                if (sequencer.checkClose()) {
                    LOG.info().$("releasing idle table sequencer [table=").utf8(tableSystemName.getPrivateTableName()).I$();
                    seqRegistry.remove(tableSystemName, sequencer);
                    removed = true;
                }
            }
        }
        return removed;
    }

    private void throwIfClosed() {
        if (closed) {
            LOG.info().$("is closed").$();
            throw PoolClosedException.INSTANCE;
        }
    }

    protected boolean releaseAll(long deadline) {
        return releaseEntries(deadline);
    }

    enum SequencerLockType {
        WRITE,
        READ,
        NONE
    }

    @FunctionalInterface
    public interface RegisteredTable {
        void onTable(int tableId, final TableToken tableName, long lastTxn);
    }

    private static class TableSequencerEntry extends TableSequencerImpl {
        private final TableSequencerAPI pool;
        private volatile long releaseTime = Long.MAX_VALUE;

        TableSequencerEntry(TableSequencerAPI pool, CairoEngine engine, TableToken tableToken) {
            super(engine, tableToken);
            this.pool = pool;
        }

        @Override
        public void close() {
            if (!pool.closed) {
                if (!isDistressed() && !isDropped()) {
                    releaseTime = pool.configuration.getMicrosecondClock().getTicks();
                } else {
                    // Sequencer is distressed or dropped, close before removing from the pool.
                    // Remove from registry only if this thread closed the instance.
                    if (checkClose()) {
                        LOG.info().$("closed distressed table sequencer [table=").$(getTableName()).I$();
                        pool.seqRegistry.remove(getTableName(), this);
                    }
                }
            } else {
                super.close();
            }
        }
    }
}
