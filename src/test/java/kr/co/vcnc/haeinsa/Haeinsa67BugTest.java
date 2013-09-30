package kr.co.vcnc.haeinsa;

import kr.co.vcnc.haeinsa.thrift.TRowLocks;
import kr.co.vcnc.haeinsa.thrift.generated.TRowKey;
import kr.co.vcnc.haeinsa.thrift.generated.TRowLock;
import kr.co.vcnc.haeinsa.thrift.generated.TRowLockState;

import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * https://issues.vcnc.co.kr/browse/HAEINSA-67 When haeinsa recovers rows, we
 * should make stable rows into new committimestamp's stable rows. This method
 * prevents making dangling row locks of long running transactions.
 */
public class Haeinsa67BugTest {

    private static HaeinsaTestingCluster CLUSTER;

    @BeforeClass
    public static void setUpHbase() throws Exception {
        CLUSTER = HaeinsaTestingCluster.getInstance();
    }

    @Test
    public void testRecover() throws Exception {
        final HaeinsaTransactionManager tm = CLUSTER.getTransactionManager();
        final HaeinsaTableIface testTable = CLUSTER.getHaeinsaTable("Haeinsa67BugTest.test");
        final HTableInterface hTestTable = CLUSTER.getHbaseTable("Haeinsa67BugTest.test");

        {
            TRowKey primaryRowKey = new TRowKey().setTableName(testTable.getTableName()).setRow(Bytes.toBytes("Andrew"));
            TRowKey secondaryRowKey = new TRowKey().setTableName(testTable.getTableName()).setRow(Bytes.toBytes("Brad"));
            TRowLock secondaryRowLock = new TRowLock(HaeinsaConstants.ROW_LOCK_VERSION, TRowLockState.STABLE, 1380504156137L);
            TRowLock primaryRowLock = new TRowLock(HaeinsaConstants.ROW_LOCK_VERSION, TRowLockState.PREWRITTEN, 1380504157100L)
                                            .setCurrentTimestmap(1380504156000L)
                                            .setExpiry(1380504160000L);
            primaryRowLock.addToSecondaries(secondaryRowKey);
            Put primaryPut = new Put(primaryRowKey.getRow());
            primaryPut.add(HaeinsaConstants.LOCK_FAMILY, HaeinsaConstants.LOCK_QUALIFIER,
                    primaryRowLock.getCurrentTimestmap(), TRowLocks.serialize(primaryRowLock));
            hTestTable.put(primaryPut);

            Put secondaryPut = new Put(secondaryRowKey.getRow());
            secondaryPut.add(HaeinsaConstants.LOCK_FAMILY, HaeinsaConstants.LOCK_QUALIFIER,
                    secondaryRowLock.getCommitTimestamp(), TRowLocks.serialize(secondaryRowLock));
            hTestTable.put(secondaryPut);

            HaeinsaTransaction tx = tm.begin();
            HaeinsaGet get = new HaeinsaGet(primaryRowKey.getRow());
            HaeinsaResult result = testTable.get(tx, get);
            Assert.assertTrue(result.isEmpty());

            Get hPrimaryGet = new Get(primaryRowKey.getRow());
            hPrimaryGet.addColumn(HaeinsaConstants.LOCK_FAMILY, HaeinsaConstants.LOCK_QUALIFIER);
            Result primaryResult = hTestTable.get(hPrimaryGet);
            TRowLock stablePrimaryRowLock = TRowLocks.deserialize(primaryResult.getValue(HaeinsaConstants.LOCK_FAMILY, HaeinsaConstants.LOCK_QUALIFIER));
            Assert.assertEquals(stablePrimaryRowLock.getState(), TRowLockState.STABLE);
            Assert.assertEquals(stablePrimaryRowLock.getCommitTimestamp(), primaryRowLock.getCommitTimestamp());

            Get hSecondaryGet = new Get(secondaryRowKey.getRow());
            hSecondaryGet.addColumn(HaeinsaConstants.LOCK_FAMILY, HaeinsaConstants.LOCK_QUALIFIER);
            Result secondaryResult = hTestTable.get(hSecondaryGet);
            TRowLock stableSecondaryRowLock = TRowLocks.deserialize(secondaryResult.getValue(HaeinsaConstants.LOCK_FAMILY, HaeinsaConstants.LOCK_QUALIFIER));
            Assert.assertEquals(stableSecondaryRowLock.getState(), TRowLockState.STABLE);
            Assert.assertEquals(stableSecondaryRowLock.getCommitTimestamp(), primaryRowLock.getCommitTimestamp());
        }

        testTable.close();
        hTestTable.close();
    }
}