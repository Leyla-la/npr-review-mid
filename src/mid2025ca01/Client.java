// Client class: Handles connection, sending first ID, and loop with improved quit prompt

package mid2025ca01;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Client {
    private static final int PORT = 4075; // Same as server
    private static final String HOST = "localhost";
    private static final String STUDENT_ID = "12345"; // Replace with your actual Student ID

    public static void main(String[] args) throws IOException {
        Socket socket = null;
        BufferedWriter writer = null;
        BufferedReader reader = null;
        Scanner scanner = null;

        try {
            socket = new Socket(HOST, PORT);
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            scanner = new Scanner(System.in);

            System.out.println("Connected to server");

            // Send first Student_ID, receive 4*ID, print
            writer.write(STUDENT_ID);
            writer.newLine();
            writer.flush();
            String reply = reader.readLine();
            if (reply != null) {
                System.out.println("Reply (4*ID): " + reply);
            } else {
                System.out.println("Server disconnected during first reply");
                return;
            }

            // Loop to read keyboard, send, receive reply, print
            while (true) {
                System.out.print("Enter message (positive int for ^4, else echo): ");
                String input = scanner.nextLine();
                if (input == null) continue; // Skip null input
                if (input.isEmpty()) {
                    continue;
                }
                writer.write(input);
                writer.newLine();
                writer.flush();
                reply = reader.readLine();
                if (reply != null) {
                    System.out.println("Reply: " + reply);
                } else {
                    System.out.println("Server disconnected");
                    break;
                }

                // Improved Quit prompt with input validation (FIXED VERSION)
                boolean shouldQuit = false;
                while (true) {
                    System.out.print("Do you want to quit? (yes/no): ");
                    String quitChoice = scanner.nextLine().trim().toLowerCase();
                    if (quitChoice.isEmpty()) {
                        System.out.println("Error: Input cannot be empty. Please enter 'yes' or 'no'.");
                        continue;
                    }
                    if ("yes".equals(quitChoice)) {
                        writer.write("QUIT"); // Send quit signal to server
                        writer.newLine();
                        writer.flush();
                        System.out.println("Disconnecting...");
                        shouldQuit = true;
                        break; // Exit inner loop
                    } else if ("no".equals(quitChoice)) {
                        break; // Exit inner loop, continue main loop
                    } else {
                        System.out.println("Error: Invalid input. Please enter 'yes' or 'no'.");
                    }
                }

                if (shouldQuit) {
                    break; // Exit main loop
                }
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            // Close all resources safely
            try {
                if (scanner != null) scanner.close();
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                System.err.println("Error closing client resources: " + e.getMessage());
            }
        }
    }
}