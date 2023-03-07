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
package io.questdb.griffin;

import org.junit.Assert;
import org.junit.Test;

public class GroupByTest extends AbstractGriffinTest {

    @Test
    public void test1GroupByWithoutAggregateFunctionsReturnsUniqueKeys() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t as (" +
                    "    select 1 as l, 'a' as s " +
                    "    union all " +
                    "    select 1, 'a' )");

            String query1 = "select l,s from t group by l,s";
            assertPlan(query1,
                    "GroupBy vectorized: false\n" +
                            "  keys: [l,s]\n" +
                            "    DataFrame\n" +
                            "        Row forward scan\n" +
                            "        Frame forward scan on: t\n");
            assertQuery("l\ts\n1\ta\n", query1, null, true, true);

            String query2 = "select l as l1,s as s1 from t group by l,s";
            //virtual model must be used here to change aliases
            assertPlan(query2,
                    "VirtualRecord\n" +
                            "  functions: [l,s]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [l,s]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: t\n");
            assertQuery("l1\ts1\n1\ta\n", query2, null, true, true);
        });
    }

    @Test
    public void test2FailOnAggregateFunctionAliasInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            assertError("select x, avg(x) as agx, avg(y) from t group by agx ",
                    "[48] aggregate functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2FailOnAggregateFunctionColumnIndexInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            assertError("select x, avg(x) as agx, avg(y) from t group by 2 ",
                    "[48] aggregate functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2FailOnAggregateFunctionInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, avg(y) from t group by x, avg(x) ";
            assertError(query, "[36] aggregate functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2FailOnExpressionWithAggregateFunctionInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, avg(y) from t group by x, y+avg(x) ";
            assertError(query, "[38] aggregate functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2FailOnExpressionWithNonAggregateNonKeyColumnReference() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, x+y from t group by x ";
            assertError(query, "[12] column must appear in GROUP BY clause or aggregate function");
        });
    }

    @Test
    public void test2FailOnNonAggregateNonKeyColumnReference() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, y from t group by x ";
            assertError(query, "[10] column must appear in GROUP BY clause or aggregate function");
        });
    }

    @Test
    public void test2FailOnSelectAliasUsedInGroupByExpression() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            final String errorMessage = "[48] Invalid column: agx";

            assertError("select x, abs(x) as agx, avg(y) from t group by agx+1 ", errorMessage);
            assertError("select x, x+5    as agx, avg(y) from t group by agx+1 ", errorMessage);
            assertError("select x, avg(x)    agx, avg(y) from t group by agx+1 ", errorMessage);
        });
    }

    @Test
    public void test2FailOnWindowFunctionAliasInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, row_number() as z from t group by x, z ";
            assertError(query, "[47] window functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2FailOnWindowFunctionColumnIndexInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, row_number() as z from t group by x, 2 ";
            assertError(query, "[47] window functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2FailOnWindowFunctionInGroupByClause() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            String query = "select x, avg(y) from t group by x, row_number() ";
            assertError(query, "[36] window functions are not allowed in GROUP BY");
        });
    }

    @Test
    public void test2GroupByWithNonAggregateExpressionsOnKeyColumns1() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            compile("insert into t values (1, 11), (1, 12);");

            String query = "select x+1, count(*) " +
                    "from t " +
                    "group by x+1 ";

            assertPlan(query,
                    "VirtualRecord\n" +
                            "  functions: [column,count]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [column]\n" +
                            "      values: [count(*)]\n" +
                            "        VirtualRecord\n" +
                            "          functions: [x+1]\n" +
                            "            DataFrame\n" +
                            "                Row forward scan\n" +
                            "                Frame forward scan on: t\n");

            assertQuery("column\tcount\n" +
                    "2\t2\n", query, null, true, true);
        });
    }

    @Test//expressions based on group by clause expressions should go to outer model 
    public void test2GroupByWithNonAggregateExpressionsOnKeyColumns2() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            compile("insert into t values (1, 11), (1, 12);");

            String query = "select x, avg(y), avg(y) + min(y), x+10, avg(x), avg(x) + 10 " +
                    "from t " +
                    "group by x ";

            assertPlan(query,
                    "VirtualRecord\n" +
                            "  functions: [x,avg,avg+min,x+10,avg1,avg1+10]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [x]\n" +
                            "      values: [avg(y),min(y),avg(x)]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: t\n");
            assertQuery("x\tavg\tcolumn\tcolumn1\tavg1\tcolumn2\n" +
                    "1\t11.5\t22.5\t11\t1.0\t11.0\n", query, null, true, true);
        });
    }

    @Test
    public void test2GroupByWithNonAggregateExpressionsOnKeyColumnsAndBindVariable() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            compile("insert into t values (1, 11), (1, 12);");

            bindVariableService.clear();
            bindVariableService.setStr("bv", "x");
            String query = "select x, avg(y), :bv " +
                    "from t " +
                    "group by x ";

            assertPlan(query,
                    "VirtualRecord\n" +
                            "  functions: [x,avg,:bv::string]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [x]\n" +
                            "      values: [avg(y)]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: t\n");
            assertQuery("x\tavg\t:bv\n" +
                    "1\t11.5\tx\n", query, null, true, true);
        });
    }

    @Test
    public void test2SuccessOnSelectWithExplicitGroupBy() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            compile("insert into t values (1, 11), (1, 12);");
            String query = "select x*10, x+avg(y), min(y) from t group by x ";
            assertPlan(query,
                    "VirtualRecord\n" +
                            "  functions: [x*10,x+avg,min]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [x]\n" +
                            "      values: [avg(y),min(y)]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: t\n");
            assertQuery("column\tcolumn1\tmin\n" +
                    "10\t12.5\t11\n", query, null, true, true);
        });
    }

    @Test
    public void test2SuccessOnSelectWithoutExplicitGroupBy() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table t (x long, y long);");
            compile("insert into t values (1, 11), (1, 12);");
            String query = "select x*10, x+avg(y), min(y) from t";
            assertPlan(query,
                    "VirtualRecord\n" +
                            "  functions: [column,x+avg,min]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [column,x]\n" +
                            "      values: [avg(y),min(y)]\n" +
                            "        VirtualRecord\n" +
                            "          functions: [x*10,y,x]\n" +
                            "            DataFrame\n" +
                            "                Row forward scan\n" +
                            "                Frame forward scan on: t\n");
            assertQuery("column\tcolumn1\tmin\n" +
                    "10\t12.5\t11\n", query, null, true, true);
        });
    }

    @Test
    public void test3GroupByWithNonAggregateExpressionUsingAliasDefinedOnSameLevel() throws Exception {
        assertMemoryLeak(() -> {
            compile("CREATE TABLE weather ( " +
                    "timestamp TIMESTAMP, windDir INT, windSpeed INT, windGust INT, \n" +
                    "cloudCeiling INT, skyCover SYMBOL, visMiles DOUBLE, tempF INT, \n" +
                    "dewpF INT, rain1H DOUBLE, rain6H DOUBLE, rain24H DOUBLE, snowDepth INT) " +
                    "timestamp (timestamp)");

            String query = "select  windSpeed, avg(windSpeed), avg + 10  " +
                    "from weather " +
                    "group by windSpeed " +
                    "order by windSpeed";

            assertError(query, "[35] Invalid column: avg");
        });
    }

    @Test
    public void test4GroupByWithNonAggregateExpressionUsingAliasDefinedOnSameLevel() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table dat as ( select cast(86400000000*(x%3) as timestamp) as date_report from long_sequence(10))");
            String query = "select ordr.date_report, count(*) " +
                    "from dat ordr " +
                    "group by ordr.date_report " +
                    "order by ordr.date_report";

            assertPlan(query,
                    "Sort light\n" +
                            "  keys: [date_report]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [date_report]\n" +
                            "      values: [count(*)]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: dat\n");
            assertQuery("date_report\tcount\n" +
                    "1970-01-01T00:00:00.000000Z\t3\n" +
                    "1970-01-02T00:00:00.000000Z\t4\n" +
                    "1970-01-03T00:00:00.000000Z\t3\n", query, null, true, true);
        });
    }

    @Test
    public void test4GroupByWithNonAggregateExpressionUsingAliasDefinedOnSameLevel2() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table dat as ( select cast(86400000000*(x%3) as timestamp) as date_report from long_sequence(10))");
            String query = "select ordr.date_report, count(*) " +
                    "from dat ordr " +
                    "group by date_report " +
                    "order by ordr.date_report";
            assertPlan(query,
                    "Sort light\n" +
                            "  keys: [date_report]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [date_report]\n" +
                            "      values: [count(*)]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: dat\n");
            assertQuery("date_report\tcount\n" +
                    "1970-01-01T00:00:00.000000Z\t3\n" +
                    "1970-01-02T00:00:00.000000Z\t4\n" +
                    "1970-01-03T00:00:00.000000Z\t3\n", query, null, true, true);
        });
    }

    @Test
    public void test4GroupByWithNonAggregateExpressionUsingAliasDefinedOnSameLevel3() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table dat as ( select cast(86400000000*(x%3) as timestamp) as date_report from long_sequence(10))");
            String query = "select date_report, count(*) " +
                    "from dat ordr " +
                    "group by ordr.date_report " +
                    "order by ordr.date_report";

            assertPlan(query,
                    "Sort light\n" +
                            "  keys: [date_report]\n" +
                            "    GroupBy vectorized: false\n" +
                            "      keys: [date_report]\n" +
                            "      values: [count(*)]\n" +
                            "        DataFrame\n" +
                            "            Row forward scan\n" +
                            "            Frame forward scan on: dat\n");
            assertQuery("date_report\tcount\n" +
                    "1970-01-01T00:00:00.000000Z\t3\n" +
                    "1970-01-02T00:00:00.000000Z\t4\n" +
                    "1970-01-03T00:00:00.000000Z\t3\n", query, null, true, true);
        });
    }

    @Test
    public void test4GroupByWithNonAggregateExpressionUsingAliasDefinedOnSameLevel4() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table dat as ( select cast(86400000000*(x%3) as timestamp) as date_report from long_sequence(10))");
            String query = "select date_report, ordr.date_report,  count(*) " +
                    "from dat ordr " +
                    "group by date_report, ordr.date_report " +
                    "order by ordr.date_report";
            assertPlan(query,
                    "Sort light\n" +
                            "  keys: [date_report]\n" +
                            "    VirtualRecord\n" +
                            "      functions: [date_report,date_report,count]\n" +
                            "        GroupBy vectorized: false\n" +
                            "          keys: [date_report]\n" +
                            "          values: [count(*)]\n" +
                            "            DataFrame\n" +
                            "                Row forward scan\n" +
                            "                Frame forward scan on: dat\n");
            assertQuery("date_report\tdate_report1\tcount\n" +
                    "1970-01-01T00:00:00.000000Z\t1970-01-01T00:00:00.000000Z\t3\n" +
                    "1970-01-02T00:00:00.000000Z\t1970-01-02T00:00:00.000000Z\t4\n" +
                    "1970-01-03T00:00:00.000000Z\t1970-01-03T00:00:00.000000Z\t3\n", query, null, true, true);
        });
    }

    @Test
    public void test5GroupByWithNonAggregateExpressionUsingKeyColumn() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table dat as ( select cast(86400000000*(x%3) as timestamp) as date_report from long_sequence(10))");
            String query = "select ordr.date_report, to_str(ordr.date_report, 'dd.MM.yyyy') as dt, count(*)\n" +
                    "from dat ordr\n" +
                    "group by ordr.date_report\n" +
                    "order by ordr.date_report";

            assertPlan(query,
                    "Sort light\n" +
                            "  keys: [date_report]\n" +
                            "    VirtualRecord\n" +
                            "      functions: [date_report,to_str(date_report),count]\n" +
                            "        GroupBy vectorized: false\n" +
                            "          keys: [date_report]\n" +
                            "          values: [count(*)]\n" +
                            "            DataFrame\n" +
                            "                Row forward scan\n" +
                            "                Frame forward scan on: dat\n");
            assertQuery("date_report\tdt\tcount\n" +
                    "1970-01-01T00:00:00.000000Z\t01.01.1970\t3\n" +
                    "1970-01-02T00:00:00.000000Z\t02.01.1970\t4\n" +
                    "1970-01-03T00:00:00.000000Z\t03.01.1970\t3\n", query, null, true, true);
        });
    }

    @Test
    public void test6GroupByWithNonAggregateExpressionUsingKeyColumn() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table ord as ( select cast(86400000000*(x%3) as timestamp) as date_report, x from long_sequence(10))");
            compile("create table det as ( select cast(86400000000*(10+x%3) as timestamp) as date_report, x from long_sequence(10))");

            String query = "select details.date_report, to_str(details.date_report, 'dd.MM.yyyy') as dt, min(details.x), count(*) " +
                    "from ord ordr " +
                    "join det details on ordr.x = details.x " +
                    "group by details.date_report " +
                    "order by details.date_report";

            assertPlan(query,
                    "Sort light\n" +
                            "  keys: [date_report]\n" +
                            "    VirtualRecord\n" +
                            "      functions: [date_report,to_str(date_report),min,count]\n" +
                            "        GroupBy vectorized: false\n" +
                            "          keys: [date_report]\n" +
                            "          values: [min(x),count(*)]\n" +
                            "            SelectedRecord\n" +
                            "                Hash Join Light\n" +
                            "                  condition: details.x=ordr.x\n" +
                            "                    DataFrame\n" +
                            "                        Row forward scan\n" +
                            "                        Frame forward scan on: ord\n" +
                            "                    Hash\n" +
                            "                        DataFrame\n" +
                            "                            Row forward scan\n" +
                            "                            Frame forward scan on: det\n");
            assertQuery("date_report\tdt\tmin\tcount\n" +
                    "1970-01-11T00:00:00.000000Z\t11.01.1970\t3\t3\n" +
                    "1970-01-12T00:00:00.000000Z\t12.01.1970\t1\t4\n" +
                    "1970-01-13T00:00:00.000000Z\t13.01.1970\t2\t3\n", query, null, true, true);
        });
    }

    @Test
    public void test6GroupByWithNonAggregateExpressionUsingKeyColumn2() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table ord as ( select cast(86400000000*(x%3) as timestamp) as date_report, x from long_sequence(10))");
            compile("create table det as ( select cast(86400000000*(10+x%3) as timestamp) as date_report, x from long_sequence(10))");

            String query = "select details.date_report, to_str(date_report, 'dd.MM.yyyy') as dt, min(details.x), count(*) " +
                    "from ord ordr " +
                    "join det details on ordr.x = details.x " +
                    "group by details.date_report " +
                    "order by details.date_report";
            assertError(query, "[35] Ambiguous column [name=date_report]");
        });
    }

    @Test
    public void testGroupByAliasInDifferentOrder1() throws Exception {
        assertQuery("k1\tk2\tcount\n" +
                        "0\t0\t2\n" +
                        "0\t2\t3\n" +
                        "1\t3\t2\n" +
                        "1\t1\t3\n",
                "select key1 as k1, key2 as k2, count(*) from t group by k2, k1 order by 1",
                "create table t as ( select x%2 key1, x%4 key2, x as value from long_sequence(10)); ", null, true, false, true);
    }

    @Test
    public void testGroupByAliasInDifferentOrder2() throws Exception {
        assertQuery("k1\tk2\tcount\n" +
                        "1\t0\t2\n" +
                        "1\t2\t3\n" +
                        "2\t3\t2\n" +
                        "2\t1\t3\n",
                "select key1+1 as k1, key2 as k2, count(*) from t group by k2, k1 order by 1",
                "create table t as ( select x%2 key1, x%4 key2, x as value from long_sequence(10)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx1() throws Exception {
        assertQuery("key\tcount\n" +
                        "0\t50\n" +
                        "1\t50\n",
                "select key, count(*) from t group by 1 order by 1",
                "create table t as ( select x%2 as key, x as value from long_sequence(100))", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx2() throws Exception {
        assertQuery("key\tcount\n" +
                        "0\t50\n" +
                        "1\t50\n",
                "select key, count(*) from t group by 1, 1 order by 1",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx3() throws Exception {
        assertQuery("key\tcount\n" +
                        "0\t50\n" +
                        "1\t50\n",
                "select key, count(*) from t group by key, 1 order by 1",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx4() throws Exception {
        assertQuery("column\tcount\n" +
                        "1\t50\n" +
                        "2\t50\n",
                "select key+1, count(*) from t group by key, 1 order by key+1",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx5() throws Exception {
        assertQuery("z\tcount\n" +
                        "1\t50\n" +
                        "2\t50\n",
                "select key+1 as z, count(*) from t group by key, 1 order by z",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx6() throws Exception {
        assertQuery("column\tcount\n" +
                        "1\t50\n" +
                        "2\t50\n",
                "select key+1, count(*) from t group by key, 1 order by 1",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx7() throws Exception {
        assertQuery("column\tcount\n" +
                        "2\t50\n" +
                        "1\t50\n",
                "select key+1, count(*) from t group by key, 1 order by key+3 desc",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByColumnIdx8() throws Exception {
        assertQuery("column\tkey\tkey1\tcount\n" +
                        "1\t0\t0\t50\n" +
                        "2\t1\t1\t50\n",
                "select key+1, key, key, count(*) from t group by key order by 1,2,3 desc",
                "create table t as ( select x%2 as key, x as value from long_sequence(100)); ", null, true, false, true);
    }

    @Test
    public void testGroupByDuplicateColumn() throws Exception {
        assertQuery("k1\tk2\tcount\n" +
                        "0\t0\t2\n" +
                        "0\t2\t3\n" +
                        "1\t1\t3\n" +
                        "1\t3\t2\n",
                "select key1 as k1, key2 as k2, count(*) from t group by k2, k1, k2 order by 1, 2",
                "create table t as ( select x%2 key1, x%4 key2, x as value from long_sequence(10)); ", null, true, false, true);
    }

    @Test
    public void testGroupByWithDuplicateSelectColumn() throws Exception {
        assertQuery("k1\tkey2\tkey21\tcount\n" +
                        "0\t0\t0\t2\n" +
                        "0\t2\t2\t3\n" +
                        "1\t1\t1\t3\n" +
                        "1\t3\t3\t2\n",
                "select key1 as k1, key2, key2, count(*) from t group by key2, k1 order by 1, 2",
                "create table t as ( select x%2 key1, x%4 key2, x as value from long_sequence(10)); ", null, true, false, true);
    }

    private void assertError(String query, String errorMessage) {
        try {
            assertQuery(null, query,
                    null, true, true);
            Assert.fail();
        } catch (SqlException sqle) {
            sqle.printStackTrace();
            Assert.assertEquals(errorMessage, sqle.getMessage());
        }
    }

}
