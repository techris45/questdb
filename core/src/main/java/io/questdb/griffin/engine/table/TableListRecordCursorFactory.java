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
package io.questdb.griffin.engine.table;

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.GenericRecordMetadata;
import io.questdb.cairo.TableColumnMetadata;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.Misc;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;

public class TableListRecordCursorFactory extends AbstractRecordCursorFactory {

    public static final String TABLE_NAME_COLUMN = "table";
    private static final RecordMetadata METADATA;
    private final TableListRecordCursor cursor;
    private final FilesFacade ff;
    private Path path;

    public TableListRecordCursorFactory(FilesFacade ff, CharSequence dbRoot) {
        super(METADATA);
        this.ff = ff;
        path = new Path().of(dbRoot).$();
        cursor = new TableListRecordCursor();
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) {
        return cursor.of();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return false;
    }

    @Override
    protected void _close() {
        path = Misc.free(path);
    }

    private class TableListRecordCursor implements RecordCursor {
        private final TableListRecord record = new TableListRecord();
        private final StringSink sink = new StringSink();
        private long findPtr = 0;

        @Override
        public void close() {
            findPtr = ff.findClose(findPtr);
        }

        @Override
        public Record getRecord() {
            return record;
        }

        @Override
        public Record getRecordB() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasNext() {
            int plimit = path.length();
            while (true) {
                if (findPtr == 0) {
                    findPtr = ff.findFirst(path);
                    if (findPtr <= 0) {
                        return false;
                    }
                } else {
                    if (ff.findNext(findPtr) <= 0) {
                        return false;
                    }
                }
                if (ff.isDirOrSoftLinkDirNoDots(path, plimit, ff.findName(findPtr), ff.findType(findPtr), sink)) {
                    path.trimTo(plimit).$();
                    return true;
                }
            }
        }

        @Override
        public void recordAt(Record record, long atRowId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size() {
            return -1;
        }

        @Override
        public void toTop() {
            close();
        }

        private TableListRecordCursor of() {
            toTop();
            return this;
        }

        public class TableListRecord implements Record {
            @Override
            public CharSequence getStr(int col) {
                if (col == 0) {
                    return sink;
                }
                return null;
            }

            @Override
            public CharSequence getStrB(int col) {
                return getStr(col);
            }

            @Override
            public int getStrLen(int col) {
                return getStr(col).length();
            }
        }
    }

    static {
        final GenericRecordMetadata metadata = new GenericRecordMetadata();
        metadata.add(new TableColumnMetadata(TABLE_NAME_COLUMN, ColumnType.STRING));
        METADATA = metadata;
    }
}
