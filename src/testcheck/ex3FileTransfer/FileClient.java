package testcheck.ex3FileTransfer;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class FileClient {
    private static final String HOST = "localhost";
    private static final int PORT = 6789;

    public static void main(String[] args) {
        try (Socket socket = new Socket(HOST, PORT);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             DataInputStream dataIn = new DataInputStream(socket.getInputStream());
             Scanner scanner = new Scanner(System.in)) {

            System.out.print("Enter file name to download: ");
            String fileName = scanner.nextLine();
            out.write(fileName);
            out.newLine();
            out.flush();

            long fileSize = dataIn.readLong();
            if (fileSize == -1) {
                System.out.println("File not found on server");
                return;
            }

            try (FileOutputStream fos = new FileOutputStream("downloaded_" + fileName)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long remaining = fileSize;
                while (remaining > 0 && (bytesRead = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    remaining -= bytesRead;
                }
                System.out.println("File downloaded successfully");
            }

        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }
    }
}