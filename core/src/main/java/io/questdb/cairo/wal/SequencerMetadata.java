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

package io.questdb.cairo.wal;

import io.questdb.cairo.BaseRecordMetadata;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableColumnMetadata;
import io.questdb.cairo.TableDescriptor;
import io.questdb.cairo.sql.TableRecordMetadata;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryMAR;
import io.questdb.cairo.vm.api.MemoryMR;
import io.questdb.std.*;
import io.questdb.std.str.Path;

import java.io.Closeable;

import static io.questdb.cairo.TableUtils.META_FILE_NAME;
import static io.questdb.cairo.TableUtils.openSmallFile;
import static io.questdb.cairo.wal.WalUtils.*;

public class SequencerMetadata extends BaseRecordMetadata implements TableRecordMetadata, Closeable, TableDescriptor {
    public static boolean READ_ONLY = true;
    public static boolean READ_WRITE = false;

    private final FilesFacade ff;
    private final boolean readonly;
    private final MemoryMAR metaMem;
    private final MemoryMR roMetaMem;

    private long structureVersion = -1;
    private int tableId;
    private String tableName;

    public SequencerMetadata(FilesFacade ff, boolean readonly) {
        this.ff = ff;
        this.readonly = readonly;
        if (!readonly) {
            roMetaMem = metaMem = Vm.getMARInstance();
        } else {
            metaMem = null;
            roMetaMem = Vm.getMRInstance();
        }
        columnMetadata = new ObjList<>();
        columnNameIndexMap = new LowerCaseCharSequenceIntHashMap();
    }

    public void addColumn(CharSequence columnName, int columnType) {
        addColumn0(columnName, columnType);
        structureVersion++;
    }

    @Override
    public void close() {
        clear();
    }

    public void copyFrom(TableDescriptor model, String tableName, int tableId, long structureVersion) {
        reset();
        this.tableName = tableName;
        timestampIndex = model.getTimestampIndex();
        this.tableId = tableId;

        for (int i = 0; i < model.getColumnCount(); i++) {
            final CharSequence name = model.getColumnName(i);
            final int type = model.getColumnType(i);
            addColumn0(name, type);
        }

        this.structureVersion = structureVersion;
        columnCount = columnMetadata.size();
    }

    public void copyFrom(SequencerMetadata metadata) {
        copyFrom(metadata, metadata.getTableName(), metadata.getTableId(), metadata.getStructureVersion());
    }

    public void create(TableDescriptor model, String tableName, Path path, int pathLen, int tableId) {
        copyFrom(model, tableName, tableId, 0);
        dumpTo(path, pathLen);
    }

    public void dumpTo(Path path, int pathLen) {
        openSmallFile(ff, path, pathLen, metaMem, META_FILE_NAME, MemoryTag.MMAP_SEQUENCER);
        syncToMetaFile();
    }

    public long getStructureVersion() {
        return structureVersion;
    }

    public int getRealColumnCount() {
        return columnNameIndexMap.size();
    }

    public int getTableId() {
        return tableId;
    }

    @Override
    public String getTableName() {
        return tableName;
    }

    @Override
    public boolean isWalEnabled() {
        return true;
    }

    public void syncToMetaFile() {
        metaMem.jumpTo(0);
        // Size of metadata
        metaMem.putInt(0);
        metaMem.putInt(WAL_FORMAT_VERSION);
        metaMem.putLong(structureVersion);
        int metaSize = columnMetadata.size();
        metaMem.putInt(metaSize);
        metaMem.putInt(timestampIndex);
        metaMem.putInt(tableId);
        for (int i = 0; i < metaSize; i++) {
            final int type = getColumnType(i);
            metaMem.putInt(type);
            metaMem.putStr(getColumnName(i));
        }

        // Set metadata size
        int size = (int) metaMem.getAppendOffset();
        metaMem.jumpTo(0);
        metaMem.putInt(size);
        metaMem.jumpTo(size);
    }

    public void open(String tableName, Path path, int pathLen) {
        reset();
        this.tableName = tableName;
        openSmallFile(ff, path, pathLen, roMetaMem, META_FILE_NAME, MemoryTag.MMAP_SEQUENCER);

        // get written data size
        if (readonly == READ_WRITE) {
            metaMem.jumpTo(SEQ_META_OFFSET_WAL_VERSION);
            int size = metaMem.getInt(0);
            metaMem.jumpTo(size);
        }

        loadSequencerMetadata(roMetaMem, columnMetadata, columnNameIndexMap);
        structureVersion = roMetaMem.getLong(SEQ_META_OFFSET_STRUCTURE_VERSION);
        columnCount = columnMetadata.size();
        timestampIndex = roMetaMem.getInt(SEQ_META_OFFSET_TIMESTAMP_INDEX);
        tableId = roMetaMem.getInt(SEQ_META_TABLE_ID);

        if (readonly == READ_ONLY) {
            // close early
            roMetaMem.close();
        }
    }

    public void removeColumn(CharSequence columnName) {
        int columnIndex = columnNameIndexMap.get(columnName);
        if (columnIndex < 0) {
            throw CairoException.critical(0).put("Column not found: ").put(columnName);
        }
        final TableColumnMetadata deletedMeta = columnMetadata.getQuick(columnIndex);
        deletedMeta.markDeleted();
        columnNameIndexMap.remove(deletedMeta.getName());
        structureVersion++;
    }

    public void renameColumn(CharSequence columnName, CharSequence newName) {
        int columnIndex = columnNameIndexMap.get(columnName);
        if (columnIndex < 0) {
            throw CairoException.critical(0).put("Column not found: ").put(columnName);
        }
        int columnType = columnMetadata.getQuick(columnIndex).getType();
        columnMetadata.setQuick(columnIndex, new TableColumnMetadata(newName.toString(), 0L, columnType));
        structureVersion++;
    }

    private void addColumn0(CharSequence columnName, int columnType) {
        final String name = columnName.toString();
        columnNameIndexMap.put(name, columnMetadata.size());
        columnMetadata.add(new TableColumnMetadata(name, -1L, columnType, false, 0, false, null, columnMetadata.size()));
        columnCount++;
    }

    protected void clear() {
        reset();
        Misc.free(metaMem);
        Misc.free(roMetaMem);
    }

    private void reset() {
        columnMetadata.clear();
        columnNameIndexMap.clear();
        columnCount = 0;
        timestampIndex = -1;
        tableName = null;
    }

    @Override
    public void toReaderIndexes() {
        // Remove deleted columns from the metadata, e.g. make it reader metadata.
        // Deleted columns have negative type.
        int copyTo = 0;
        for (int i = 0; i < columnCount; i++) {
            int columnType = columnMetadata.getQuick(i).getType();
            if (columnType > 0) {
                if (copyTo != i) {
                    TableColumnMetadata columnMeta = columnMetadata.get(i);
                    columnMetadata.set(copyTo, columnMeta);
                    if (i == timestampIndex) {
                        timestampIndex = copyTo;
                    }
                }
                copyTo++;
            }
        }
        columnCount = copyTo;
        columnMetadata.setPos(columnCount);
    }
}
