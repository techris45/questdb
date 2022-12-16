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

import io.questdb.cairo.pool.PoolListener;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.ReaderOutOfDateException;
import io.questdb.mp.Job;
import io.questdb.std.*;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Path;
import io.questdb.test.tools.TestUtils;
import org.junit.*;

import static org.junit.Assert.fail;

public class CairoEngineTest extends AbstractCairoTest {

    private static Path otherPath = new Path();
    private static Path path = new Path();

    @BeforeClass
    public static void setUpStatic() {
        AbstractCairoTest.setUpStatic();
        otherPath = new Path();
        path = new Path();
    }

    @AfterClass
    public static void tearDownStatic() {
        otherPath = Misc.free(otherPath);
        path = Misc.free(path);
        AbstractCairoTest.tearDownStatic();
    }

    @Test
    public void testAncillaries() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            class MyListener implements PoolListener {
                int count = 0;

                @Override
                public void onEvent(byte factoryType, long thread, CharSequence name, short event, short segment, short position) {
                    count++;
                }
            }

            MyListener listener = new MyListener();

            try (CairoEngine engine = new CairoEngine(configuration)) {
                engine.setPoolListener(listener);
                Assert.assertEquals(listener, engine.getPoolListener());

                TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, "x", TableUtils.ANY_TABLE_ID, -1);
                TableWriter writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, "x", "testing");
                Assert.assertEquals(1, engine.getBusyReaderCount());
                Assert.assertEquals(1, engine.getBusyWriterCount());

                reader.close();
                writer.close();

                Assert.assertEquals(4, listener.count);
                Assert.assertEquals(configuration, engine.getConfiguration());
            }
        });
    }

    @Test
    public void testCannotMapTableId() throws Exception {
        TestUtils.assertMemoryLeak(new TestUtils.LeakProneCode() {
            @Override
            public void run() {
                ff = new FilesFacadeImpl() {
                    private boolean failNextAlloc = false;
                    private int theFD = 0;

                    @Override
                    public boolean allocate(int fd, long size) {
                        if (failNextAlloc) {
                            failNextAlloc = false;
                            return false;
                        }
                        return super.allocate(fd, size);
                    }

                    @Override
                    public long length(int fd) {
                        if (theFD == fd) {
                            failNextAlloc = true;
                            theFD = 0;
                            return 0;
                        }
                        return super.length(fd);
                    }

                    @Override
                    public int openRW(LPSZ name, long opts) {
                        int fd = super.openRW(name, opts);
                        if (Chars.endsWith(name, TableUtils.TAB_INDEX_FILE_NAME)) {
                            theFD = fd;
                        }
                        return fd;
                    }
                };

                try {
                    new CairoEngine(configuration);
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "No space left");
                } finally {
                    ff = null;
                }
                Path.clearThreadLocals();
            }
        });
    }

    @Test
    public void testDuplicateTableCreation() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (TableModel model = new TableModel(configuration, "x", PartitionBy.NONE)
                    .col("a", ColumnType.INT)) {
                CairoTestUtils.create(model);
                try {
                    engine.createTable(AllowAllCairoSecurityContext.INSTANCE, model.getMem(), model.getPath(), model);
                    fail("duplicated tables should not be permitted!");
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table exists");
                }
            }
        });
    }

    @Test
    public void testExpiry() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            class MyListener implements PoolListener {
                int count = 0;

                @Override
                public void onEvent(byte factoryType, long thread, CharSequence name, short event, short segment, short position) {
                    if (event == PoolListener.EV_EXPIRE) {
                        count++;
                    }
                }
            }

            MyListener listener = new MyListener();

            try (CairoEngine engine = new CairoEngine(configuration)) {
                engine.setPoolListener(listener);

                assertWriter(engine, "x");
                assertReader(engine, "x");

                Job job = engine.getEngineMaintenanceJob();
                Assert.assertNotNull(job);

                Assert.assertTrue(job.run(0));
                Assert.assertFalse(job.run(0));

                Assert.assertEquals(2, listener.count);
            }
        });
    }

    @Test
    public void testLockBusyReader() throws Exception {

        createX();

        TestUtils.assertMemoryLeak(() -> {
            try (CairoEngine engine = new CairoEngine(configuration)) {
                try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, "x", TableUtils.ANY_TABLE_ID, TableUtils.ANY_TABLE_VERSION)) {
                    Assert.assertNotNull(reader);
                    Assert.assertEquals(CairoEngine.BUSY_READER, engine.lock(AllowAllCairoSecurityContext.INSTANCE, "x", "testing", true, path));
                    assertReader(engine, "x");
                    assertWriter(engine, "x");
                }
            }
        });
    }

    @Test
    public void testNewTableRename() throws Exception {
        createX();

        TestUtils.assertMemoryLeak(() -> {
            try (CairoEngine engine = new CairoEngine(configuration)) {
                engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");

                assertWriter(engine, "y");
                assertReader(engine, "y");
            }
        });
    }

    @Test
    public void testRemoveExisting() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            spinLockTimeout = 1;
            try (CairoEngine engine = new CairoEngine(configuration)) {
                assertReader(engine, "x");
                assertWriter(engine, "x");
                engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
                Assert.assertEquals(TableUtils.TABLE_DOES_NOT_EXIST, engine.getStatus(AllowAllCairoSecurityContext.INSTANCE, path, "x"));

                try {
                    engine.getReader(AllowAllCairoSecurityContext.INSTANCE, "x", TableUtils.ANY_TABLE_ID, TableUtils.ANY_TABLE_VERSION);
                    Assert.fail();
                } catch (CairoException ignored) {
                }

                try {
                    engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, "x", "testing");
                    Assert.fail();
                } catch (CairoException ignored) {
                }
            }
        });
    }

    @Test
    public void testRemoveNewTable() {

        createX();

        try (CairoEngine engine = new CairoEngine(configuration)) {
            engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
            Assert.assertEquals(TableUtils.TABLE_DOES_NOT_EXIST, engine.getStatus(AllowAllCairoSecurityContext.INSTANCE, path, "x"));
        }
    }

    @Test
    public void testRemoveNonExisting() throws Exception {
        createY(); // this will create root dir at least
        TestUtils.assertMemoryLeak(() -> {
            try (CairoEngine engine = new CairoEngine(configuration)) {
                try {
                    engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "could not remove table");
                }
            }
        });
    }

    @Test
    public void testRemoveWhenReaderBusy() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            try (CairoEngine engine = new CairoEngine(configuration)) {
                try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, "x", TableUtils.ANY_TABLE_ID, TableUtils.ANY_TABLE_VERSION)) {
                    Assert.assertNotNull(reader);
                    try {
                        engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
                        Assert.fail();
                    } catch (CairoException ignored) {
                    }
                }
            }
        });
    }

    @Test
    public void testRemoveWhenWriterBusy() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            try (CairoEngine engine = new CairoEngine(configuration)) {
                try (TableWriter writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, "x", "testing")) {
                    Assert.assertNotNull(writer);
                    try {
                        engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
                        Assert.fail();
                    } catch (CairoException ignored) {
                    }
                }
            }
        });
    }

    @Test
    public void testRenameExisting() throws Exception {
        createX();

        TestUtils.assertMemoryLeak(() -> {
            try (CairoEngine engine = new CairoEngine(configuration)) {
                assertWriter(engine, "x");
                assertReader(engine, "x");

                engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");

                assertWriter(engine, "y");
                assertReader(engine, "y");

                Assert.assertTrue(engine.clear());
            }
        });
    }

    @Test
    public void testRenameExternallyLockedTable() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            try (TableWriter ignored1 = new TableWriter(configuration, "x", metrics)) {

                try (CairoEngine engine = new CairoEngine(configuration)) {
                    try {
                        engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, "x", "testing");
                        Assert.fail();
                    } catch (CairoException ignored) {
                    }

                    try {
                        engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");
                        Assert.fail();
                    } catch (CairoException e) {
                        TestUtils.assertContains(e.getFlyweightMessage(), "table busy");
                    }
                }
            }
        });
    }

    @Test
    public void testRenameFail() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            createX();

            TestFilesFacade ff = new TestFilesFacade() {
                int counter = 1;

                @Override
                public int rename(LPSZ from, LPSZ to) {
                    return counter-- <= 0
                            && super.rename(from, to) == Files.FILES_RENAME_OK ? Files.FILES_RENAME_OK
                            : Files.FILES_RENAME_ERR_OTHER;
                }

                @Override
                public boolean wasCalled() {
                    return counter < 1;
                }
            };

            CairoConfiguration configuration = new DefaultCairoConfiguration(root) {
                @Override
                public FilesFacade getFilesFacade() {
                    return ff;
                }
            };

            try (CairoEngine engine = new CairoEngine(configuration)) {
                assertReader(engine, "x");
                assertWriter(engine, "x");
                try {
                    engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "could not rename");
                }

                assertReader(engine, "x");
                assertWriter(engine, "x");
                engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");
                assertReader(engine, "y");
                assertWriter(engine, "y");
            }

            Assert.assertTrue(ff.wasCalled());
        });
    }

    @Test
    public void testRenameNonExisting() throws Exception {
        TestUtils.assertMemoryLeak(() -> {

            try (TableModel model = new TableModel(configuration, "z", PartitionBy.NONE)
                    .col("a", ColumnType.INT)) {
                CairoTestUtils.create(model);
            }

            try (CairoEngine engine = new CairoEngine(configuration)) {
                engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");
                Assert.fail();
            } catch (CairoException e) {
                TestUtils.assertContains(e.getFlyweightMessage(), "does not exist");
            }
        });
    }

    @Test
    public void testRenameToExistingTarget() throws Exception {
        TestUtils.assertMemoryLeak(() -> {

            createX();
            createY();

            try (CairoEngine engine = new CairoEngine(configuration)) {
                assertWriter(engine, "x");
                assertReader(engine, "x");
                try {
                    engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "y");
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "exists");
                }
                assertWriter(engine, "x");
                assertReader(engine, "x");

                assertReader(engine, "y");
                assertWriter(engine, "y");
            }
        });
    }

    @Test
    public void testWrongReaderVersion() throws Exception {
        createX();

        TestUtils.assertMemoryLeak(() -> {
            try (CairoEngine engine = new CairoEngine(configuration)) {
                assertWriter(engine, "x");
                try {
                    engine.getReader(AllowAllCairoSecurityContext.INSTANCE, "x", TableUtils.ANY_TABLE_VERSION, 2);
                    Assert.fail();
                } catch (ReaderOutOfDateException ignored) {
                }
                Assert.assertTrue(engine.clear());
            }
        });
    }

    private void assertReader(CairoEngine engine, String name) {
        try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, name, TableUtils.ANY_TABLE_ID, TableUtils.ANY_TABLE_VERSION)) {
            Assert.assertNotNull(reader);
        }
    }

    private void assertWriter(CairoEngine engine, String name) {
        try (TableWriter w = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, name, "testing")) {
            Assert.assertNotNull(w);
        }
    }

    private void createX() {
        try (TableModel model = new TableModel(configuration, "x", PartitionBy.NONE)
                .col("a", ColumnType.INT)) {
            CairoTestUtils.create(model);
        }
    }

    private void createY() {
        try (TableModel model = new TableModel(configuration, "y", PartitionBy.NONE)
                .col("b", ColumnType.INT)) {
            CairoTestUtils.create(model);
        }
    }

    @Test
    public void testCrossEngineRenameTableFails() {
        createX();
        try (CairoEngine eng = new CairoEngine(configuration)) {
            // acquire reader in anticipation to table rename failing on the main engine
            try (TableReader ignored = eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x")) {
                try {
                    engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "x2");
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table busy");
                }
            }
        }
    }

    @Test
    public void testCrossEngineRenameTable() throws Exception {
        Assume.assumeFalse(Os.type == Os.WINDOWS);
        assertMemoryLeak(() -> {
            createX();
            try (CairoEngine eng = new CairoEngine(configuration)) {
                long fileCount = Files.getOpenFileCount();
                // ensure reader is cached in the second engine
                eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x").close();
                // rename should succeed
                engine.rename(AllowAllCairoSecurityContext.INSTANCE, path, "x", otherPath, "x2");
                // getting table "x" from second engine should yield "table does not exist" error
                try {
                    eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x").close();
                    Assert.fail();
                } catch (CairoException e) {
                    Assert.assertEquals(CairoException.E_TABLE_DOES_NOT_EXIST, e.getErrno());
                }
                // reader should be released
                Assert.assertEquals(0, eng.getReaderCount());
                Assert.assertEquals(fileCount,  Files.getOpenFileCount());
                // should be able to open new table
                eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x2").close();
                eng.releaseInactive();
            }
        });
    }

    @Test
    public void testCrossEngineDropTable() throws Exception {
        Assume.assumeFalse(Os.type == Os.WINDOWS);
        assertMemoryLeak(() -> {
            createX();
            try (CairoEngine eng = new CairoEngine(configuration)) {
                long fileCount = Files.getOpenFileCount();
                // ensure reader is cached in the second engine
                eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x").close();
                // rename should succeed
                engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
                // getting table "x" from second engine should yield "table does not exist" error
                try {
                    eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x").close();
                    Assert.fail();
                } catch (CairoException e) {
                    Assert.assertEquals(CairoException.E_TABLE_DOES_NOT_EXIST, e.getErrno());
                }
                // reader should be released
                Assert.assertEquals(0, eng.getReaderCount());
                Assert.assertEquals(fileCount,  Files.getOpenFileCount());
            }
        });
    }

    @Test
    public void testCrossEngineDropTableFails() {
        createX();
        try (CairoEngine eng = new CairoEngine(configuration)) {
            // acquire reader in anticipation to table rename failing on the main engine
            try (TableReader ignored = eng.getReader(AllowAllCairoSecurityContext.INSTANCE, "x")) {
                try {
                    engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "x");
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table busy");
                }
            }
        }
    }
}
