package tut05.ex3;

import java.io.*;
import java.net.*;

class Server {
    public static void main(String argv[]) throws Exception {
        ServerSocket welcomeSocket = null;
        Socket connectionSocket = null;
        BufferedReader inFromClient = null;
        BufferedWriter outToClient = null;

        try {
            welcomeSocket = new ServerSocket(6789);
            System.out.println("Server is waiting to accept user... ");

            connectionSocket = welcomeSocket.accept();
            System.out.println("Accept a client!");

            inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
            outToClient = new BufferedWriter(new OutputStreamWriter(connectionSocket.getOutputStream()));

            // Read n from client
            String clientInput = inFromClient.readLine();
            int n = Integer.parseInt(clientInput);
            System.out.println("Received n: " + n);

            // Calculate square
            int square = n * n;

            // Send back to client
            outToClient.write(String.valueOf(square));
            outToClient.newLine();
            outToClient.flush();
            System.out.println("Sent square: " + square);

        } catch (IOException e) {
            System.out.println(e);
            e.printStackTrace();
        } finally {
            // Close resources
            try {
                if (outToClient != null) outToClient.close();
                if (inFromClient != null) inFromClient.close();
                if (connectionSocket != null) connectionSocket.close();
                if (welcomeSocket != null) welcomeSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Server stopped!");
    }
}