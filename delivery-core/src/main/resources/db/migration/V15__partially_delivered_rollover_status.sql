SET search_path TO archive_delivery;

-- 跨日接续后仍未全部成功的主单必须展示部分交付；该状态不是终态，次日任务继续占用目标配额。
ALTER TABLE delivery_submit DROP CONSTRAINT delivery_submit_status_check;
ALTER TABLE delivery_submit ADD CONSTRAINT delivery_submit_status_check
  CHECK(status IN('SUBMITTED','DISCOVERING','PLANNING','PROCESSING','WAITING','RETRYING','BLOCKED',
                  'PARTIALLY_DELIVERED','DELIVERED','COMPLETED_WITH_CANCELLATIONS','CANCELLED'));
COMMENT ON COLUMN delivery_submit.status IS '聚合状态；EXPIRED 历史存在且未全部成功为 PARTIALLY_DELIVERED，最新有效任务达到目标并全部成功才 DELIVERED。';
