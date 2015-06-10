/*
 * Copyright (c) 2014. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.ql.ops;

import com.nfsdb.ql.Record;
import com.nfsdb.storage.ColumnType;

public class IntNegativeOperator extends AbstractUnaryOperator {

    public IntNegativeOperator() {
        super(ColumnType.INT);
    }

    @Override
    public double getDouble(Record rec) {
        int v = value.getInt(rec);
        return v == Integer.MIN_VALUE ? Double.NaN : -v;
    }

    @Override
    public int getInt(Record rec) {
        int v = value.getInt(rec);
        return v == Integer.MIN_VALUE ? Integer.MIN_VALUE : -v;
    }

    @Override
    public long getLong(Record rec) {
        int v = value.getInt(rec);
        return v == Integer.MIN_VALUE ? Long.MIN_VALUE : -v;
    }

}
