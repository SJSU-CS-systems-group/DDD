// package com.ddd.server.repository.impl;

// import java.util.Optional;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.dao.EmptyResultDataAccessException;
// import org.springframework.jdbc.core.BeanPropertyRowMapper;
// import org.springframework.jdbc.core.JdbcTemplate;
// import org.springframework.stereotype.Repository;
// import com.ddd.server.repository.LargestBundleIdReceivedRepository;
// import com.ddd.server.repository.entity.LargestBundleIdReceived;

// @Repository
// public class LargestBundleIdReceivedRepositoryImpl implements LargestBundleIdReceivedRepository {

//   @Autowired private JdbcTemplate jdbcTemplate;

//   @Override
//   public Optional<LargestBundleIdReceived> findByClientId(String clientId) {
//     LargestBundleIdReceived record = null;
//     try {
//       record =
//           this.jdbcTemplate.queryForObject(
//               "select client_id as clientId, bundle_id as bundleId from largest_bundle_id_received where client_id
//               = ?",
//               BeanPropertyRowMapper.newInstance(LargestBundleIdReceived.class),
//               clientId);
//     } catch (EmptyResultDataAccessException e) {
//       //      logger.log(SEVERE,e);
//     }
//     if (record == null) {
//       return Optional.empty();
//     }
//     return Optional.of(record);
//   }

//   @Override
//   public void save(LargestBundleIdReceived record) {
//     Optional<LargestBundleIdReceived> opt = this.findByClientId(record.getClientId());

//     if (opt.isEmpty()) {
//       this.jdbcTemplate.update(
//           "INSERT INTO largest_bundle_id_received (client_id, bundle_id) VALUES (?, ?)",
//           record.getClientId(),
//           record.getBundleId());
//     } else {
//       this.jdbcTemplate.update(
//           "UPDATE largest_bundle_id_received SET bundle_id = ? where client_id = ?",
//           record.getBundleId(),
//           record.getClientId());
//     }
//   }
// }
