package testcheck.ex2aAReserve;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class TCPClient {
    private static final String HOST = "localhost";
    private static final int PORT = 6789;

    public static void main(String[] args) {
        try (Socket socket = new Socket(HOST, PORT);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             Scanner scanner = new Scanner(System.in)) {

            System.out.print("Enter string: ");
            String input = scanner.nextLine();
            out.write(input);
            out.newLine();
            out.flush();

            String replaced = in.readLine();
            System.out.println("Replaced: " + replaced);

            String reversed = in.readLine();
            System.out.println("Reversed: " + reversed);

        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }
    }
}