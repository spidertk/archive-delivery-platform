package com.powerjob.archive.server.service.ingestmonitor;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OssCostServiceTest {
    @Test void usesOfficialSingaporePriceAndBinaryBillingGb() {
        // 阿里云按计费 GB 折算，页面的十进制容量展示不能直接替代计费单位。
        assertEquals(new BigDecimal("116.245402"),OssCostService.charge(1_099_511_627_776L));
        assertEquals(new BigDecimal("0.000000"),OssCostService.charge(0));
        assertEquals("ap-southeast-1",new OssCostService().pricing().get("region"));
    }
    @Test void totalUsesCurrentStorageAndDateStampedReferenceExchangeRate() {
        // 总额按完整容量一次换算，不把桶/渠道重复累加；汇率及美元原价可复核。
        var service=new OssCostService();
        var total=service.total(List.of(Map.of("sizeBytes",1_073_741_824L),Map.of("sizeBytes",2_147_483_648L)));
        assertEquals("CNY",total.get("currency"));assertEquals(1,total.get("periodMonths"));
        assertEquals(3_221_225_472L,total.get("storedBytes"));assertEquals(new BigDecimal("0.340563"),total.get("monthlyStorage"));
        var pricing=service.pricing();
        assertEquals(new BigDecimal("0.0170"),pricing.get("sourceStoragePerGbMonth"));
        assertEquals(new BigDecimal("6.6777"),pricing.get("usdCnyRate"));assertEquals("2026-11-20",pricing.get("exchangeRateDate"));
        assertEquals(new BigDecimal("0.11352090"),pricing.get("storagePerGbMonth"));
    }
    @Test void sameBucketIsChargedOnceAndUploadedBytesDoNotBecomeStorage() {
        // 两个渠道合并存量；未知目标存量不能拿累计上传量替代，也不计算下载费用。
        var channels=List.<Map<String,Object>>of(Map.of("bucket","source","sizeBytes",1_073_741_824L,"destinations",List.of(Map.of("bucket","target","totalBytes",999))),Map.of("bucket","source","sizeBytes",2_147_483_648L,"destinations",List.of()));
        var buckets=new OssCostService().buckets(channels);
        assertEquals(1,buckets.size());
        assertEquals(new BigDecimal("0.340563"),buckets.get(0).get("monthlyStorage"));
        assertFalse(buckets.get(0).containsKey("deliveryDownload"));
    }
}
