package com.ddd.server.bundlerouting;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BundleRouting {

  public void registerReceiptFromTransport(String transportId) {
    System.out.println("[BR] Registered receipt of a bundle from transport: " + transportId);
  }

  public List<String> getClientIdsReachableFromTransport(String transportId) {
    return Arrays.asList(new String[] {"client0"});
  }
}
