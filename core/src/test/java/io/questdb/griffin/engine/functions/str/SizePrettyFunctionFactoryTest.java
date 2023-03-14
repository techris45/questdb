/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
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

package io.questdb.griffin.engine.functions.str;

import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.engine.AbstractFunctionFactoryTest;
import io.questdb.std.Numbers;
import org.junit.Test;

public class SizePrettyFunctionFactoryTest extends AbstractFunctionFactoryTest {

    @Test
    public void testSizes() throws SqlException {
        call(0L).andAssert("0.0  B");
        call(Numbers.LONG_NaN).andAssert(null);
        call(1L).andAssert("1.0  B");
        call(1024L).andAssert("1.0 KB");
        call(1024L * 1024).andAssert("1.0 MB");
        call(1024L * 1024 * 1024).andAssert("1.0 GB");
        call(1024L * 1024 * 1024 * 1024).andAssert("1.0 TB");
        call(1024L * 1024 * 1024 * 1024 * 1024).andAssert("1.0 PB");
        call(1024L * 1024 * 1024 * 1024 * 1024 * 1024).andAssert("1.0 EB");
        call((long) (8.657 * 1024L * 1024 * 1024 * 1024 * 1024 * 1024)).andAssert("8.0 EB");
        call((long) (8.657 * 1024L * 1024 * 1024 * 1024 * 1024)).andAssert("8.7 PB");
        call((long) (8.657 * 1024L * 1024 * 1024 * 1024)).andAssert("8.7 TB");
        call((long) (8.657 * 1024L * 1024 * 1024)).andAssert("8.7 GB");
        call((long) (8.657 * 1024L * 1024)).andAssert("8.7 MB");
        call((long) (8.657 * 1024L)).andAssert("8.7 KB");
        call((long) 8.657).andAssert("8.0  B");
    }

    @Override
    protected FunctionFactory getFunctionFactory() {
        return new SizePrettyFunctionFactory();
    }
}
