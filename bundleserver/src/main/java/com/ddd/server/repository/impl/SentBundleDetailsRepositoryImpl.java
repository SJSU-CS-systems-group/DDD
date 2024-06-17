// package com.ddd.server.repository.impl;

// import java.util.Optional;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.dao.EmptyResultDataAccessException;
// import org.springframework.jdbc.core.BeanPropertyRowMapper;
// import org.springframework.jdbc.core.JdbcTemplate;
// import org.springframework.stereotype.Repository;
// import com.ddd.server.repository.SentBundleDetailsRepository;
// import com.ddd.server.repository.entity.SentBundleDetails;

// @Repository
// public class SentBundleDetailsRepositoryImpl implements SentBundleDetailsRepository {

//   @Autowired private JdbcTemplate jdbcTemplate;

//   @Override
//   public Optional<SentBundleDetails> findByBundleId(String bundleId) {
//     SentBundleDetails record = null;
//     try {
//       record =
//           this.jdbcTemplate.queryForObject(
//               "select bundle_id as bundleId, client_id as clientId, acked_bundle_id as ackedBundleId from
//               sent_bundle_details where bundle_id = ?",
//               BeanPropertyRowMapper.newInstance(SentBundleDetails.class),
//               bundleId);
//     } catch (EmptyResultDataAccessException e) {
//       //      logger.log(SEVERE,e);
//     }
//     if (record == null) {
//       return Optional.empty();
//     }
//     return Optional.of(record);
//   }

//   @Override
//   public void save(SentBundleDetails record) {
//     Optional<SentBundleDetails> opt = this.findByBundleId(record.getBundleId());
//     if (opt.isEmpty()) {
//       this.jdbcTemplate.update(
//           "INSERT INTO sent_bundle_details (bundle_id, client_id, acked_bundle_id) VALUES (?, ?, ?)",
//           record.getBundleId(),
//           record.getClientId(),
//           record.getAckedBundleId());
//     } else {
//       this.jdbcTemplate.update(
//           "UPDATE sent_bundle_details SET client_id = ?, acked_bundle_id = ? where bundle_id = ?",
//           record.getClientId(),
//           record.getAckedBundleId(),
//           record.getBundleId());
//     }
//   }
// }
