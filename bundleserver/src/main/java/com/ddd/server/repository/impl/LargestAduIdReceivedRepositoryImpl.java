// package com.ddd.server.repository.impl;

// import java.util.Optional;
// import java.util.UUID;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.dao.EmptyResultDataAccessException;
// import org.springframework.jdbc.core.BeanPropertyRowMapper;
// import org.springframework.jdbc.core.JdbcTemplate;
// import org.springframework.stereotype.Repository;
// import com.ddd.server.repository.LargestAduIdReceivedRepository;
// import com.ddd.server.repository.entity.LargestAduIdReceived;

// @Repository
// public class LargestAduIdReceivedRepositoryImpl implements LargestAduIdReceivedRepository {

//   @Autowired
//   private JdbcTemplate jdbcTemplate;

//   @Override
//   public Optional<LargestAduIdReceived> findByClientIdAndAppId(String clientId, String appId) {

//     LargestAduIdReceived record = null;
//     try {
//       record =
//           this.jdbcTemplate.queryForObject(
//               "select id, client_id as clientId, app_id as appId, adu_id as aduId from largest_adu_id_received
//               where client_id = ? and app_id = ?",
//               BeanPropertyRowMapper.newInstance(LargestAduIdReceived.class),
//               clientId,
//               appId);
//     } catch (EmptyResultDataAccessException e) {
//       //      System.out.println(e);
//     }
//     if (record == null) {
//       return Optional.empty();
//     }
//     return Optional.of(record);
//   }

//   @Override
//   public void save(LargestAduIdReceived record) {
//     Optional<LargestAduIdReceived> opt =
//         this.findByClientIdAndAppId(record.getClientId(), record.getAppId());

//     if (opt.isEmpty()) {
//       this.jdbcTemplate.update(
//           "INSERT INTO largest_adu_id_received (id, client_id, app_id, adu_id) VALUES (?, ?, ?, ?)",
//           UUID.randomUUID().toString(),
//           record.getClientId(),
//           record.getAppId(),
//           record.getAduId());
//     } else {
//       this.jdbcTemplate.update(
//           "UPDATE largest_adu_id_received SET adu_id = ? where client_id = ? and app_id = ?",
//           record.getAduId(),
//           record.getClientId(),
//           record.getAppId());
//     }
//   }
// }
