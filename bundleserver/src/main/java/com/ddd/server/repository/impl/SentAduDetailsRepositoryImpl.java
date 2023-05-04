package com.ddd.server.repository.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.ddd.server.repository.SentAduDetailsRepository;
import com.ddd.server.repository.entity.SentAduDetails;

@Repository
public class SentAduDetailsRepositoryImpl implements SentAduDetailsRepository {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Override
  public Optional<SentAduDetails> findById(String id) {
    SentAduDetails record = null;
    try {
      record =
          this.jdbcTemplate.queryForObject(
              "select id, bundle_id as bundleId, "
                  + "app_id as appId, "
                  + "adu_id_range_start as aduIdRangeStart, "
                  + "adu_id_range_end as aduIdRangeEnd "
                  + "from sent_adu_details where bundle_id = ?",
              BeanPropertyRowMapper.newInstance(SentAduDetails.class),
              id);
    } catch (EmptyResultDataAccessException e) {
      //      System.out.println(e);
    }
    if (record == null) {
      return Optional.empty();
    }
    return Optional.of(record);
  }

  @Override
  public List<SentAduDetails> findByBundleId(String bundleId) {
    List<SentAduDetails> records =
        this.jdbcTemplate.query(
            "select id, bundle_id as bundleId, "
                + "app_id as appId, "
                + "adu_id_range_start as aduIdRangeStart, "
                + "adu_id_range_end as aduIdRangeEnd "
                + "from sent_adu_details where bundle_id = ?",
            BeanPropertyRowMapper.newInstance(SentAduDetails.class),
            bundleId);

    return records;
  }

  @Override
  public void save(SentAduDetails record) {
    Optional<SentAduDetails> opt = this.findById(record.getId());

    if (opt.isEmpty()) {
      this.jdbcTemplate.update(
          "INSERT INTO sent_adu_details (id, bundle_id, app_id, adu_id_range_start, adu_id_range_end) VALUES (?, ?, ?, ?, ?)",
          UUID.randomUUID().toString(),
          record.getBundleId(),
          record.getAppId(),
          record.getAduIdRangeStart(),
          record.getAduIdRangeEnd());
    } else {
      this.jdbcTemplate.update(
          "UPDATE sent_adu_details SET bundle_id = ?, app_id = ?, adu_id_range_start = ?, adu_id_range_end = ? where id = ?",
          record.getBundleId(),
          record.getAppId(),
          record.getAduIdRangeStart(),
          record.getAduIdRangeEnd(),
          record.getId());
    }
  }

  @Override
  public void deleteByBundleId(String bundleId) {
    this.jdbcTemplate.update("delete from sent_adu_details where bundle_id = ?", bundleId);
  }
}
