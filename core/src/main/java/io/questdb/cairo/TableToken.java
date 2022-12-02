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

package io.questdb.cairo;

import org.jetbrains.annotations.NotNull;

public class TableToken {
    private final boolean isWal;
    @NotNull
    private final String dirName;
    @NotNull
    private final String tableName;
    private final int tableId;

    public TableToken(@NotNull String tableName, @NotNull String dirName, int tableId, boolean isWal) {
        this.tableName = tableName;
        this.dirName = dirName;
        this.tableId = tableId;
        this.isWal = isWal;
    }

    @Override
    public boolean equals(Object o) {
        // equals() enforces both tableId and privateTableName, while hashcode() only uses tableId for speedup
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TableToken that = (TableToken) o;

        if (tableId != that.tableId) return false;
        return dirName.equals(that.dirName);
    }

    /**
     * @return table name to use for logging. Note that most of the time it is same as public table
     * name used in SQL but can diverge per period of time if it is a WAL table, and it is renamed.
     * Do not use this method to identify table, use CairoEngine.getTableNameByTableToken() instead.
     */
    //todo: name should not be exposed to avoid using it incorrectly
    //     this class should impl Sinkable
    public @NotNull String getTableName() {
        return tableName;
    }

    /**
     * @return folder where the table is located.
     */
    public @NotNull String getDirName() {
        return dirName;
    }

    /**
     * @return table id
     */
    public int getTableId() {
        // equals() enforces both tableId and privateTableName, while hashcode() only uses tableId for speedup.
        // TableId should be unique except in very rare cases.
        return tableId;
    }

    @Override
    public int hashCode() {
        return tableId;
    }

    public boolean isWal() {
        return isWal;
    }
}
