package edu.sjsu.dtn.adapter.signal;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.prefs.Preferences;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SignalCLIConnector {

    public static byte[] performRegistration(String dddClientId, byte[] bytes) throws IOException {

        Preferences prefs = Preferences.userRoot().node(SignalCLIConnector.class.getName());

        byte[] fileContent = null;
        String jsonString = new String(bytes, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonObj = mapper.readTree(jsonString);
        Runtime runtime = Runtime.getRuntime();
        Scanner scanner = new Scanner(System.in);

        // check if its a register request
        if (jsonObj.hasNonNull("requestType")) {

            if ("\"register\"".equalsIgnoreCase(jsonObj.get("requestType").toString())) {

                System.out.println("Enter phone number in E.164 format: ");
                String phoneNumber = scanner.nextLine();
                System.out.println("Enter signal captcha: ");
                String captcha = scanner.nextLine();
                Path tempFile = Files.createTempFile("registrationInfo", ".json");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), jsonObj);
                System.out.println(tempFile.toAbsolutePath().toString());

                Process pc = runtime.exec(
                        "java -jar signal-cli.jar -a " + phoneNumber + " register --captcha " + captcha + " --ddd " +
                                tempFile.toAbsolutePath().toString());
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(pc.getInputStream()));

                BufferedReader stdError = new BufferedReader(new InputStreamReader(pc.getErrorStream()));

                // Read the output from the command
                System.out.println("Here is the standard output of the command:\n");
                String s = null;
                while ((s = stdInput.readLine()) != null) {
                    System.out.println(s);
                }

                // Read any errors from the attempted command
                System.out.println("Here is the standard error of the command (if any):\n");
                while ((s = stdError.readLine()) != null) {
                    System.out.println(s);
                }
                System.out.println("Enter PIN received on phone number");
                String pin = scanner.nextLine();

                pc = runtime.exec("java -jar signal-cli.jar -a " + phoneNumber + " verify " + pin);

                stdInput = new BufferedReader(new InputStreamReader(pc.getInputStream()));

                while ((s = stdInput.readLine()) != null) {
                    System.out.println(s);
                    File f = new File(s);
                    if (f.exists() && !f.isDirectory()) {
                        System.out.println("File exists");
                        fileContent = Files.readAllBytes(f.toPath());
                        System.out.println(fileContent);
                    }
                }
                stdError = new BufferedReader(new InputStreamReader(pc.getErrorStream()));
                System.out.println("Here is the standard error of the command (if any):\n");
                while ((s = stdError.readLine()) != null) {
                    System.out.println(s);
                }
                prefs.put(dddClientId, phoneNumber);

                tempFile.toFile().deleteOnExit();
                scanner.close();
            }
        }
        return fileContent;
    }

    public static List<String> receiveMessages(String clientId) {

        Preferences prefs = Preferences.userRoot().node(SignalCLIConnector.class.getName());
        List<String> messageLocations = new ArrayList<>();
        if (prefs.get(clientId, null) != null) {
            String phoneNumber = prefs.get(clientId, null);
            try {
                Runtime runtime = Runtime.getRuntime();
                Process pc = runtime.exec("java -jar signal-cli.jar -a " + phoneNumber + " receive");
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(pc.getInputStream()));

                String s = null;
                while ((s = stdInput.readLine()) != null) {
                    System.out.println(s);
                    File f = new File(s);
                    if (f.exists() && !f.isDirectory()) {
                        messageLocations.add(s);
                    }
                }

            } catch (Exception e) {
                System.out.println("Exception in receive");
            }
        }
        return messageLocations;
    }

//	test for performRegistration
//	public static void main(String[] args) throws IOException {
//		Path path = Paths.get("/Users/spartan/Documents/DDD/keys/publickeys.json");
//		byte[] data = Files.readAllBytes(path);
//		performRegistration("1", data);
//		receiveMessages("1");
//	}
}
