USE DTN_SERVER_DB;
SET SQL_SAFE_UPDATES = 0;
DELETE from largest_adu_id_received;
DELETE FROM LARGEST_BUNDLE_ID_RECEIVED;
DELETE FROM LARGEST_ADU_ID_RECEIVED;
DELETE FROM LARGEST_ADU_ID_DELIVERED;
DELETE from last_bundle_id_sent;
DELETE from sent_bundle_details;
DELETE from sent_adu_details;
DELETE from largest_bundle_id_received;
DELETE FROM serverRoutingTable;
DELETE FROM ServerWindow;
commit;