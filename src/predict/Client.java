package predict;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    private static final String HOST = "localhost";
    private static final int PORT = 41234; // Match server

    public static void main(String[] args) {
        try (Socket socket = new Socket(HOST, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Connected to server");

            // Step 2: Prompt and send Student_ID
            System.out.print("Enter your Student_ID: ");
            String studentId = scanner.nextLine();
            out.write(studentId);
            out.newLine();
            out.flush();

            // Receive and print uppercase ID
            String upperId = in.readLine();
            System.out.println("Server reply (uppercase ID): " + upperId);

            // Loop for user input
            while (true) {
                System.out.print("Enter message or file path (or 'QUIT' to exit): ");
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("QUIT")) {
                    out.write("QUIT");
                    out.newLine();
                    out.flush();
                    break;
                }

                // File sending block
                File file = new File(message);
                if (file.exists() && file.isFile()) {
                    dataOut.writeLong(file.length());
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            dataOut.write(buffer, 0, bytesRead);
                        }
                        dataOut.flush();
                    }
                    String fileResponse = in.readLine();
                    System.out.println("Server file reply: " + fileResponse);
                    continue;
                }

                // Send string message
                out.write(message);
                out.newLine();
                out.flush();

                // Receive and print response
                String response = in.readLine();
                System.out.println("Server reply: " + response);
            }
        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }
    }
}
