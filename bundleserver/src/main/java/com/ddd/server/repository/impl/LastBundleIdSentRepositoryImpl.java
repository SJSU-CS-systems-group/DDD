// package com.ddd.server.repository.impl;

// import java.util.Optional;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.dao.EmptyResultDataAccessException;
// import org.springframework.jdbc.core.BeanPropertyRowMapper;
// import org.springframework.jdbc.core.JdbcTemplate;
// import org.springframework.stereotype.Repository;
// import com.ddd.server.repository.LastBundleIdSentRepository;
// import com.ddd.server.repository.entity.LastBundleIdSent;

// @Repository
// public class LastBundleIdSentRepositoryImpl implements LastBundleIdSentRepository {

//   @Autowired private JdbcTemplate jdbcTemplate;

//   @Override
//   public Optional<LastBundleIdSent> findByClientId(String clientId) {
//     LastBundleIdSent record = null;
//     try {
//       record =
//           this.jdbcTemplate.queryForObject(
//               "select client_id as clientId, bundle_id as bundleId from last_bundle_id_sent where client_id = ?",
//               BeanPropertyRowMapper.newInstance(LastBundleIdSent.class),
//               clientId);

//     } catch (EmptyResultDataAccessException e) {
//       //      System.out.println(e);
//     }
//     if (record == null) {
//       return Optional.empty();
//     }
//     return Optional.of(record);
//   }

//   @Override
//   public void save(LastBundleIdSent record) {

//     Optional<LastBundleIdSent> opt = this.findByClientId(record.getClientId());

//     if (opt.isEmpty()) {
//       this.jdbcTemplate.update(
//           "INSERT INTO last_bundle_id_sent (client_id, bundle_id) VALUES (?, ?)",
//           record.getClientId(),
//           record.getBundleId());
//     } else {
//       this.jdbcTemplate.update(
//           "UPDATE last_bundle_id_sent SET bundle_id = ? where client_id = ?",
//           record.getBundleId(),
//           record.getClientId());
//     }
//   }
// }
